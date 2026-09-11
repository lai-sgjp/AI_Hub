package cn.aitavern.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import androidx.lifecycle.ViewModelProvider
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun useChineseInterface() {
        compose.runOnIdle { ViewModelProvider(compose.activity)[TavernViewModel::class.java].language("zh") }
        compose.waitUntil(10000) { ViewModelProvider(compose.activity)[TavernViewModel::class.java].snapshot.value.settings.language=="zh" }
    }
    @Test fun mainDestinationsAndCharacterCreation() {
        val worldName="验收世界-${System.nanoTime()}"
        compose.onNodeWithText("新建世界").performClick()
        compose.onNodeWithText("世界名字").performTextClearance()
        compose.onNodeWithText("世界名字").performTextInput(worldName)
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5000) { runCatching { compose.onNode(hasScrollAction()).performScrollToNode(hasText(worldName)); true }.getOrDefault(false) }
        compose.onNodeWithText(worldName).performClick()
        compose.onNodeWithText("角色").performClick()
        compose.onNodeWithText("创建角色").performClick()
        compose.onNodeWithText("名字").performTextInput("测试旅人")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("测试旅人").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("世界书").performClick()
        compose.onNodeWithText("创建世界书").assertExists()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("管理 API / 密钥").performClick()
        compose.onNodeWithText("添加 API").assertExists()
    }
}
