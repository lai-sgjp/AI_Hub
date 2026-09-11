package cn.aitavern.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.aitavern.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ContentWorkDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TavernApplication
    private fun vm()=ViewModelProvider(compose.activity)[TavernViewModel::class.java]
    private fun response(text: String, reason: String="stop")=MockResponse().setHeader("Content-Type","application/json").setBody(buildJsonObject {
        putJsonArray("choices") { addJsonObject { putJsonObject("message") { put("content",text) }; put("finish_reason",reason) } }
    }.toString())
    private fun prepare(server: MockWebServer): ApiProfile {
        val p=ApiProfile(model="test",baseUrl=server.url("/v1").toString())
        compose.runOnIdle { vm().activeWorld.value=null; vm().activeRoom.value=null; vm().notice.value=null; vm().resumeContent(); vm().language("zh") }
        compose.waitUntil { !vm().contentPaused.value && vm().snapshot.value.settings.language=="zh" }
        runBlocking { app.repository.save(p) }
        return p
    }
    private fun finish(server: MockWebServer) {
        compose.runOnIdle { vm().stopContent(); vm().notice.value=null }
        compose.waitUntil { vm().contentPending.value==0 }
        server.shutdown()
    }
    @Test fun duplicateTranslationSharesRequestAndCompletedCache() {
        val server=MockWebServer(); server.enqueue(response("同一份译文").setBodyDelay(1,TimeUnit.SECONDS)); server.start()
        try {
            val p=prepare(server); val source="unique-${newId()}"; val completed=AtomicInteger()
            compose.runOnIdle { repeat(3) { vm().translate(source,p.id) { assertEquals("同一份译文",it); completed.incrementAndGet() } } }
            compose.waitUntil(10000) { completed.get()==3 }
            compose.runOnIdle { vm().translate(source,p.id) { completed.incrementAndGet() } }
            compose.waitUntil { completed.get()==4 }
            assertEquals(1,server.requestCount)
            val key="translation:zh:${StoryContent.fingerprint(source)}"
            assertEquals(1,runBlocking { app.repository.snapshot().contentCache.count { it.key==key && it.status=="complete" } })
        } finally { finish(server) }
    }
    @Test fun stopButtonCancelsActiveAndQueuedAndKeepsAutomaticWorkPaused() {
        val server=MockWebServer()
        repeat(3) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
        server.start()
        try {
            val p=prepare(server)
            compose.runOnIdle { repeat(3) { vm().translate("pending-${newId()}",p.id) { fail("cancelled translation must not finish") } } }
            compose.waitUntil(10000) { server.requestCount==2 && vm().contentPending.value==3 }
            compose.onNodeWithText("停止翻译").performClick()
            compose.waitUntil(10000) { vm().contentPending.value==0 && runBlocking { app.repository.snapshot().settings.autoContentPaused } }
            compose.onNodeWithText("自动翻译已暂停").assertExists()
            runBlocking { repeat(5) { vm().translate("reentered-${newId()}",p.id,automatic=true) { fail("must stay paused") }.join() } }
            assertEquals(2,server.requestCount)
            // Updating unrelated settings cannot undo the persisted pause.
            compose.runOnIdle { vm().theme("dark") }
            compose.waitUntil { vm().snapshot.value.settings.theme=="dark" }
            assertTrue(runBlocking { app.repository.snapshot().settings.autoContentPaused })
            val store=ViewModelStore(); var reopened: TavernViewModel?=null
            try {
                compose.runOnIdle { reopened=TavernViewModel(app); store.put("restart",reopened!!) }
                compose.waitUntil { reopened!!.contentPaused.value }
                runBlocking { reopened!!.translate("after-restart",p.id,automatic=true) { fail("restart must stay paused") }.join() }
                assertEquals(2,server.requestCount)
            } finally { compose.runOnIdle { store.clear() } }
        } finally { finish(server) }
    }
    @Test fun repeatedFailureStopsAfterThreeRequestsAndNeedsExplicitRetry() {
        val server=MockWebServer(); repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }; server.enqueue(response("重试成功")); server.start()
        try {
            val p=prepare(server); val source="failure-${newId()}"
            compose.runOnIdle { vm().translate(source,p.id,automatic=true) { fail("503 is not a translation") } }
            compose.waitUntil(15000) { vm().contentPaused.value && vm().contentPending.value==0 }
            assertEquals(3,server.requestCount)
            runBlocking { repeat(4) { vm().translate(source,p.id,automatic=true) {}.join() } }
            assertEquals(3,server.requestCount)
            val result=AtomicReference<String>()
            compose.runOnIdle { vm().notice.value=null; vm().translate(source,p.id) { result.set(it) } }
            compose.waitUntil(10000) { result.get()!=null }
            assertEquals("重试成功",result.get()); assertEquals(4,server.requestCount)
        } finally { finish(server) }
    }
    @Test fun lengthLimitPersistsCheckpointAndExplicitRetryContinuesIt() {
        val server=MockWebServer(); val requests=AtomicInteger(); val resumed=AtomicReference<String>()
        server.dispatcher=object: Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
            val n=requests.incrementAndGet()
            if(n<=5) return response("片段$n ","length")
            resumed.set(request.body.readUtf8()); return response("结尾")
        } }
        server.start()
        try {
            val p=prepare(server); val source="length-${newId()}"; val result=AtomicReference<String>()
            compose.runOnIdle { vm().translate(source,p.id) { result.set(it) } }
            compose.waitUntil(15000) { vm().contentPaused.value && vm().contentPending.value==0 }
            assertNull(result.get()); assertEquals(5,requests.get())
            val caches=runBlocking { app.repository.snapshot().contentCache }
            assertTrue(caches.any { it.status=="partial" && it.text=="片段1 片段2 片段3 片段4 片段5 " })
            assertFalse(caches.any { it.key=="translation:zh:${StoryContent.fingerprint(source)}" })
            compose.runOnIdle { vm().notice.value=null; vm().translate(source,p.id) { result.set(it) } }
            compose.waitUntil(10000) { result.get()!=null }
            assertEquals("片段1 片段2 片段3 片段4 片段5 结尾",result.get())
            val messages=TavernJson.parseToJsonElement(resumed.get()).jsonObject["messages"]!!.jsonArray
            assertEquals("片段1 片段2 片段3 片段4 片段5 ",messages.first { it.jsonObject["role"]!!.jsonPrimitive.content=="assistant" }.jsonObject["content"]!!.jsonPrimitive.content)
            assertEquals(6,requests.get())
        } finally { finish(server) }
    }
}
