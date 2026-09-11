package cn.aitavern.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.aitavern.core.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.serialization.json.*

@RunWith(AndroidJUnit4::class)
class StoryDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TavernApplication
    private fun vm()=ViewModelProvider(compose.activity)[TavernViewModel::class.java]
    private fun response(text: String)=MockResponse().setHeader("Content-Type","application/json").setBody(buildJsonObject {
        putJsonArray("choices") { addJsonObject { putJsonObject("message") { put("content",text) }; put("finish_reason","stop") } }
    }.toString())
    @Test fun graphShowsConvergenceAndNodeOpensRoom() {
        val c=Character(name="旅人"); val w=World(name="图示验收",description="两条道路通向同一终点。",characterIds=listOf(c.id))
        val p=ApiProfile(model="test")
        val root=ChatRoom(name="起点",worldId=w.id,memberIds=listOf(c.id),profileId=p.id)
        val a=root.copy(id=newId(),name="山路",parentId=root.id,branchChoice="翻越山岭")
        val b=root.copy(id=newId(),name="河路",parentId=root.id,branchChoice="沿河前进")
        runBlocking {
            app.repository.merge(Snapshot(characters=listOf(c),worlds=listOf(w),profiles=listOf(p),rooms=listOf(root,a,b)))
            val s=app.repository.snapshot()
            for(r in listOf(a,b)) app.repository.save(StoryEnding(roomId=r.id,versionId=s.rooms.first { it.id==r.id }.activeVersionId,fingerprint=StoryContent.fingerprint(""),groupId="graph-test-${root.id}",title="平安抵达",outcome="所有人抵达城镇",reason="任务和人物状态相同"))
        }
        compose.runOnIdle { vm().language("zh"); vm().activeRoom.value=null; vm().activeWorld.value=w.id }
        compose.waitUntil { vm().snapshot.value.settings.language=="zh" }
        compose.onNodeWithText("剧情分支图").performScrollTo().performClick()
        repeat(5) { compose.onNodeWithText("－").performClick() }
        compose.onNodeWithText("◎ 平安抵达").assertExists()
        val screenshot=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(app.filesDir,"story-map-verification.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        compose.onNodeWithText("● 起点").performClick()
        compose.waitUntil { vm().activeRoom.value==root.id }
        compose.onNodeWithContentDescription("返回").assertExists()
    }
    @Test fun regenerateSwitchAndRestartPreserveBothFutures() {
        val server=MockWebServer()
        server.dispatcher=object: Dispatcher() { override fun dispatch(request: RecordedRequest)=
            if(request.body.clone().readUtf8().contains("明确结局")) response("{\"ended\":false}") else response("[阿岚] 阿岚：新的回答") }
        server.start()
        try {
            val c=Character(name="阿岚"); val w=World(name="版本测试",characterIds=listOf(c.id))
            val p=ApiProfile(model="test",baseUrl=server.url("/v1").toString(),stream=false)
            val r=ChatRoom(worldId=w.id,memberIds=listOf(c.id),profileId=p.id,summary="旧摘要",longSummary="旧长期",summaryThrough=2)
            val old=Message(roomId=r.id,speakerId=c.id,sequence=1,text="旧回答")
            runBlocking {
                app.repository.merge(Snapshot(characters=listOf(c),worlds=listOf(w),profiles=listOf(p),rooms=listOf(r),messages=listOf(Message(roomId=r.id,sequence=0,text="选择"),old,Message(roomId=r.id,sequence=2,text="旧未来")),memories=listOf(MemoryFact(worldId=w.id,roomId=r.id,sourceEnd=2,subject="未来",content="旧事实")),segments=listOf(SummarySegment(worldId=w.id,roomId=r.id,text="旧阶段"))))
            }
            val original=runBlocking { app.repository.snapshot() }
            val oldId=original.rooms.first { it.id==r.id }.activeVersionId
            compose.runOnIdle { vm().regenerate(old) }
            compose.waitUntil(15000) { runBlocking { app.repository.snapshot().rooms.first { it.id==r.id }.activeVersionId!=oldId } }
            val after=runBlocking { app.repository.snapshot() }
            assertEquals(original.rooms.size,after.rooms.size)
            assertEquals(listOf("选择","新的回答"),after.messages.filter { it.roomId==r.id }.sortedBy { it.sequence }.map { it.text })
            assertFalse(after.memories.any { it.roomId==r.id }); assertFalse(after.segments.any { it.roomId==r.id })
            compose.waitUntil { !vm().busy.value }
            compose.runOnIdle { vm().switchVersion(oldId) }
            compose.waitUntil { runBlocking { app.repository.snapshot().rooms.first { it.id==r.id }.activeVersionId==oldId } }
            runBlocking { app.repository.recover() }
            val restored=runBlocking { app.repository.snapshot() }
            assertEquals("旧未来",restored.messages.filter { it.roomId==r.id }.maxBy { it.sequence }.text)
            assertEquals("旧摘要",restored.rooms.first { it.id==r.id }.summary)
            assertEquals("旧事实",restored.memories.first { it.roomId==r.id }.content)
            assertEquals("旧阶段",restored.segments.first { it.roomId==r.id }.text)
            BackupCodec.validate(BackupCodec.decode(BackupCodec.encode(restored)))
        } finally { server.shutdown() }
    }
    @Test fun languageSurvivesThemeChangeAndOpeningStartsWithoutScenarioInput() {
        val c=Character(name="Guide",greeting="Welcome")
        val w=World(name="Opening test",characterIds=listOf(c.id),personas=listOf(Persona(name="Visitor")))
        val p=ApiProfile(model="test")
        runBlocking { app.repository.merge(Snapshot(characters=listOf(c),worlds=listOf(w),profiles=listOf(p))) }
        compose.runOnIdle { vm().language("en") }
        compose.waitUntil { runBlocking { app.repository.snapshot().settings.language=="en" } }
        compose.runOnIdle { vm().theme("dark") }
        compose.waitUntil { runBlocking { app.repository.snapshot().settings.theme=="dark" } }
        assertEquals("en",runBlocking { app.repository.snapshot().settings.language })
        compose.runOnIdle { vm().activeWorld.value=null; vm().activeRoom.value=null }
        compose.onNodeWithText("Choose your world").assertExists()
        val entry=StoryContent.entries(w,listOf(c)).single()
        compose.runOnIdle { vm().startStory(w,entry,p.id,listOf(c.id),w.personas.single().id) }
        compose.waitUntil { runBlocking { app.repository.snapshot().rooms.any { it.worldId==w.id } } }
        val s=runBlocking { app.repository.snapshot() }
        val room=s.rooms.single { it.worldId==w.id }
        assertEquals("Welcome",s.messages.single { it.roomId==room.id }.text)
        assertEquals("Visitor",room.userName)
        compose.runOnIdle { vm().language("ja") }
        compose.waitUntil { runBlocking { app.repository.snapshot().settings.language=="ja" } }
        compose.runOnIdle { vm().activeWorld.value=null; vm().activeRoom.value=null }
        compose.onNodeWithText("世界を選ぶ").assertExists()
        compose.runOnIdle { vm().language("zh"); vm().theme("system") }
    }
    @Test fun failedRegenerationKeepsOriginalActiveAndCandidateRecoverable() {
        val server=MockWebServer(); server.enqueue(MockResponse().setResponseCode(500)); server.start()
        try {
            val c=Character(name="A"); val w=World(characterIds=listOf(c.id)); val p=ApiProfile(model="test",stream=false,baseUrl=server.url("/v1").toString())
            val r=ChatRoom(worldId=w.id,memberIds=listOf(c.id),profileId=p.id)
            val m=Message(roomId=r.id,speakerId=c.id,text="keep me")
            runBlocking { app.repository.merge(Snapshot(characters=listOf(c),worlds=listOf(w),profiles=listOf(p),rooms=listOf(r),messages=listOf(m))) }
            val old=runBlocking { app.repository.snapshot().rooms.first { it.id==r.id }.activeVersionId }
            compose.runOnIdle { vm().regenerate(m) }
            compose.waitUntil(10000) { runBlocking { app.repository.snapshot().versions.any { it.roomId==r.id && it.status=="failed" } } }
            val s=runBlocking { app.repository.snapshot() }
            assertEquals(old,s.rooms.first { it.id==r.id }.activeVersionId)
            assertEquals(listOf(m),s.messages.filter { it.roomId==r.id })
            runBlocking { app.repository.recover() }
            assertTrue(runBlocking { app.repository.snapshot().versions.any { it.roomId==r.id && it.status=="failed" } })
            compose.runOnIdle { vm().notice.value=null }
        } finally { server.shutdown() }
    }
    @Test fun automaticEndingConvergesWithoutMergingHistoryAndUndoSticks() {
        val matching=java.util.concurrent.atomic.AtomicReference("")
        val server=MockWebServer()
        server.dispatcher=object: Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
            val body=request.body.clone().readUtf8()
            return if(body.contains("明确结局")) response("{\"ended\":true,\"title\":\"平安归来\",\"outcome\":\"任务完成，所有人平安\",\"matchingGroup\":\"${matching.get()}\",\"reason\":\"任务结果、人物状态和处境一致\"}") else response("任务完成，所有人平安。")
        } }
        server.start()
        try {
            val c=Character(name="A"); val w=World(characterIds=listOf(c.id)); val p=ApiProfile(model="test",stream=false,baseUrl=server.url("/v1").toString())
            val root=ChatRoom(worldId=w.id,memberIds=listOf(c.id),profileId=p.id)
            val child=root.copy(id=newId(),parentId=root.id)
            runBlocking { app.repository.merge(Snapshot(characters=listOf(c),worlds=listOf(w),profiles=listOf(p),rooms=listOf(root,child))) }
            compose.runOnIdle { vm().activeRoom.value=root.id; vm().send("结局一") }
            compose.waitUntil(15000) { runBlocking { app.repository.snapshot().endings.any { it.roomId==root.id && it.groupId.isNotBlank() } } }
            val parent=runBlocking { app.repository.snapshot().endings.first { it.roomId==root.id } }
            matching.set(parent.groupId)
            compose.runOnIdle { vm().activeRoom.value=child.id; vm().send("另一条路线") }
            compose.waitUntil(15000) { runBlocking { app.repository.snapshot().endings.any { it.roomId==child.id && it.groupId==parent.groupId } } }
            val before=runBlocking { app.repository.snapshot() }
            val ending=before.endings.first { it.roomId==child.id }
            compose.runOnIdle { vm().setEndingGroup(ending,null) }
            compose.waitUntil { runBlocking { app.repository.snapshot().endings.first { it.id==ending.id }.groupId!=parent.groupId } }
            compose.runOnIdle { vm().send("仍然是同一个结局") }
            compose.waitUntil(15000) { !vm().busy.value && runBlocking { app.repository.snapshot().messages.count { it.roomId==child.id }==4 } }
            compose.waitUntil(15000) { vm().contentBusy.value==0 }
            val after=runBlocking { app.repository.snapshot() }
            assertNotEquals(parent.groupId,after.endings.first { it.roomId==child.id }.groupId)
            assertEquals(before.messages.filter { it.roomId==root.id },after.messages.filter { it.roomId==root.id })
            assertTrue(after.endings.first { it.roomId==child.id }.rejectedGroups.contains(parent.groupId))
        } finally { server.shutdown() }
    }
    @Test fun worldEntriesGuideTranslationAndBranchNamingUseCachedApiResults() {
        val c=Character(name="Guide",greeting="Hello"); val w=World(description="A quiet town",characterIds=listOf(c.id))
        val server=MockWebServer()
        server.dispatcher=object: Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
            val body=request.body.clone().readUtf8()
            return when {
                body.contains("恰好六个") -> response(buildJsonArray { repeat(6) { n -> addJsonObject { put("title","Opening $n"); put("background","Town"); putJsonArray("memberIds") { add(c.id) }; put("personaId",""); put("objective","Explore"); put("opening","Hello $n") } } }.toString())
                body.contains("为剧情分支命名") -> response("守护书店")
                body.contains("整理无剧透") -> response("World guide: a quiet town, free exploration.")
                body.contains("将以下文本") -> response("Translated message")
                else -> response("{\"ended\":false}")
            }
        } }
        server.start()
        try {
            val p=ApiProfile(model="test",stream=false,baseUrl=server.url("/v1").toString())
            val r=ChatRoom(worldId=w.id,memberIds=listOf(c.id),profileId=p.id)
            val m=Message(roomId=r.id,speakerId=c.id,text="Original")
            runBlocking { app.repository.merge(Snapshot(characters=listOf(c),worlds=listOf(w),profiles=listOf(p),rooms=listOf(r),messages=listOf(m))) }
            val result=java.util.concurrent.atomic.AtomicReference("")
            compose.runOnIdle { vm().worldContent(w.id,"",p.id,true) { result.set(it) } }
            compose.waitUntil(10000) { result.get().isNotBlank() }
            assertEquals(6,TavernJson.parseToJsonElement(result.get()).jsonArray.size)
            val count=server.requestCount
            result.set("")
            compose.runOnIdle { vm().worldContent(w.id,"",p.id,true) { result.set(it) } }
            compose.waitUntil { result.get().isNotBlank() }
            assertEquals(count,server.requestCount)
            result.set("")
            compose.runOnIdle { vm().worldContent(w.id,"",p.id,false) { result.set(it) } }
            compose.waitUntil(10000) { result.get().startsWith("World guide") }
            result.set("")
            compose.runOnIdle { vm().translate("original",p.id) { result.set(it) } }
            compose.waitUntil(10000) { result.get()=="Translated message" }
            val translations=server.requestCount
            result.set("")
            compose.runOnIdle { vm().translate("original",p.id) { result.set(it) } }
            compose.waitUntil { result.get().isNotBlank() }
            assertEquals(translations,server.requestCount)
            compose.runOnIdle { vm().branch(m,"Stay at the shop") }
            compose.waitUntil(10000) { runBlocking { app.repository.snapshot().rooms.any { it.parentId==r.id && it.name=="守护书店" } } }
            assertEquals("Original",runBlocking { app.repository.snapshot().messages.single { it.roomId==r.id }.text })
            compose.runOnIdle { vm().notice.value=null }
        } finally { server.shutdown() }
    }
}
