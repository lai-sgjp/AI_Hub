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
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ChatIntegrationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
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
        compose.onNodeWithText("雪夜书店").performClick()
    }
    @Test fun memoryTableIsWorldIsolatedAndEditableInDarkMode() {
        val server=MockWebServer(); server.start()
        try {
            val world="记忆验收-${System.nanoTime()}"
            val room=fixture(server,world)
            runBlocking {
                app.repository.save(AppSettings("dark"))
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
        } finally { runBlocking { app.repository.save(AppSettings("system")) }; server.shutdown() }
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
