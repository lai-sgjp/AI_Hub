package cn.aitavern.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.aitavern.core.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import androidx.lifecycle.ViewModelProvider
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ChatIntegrationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Before fun useChineseInterface() {
        compose.runOnIdle { ViewModelProvider(compose.activity)[TavernViewModel::class.java].language("zh") }
        compose.waitUntil(10000) { ViewModelProvider(compose.activity)[TavernViewModel::class.java].snapshot.value.settings.language=="zh" }
    }
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TavernApplication
    private fun fixture(server: MockWebServer,name: String): ChatRoom = runBlocking {
        val c=Character(name="阿岚",description="在雪城经营书店的温柔旅人")
        val world=World(name=name,description="永夜雪城",characterIds=listOf(c.id))
        val profile=ApiProfile(name="本地验收",model="test",baseUrl=server.url("/v1").toString())
        val room=ChatRoom(name="雪夜书店",worldId=world.id,memberIds=listOf(c.id),profileId=profile.id)
        app.secrets.put(profile.id,"instrumentation-only-secret")
        app.repository.merge(Snapshot(characters=listOf(c),worlds=listOf(world),profiles=listOf(profile),rooms=listOf(room)))
        room
    }
    private fun enter(world: String) {
        compose.waitUntil(10000) {
            runCatching { compose.onNode(hasScrollAction()).performScrollToNode(hasText(world)); true }.getOrDefault(false)
        }
        compose.onNodeWithText(world).performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("雪夜书店"))
        compose.onNodeWithText("雪夜书店").performClick()
    }
    @Test fun switchApiDuringStreamKeepsPartialAndUsesNewKey() {
        val server=MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"切换前部分\"}}]}\n\n"+"data: {\"choices\":[{\"delta\":{\"content\":\"稍后\"}}]}\n\n".repeat(100)).throttleBody(90,1,TimeUnit.SECONDS))
        server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"新密钥回复\"}}]}\n\ndata: [DONE]\n\n"))
        server.start()
        try {
            val world="切换验收-${System.nanoTime()}"
            val room=fixture(server,world)
            val replacement=ApiProfile(name="备用-${System.nanoTime()}",model="test",baseUrl=server.url("/v1").toString())
            runBlocking { app.repository.save(replacement); app.secrets.put(replacement.id,"replacement-test-secret") }
            enter(world)
            compose.onNodeWithText("写下你的回应…").performTextInput("开始")
            compose.onNodeWithContentDescription("发送").performClick()
            compose.waitUntil(15000) { compose.onAllNodes(hasText("切换前部分",substring=true)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("API：本地验收 · 切换 / 管理").performClick()
            compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("添加 API"))).performScrollToNode(hasText(replacement.name))
            compose.onNode(hasText(replacement.name) and hasAnySibling(hasText("test"))).assertExists()
            compose.onNode(hasText("切换到此 API") and hasAnySibling(hasText("编辑")) and hasAnyAncestor(hasAnyChild(hasText(replacement.name)))).performClick()
            compose.waitUntil(10000) { runBlocking { app.repository.snapshot().rooms.find { it.id==room.id }?.profileId==replacement.id } }
            compose.onNodeWithText("知道了").performClick()
            compose.onNodeWithText("完成").performClick()
            assertEquals(1,server.requestCount)
            val saved=runBlocking { app.repository.snapshot() }
            assertTrue(saved.messages.any { it.roomId==room.id && it.status=="interrupted" && it.text.startsWith("切换前部分") })
            compose.onNodeWithText("写下你的回应…").performTextInput("继续剧情")
            compose.onNodeWithContentDescription("发送").performClick()
            try { compose.waitUntil(15000) { compose.onAllNodesWithText("新密钥回复").fetchSemanticsNodes().isNotEmpty() } }
            catch(e: ComposeTimeoutException) {
                val model=ViewModelProvider(compose.activity)[TavernViewModel::class.java]
                val statuses=runBlocking { app.repository.snapshot().messages.filter { it.roomId==room.id }.map { it.status } }
                throw AssertionError("Synthetic switch test: requests=${server.requestCount}, busy=${model.busy.value}, notice=${model.notice.value}, savedStatuses=$statuses",e)
            }
            assertEquals("Bearer instrumentation-only-secret",server.takeRequest(2,TimeUnit.SECONDS)!!.getHeader("Authorization"))
            assertEquals("Bearer replacement-test-secret",server.takeRequest(2,TimeUnit.SECONDS)!!.getHeader("Authorization"))
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("API：${replacement.name} · 切换 / 管理").assertExists()
        } finally { server.shutdown() }
    }
    @Test fun deletingInUseApiIsRejectedAndUnusedKeyCanBeRemoved() = runBlocking {
        val profile=ApiProfile(name="unused",model="test")
        app.repository.save(profile)
        app.secrets.put(profile.id,"removal-test-secret")
        val character=Character(name="测试角色")
        val world=World(name="删除配置验收",characterIds=listOf(character.id))
        app.repository.save(character); app.repository.save(world)
        val room=ChatRoom(profileId=profile.id,worldId=world.id,memberIds=listOf(character.id))
        app.repository.save(room)
        try { app.repository.deleteUnusedProfile(profile.id); fail("In-use profile deleted") } catch(_: IllegalArgumentException) { }
        assertTrue(app.repository.snapshot().profiles.any { it.id==profile.id })
        val unused=ApiProfile(name="delete",model="test")
        app.repository.save(unused); app.secrets.put(unused.id,"removal-test-secret")
        app.repository.deleteUnusedProfile(unused.id); app.secrets.remove(unused.id)
        assertFalse(app.repository.snapshot().profiles.any { it.id==unused.id })
        assertEquals("",app.secrets.get(unused.id))
    }
    @Test fun memoryTableIsWorldIsolatedAndEditableInDarkMode() {
        val server=MockWebServer(); server.start()
        try {
            val world="记忆验收-${System.nanoTime()}"
            val room=fixture(server,world)
            runBlocking {
                app.repository.save(app.repository.snapshot().settings.copy(theme="dark"))
                app.repository.save(MemoryFact(worldId=room.worldId,roomId=room.id,category="物品",subject="银钥匙",content="由旅人保管",locked=true))
                val other=World(name="不相关的世界",characterIds=room.memberIds)
                val otherRoom=room.copy(id=newId(),worldId=other.id,name="异界房间")
                app.repository.save(other)
                app.repository.save(otherRoom)
                app.repository.save(MemoryFact(worldId=other.id,roomId=otherRoom.id,scope="world",subject="异界秘密",content="不应显示"))
            }
            enter(world)
            compose.onNodeWithContentDescription("记忆中心").performClick()
            compose.onNodeWithText("记忆表").performClick()
            compose.onNodeWithText("银钥匙").assertExists()
            compose.onNodeWithText("异界秘密").assertDoesNotExist()
            compose.onNodeWithText("由旅人保管").performClick()
            compose.onNodeWithText("事实内容").performTextClearance()
            compose.onNodeWithText("事实内容").performTextInput("由阿岚保管")
            compose.onAllNodesWithText("保存").onLast().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("由阿岚保管").fetchSemanticsNodes().isNotEmpty() }
            File(app.filesDir,"memory-verification.png").outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG,100,it) }
        } finally { runBlocking { app.repository.save(app.repository.snapshot().settings.copy(theme="system")) }; server.shutdown() }
    }
    @Test fun streamPersistsAcrossActivityRecreationAndBackupExcludesSecret() {
        val server=MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"欢迎来到雪夜书店。窗外的雪还没有停，要喝杯热茶吗？\"}}]}\n\ndata: [DONE]\n\n"))
        server.start()
        try {
            val world="流式验收-${System.nanoTime()}"
            val room=fixture(server,world)
            enter(world)
            compose.onNodeWithText("写下你的回应…").performTextInput("你好，阿岚。")
            compose.onNodeWithContentDescription("发送").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("欢迎来到雪夜书店。窗外的雪还没有停，要喝杯热茶吗？").fetchSemanticsNodes().isNotEmpty() }
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("欢迎来到雪夜书店。窗外的雪还没有停，要喝杯热茶吗？").assertExists()
            val snapshot=runBlocking { app.repository.snapshot() }
            assertTrue(snapshot.messages.any { it.roomId==room.id && it.status=="complete" && it.speakerId!=null })
            assertEquals("Bearer instrumentation-only-secret",server.takeRequest(2,TimeUnit.SECONDS)!!.getHeader("Authorization"))
            val restored=BackupCodec.decode(BackupCodec.encode(snapshot))
            assertFalse(TavernJson.encodeToString(Snapshot.serializer(),restored).contains("instrumentation-only-secret"))
            val file=File(app.filesDir,"chat-verification.png")
            file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
        } finally { server.shutdown() }
    }
    @Test fun backgroundStopsGenerationAndPersistsPartial() {
        val server=MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"部分回复\"}}]}\n\n"+"data: {\"choices\":[{\"delta\":{\"content\":\"后续\"}}]}\n\n".repeat(100)).throttleBody(90,1,TimeUnit.SECONDS))
        server.start()
        try {
            val world="后台验收-${System.nanoTime()}"
            val room=fixture(server,world)
            enter(world)
            compose.onNodeWithText("写下你的回应…").performTextInput("开始")
            compose.onNodeWithContentDescription("发送").performClick()
            compose.waitUntil(15000) { compose.onAllNodes(hasText("部分回复",substring=true)).fetchSemanticsNodes().isNotEmpty() }
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            val deadline=System.currentTimeMillis()+5000
            var messages=emptyList<Message>()
            while(System.currentTimeMillis()<deadline) {
                messages=runBlocking { app.repository.snapshot().messages.filter { it.roomId==room.id && it.speakerId!=null } }
                if(messages.any { it.status=="interrupted" }) break
                Thread.sleep(100)
            }
            assertEquals("interrupted",messages.last().status)
            assertTrue(messages.last().text.startsWith("部分回复"))
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        } finally { server.shutdown() }
    }
}
