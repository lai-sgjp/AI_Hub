package cn.aitavern.core

import kotlin.test.*
import org.junit.Test

class EngineTest {
    private val a = Character(id="a", name="阿岚")
    private val b = Character(id="b", name="小雪")
    @Test fun leastRecentSpeakerGoesFirst() {
        val history = listOf(Message(speakerId="a", text="你好"))
        assertEquals(listOf("b", "a"), Engine.selectSpeakers(listOf(a,b), history, 2).map { it.id })
    }
    @Test fun nominationOverridesRotation() {
        assertEquals(listOf("a"), Engine.selectSpeakers(listOf(a,b), listOf(Message(speakerId="a")), 2, "a").map { it.id })
    }
    @Test fun unknownNominationRejected() {
        assertFailsWith<IllegalArgumentException> { Engine.selectSpeakers(listOf(a), emptyList(), 1, "gone") }
    }
    @Test fun worldEntriesRespectWindowAndPriority() {
        val entries = listOf(LoreEntry(content="旧事", keys=listOf("旧")), LoreEntry(content="雪", keys=listOf("雪"), priority=9), LoreEntry(content="常驻", constant=true), LoreEntry(content="禁用", constant=true, enabled=false))
        val history = listOf(Message(text="旧")) + List(6) { Message(text="雪") }
        assertEquals(listOf("雪", "常驻"), Engine.activeLore(entries, history).map { it.content })
    }
    @Test fun promptIncludesOnlyCurrentCharacterAndNamedHistory() {
        val room = ChatRoom(userName="旅人", scenario="车站", pinned="寻找钥匙", summary="刚刚抵达")
        val prompt = Engine.prompt(a.copy(description="{{char}}等待{{user}}"), room, listOf(a,b), listOf(Message(speakerId="b", text="欢迎")), emptyList())
        assertTrue(prompt.first().content.contains("阿岚等待旅人"))
        assertTrue(prompt.first().content.contains("寻找钥匙"))
        assertTrue(prompt.any { it.role == "assistant" && it.content.contains("小雪") })
    }
    @Test fun oversizedFixedPromptFailsInsteadOfDroppingIt() {
        assertFailsWith<IllegalArgumentException> { Engine.checkBudget(listOf(WireMessage("system", "字".repeat(500))), 100) }
    }
    @Test fun branchKeepsOriginalAndResetsSummary() {
        val original = ChatRoom(summary="未来事件", summaryThrough=2)
        val messages = listOf(Message(text="旧"), Message(text="未来"))
        val (branch, copied) = Engine.branch(original, messages, 0, "新")
        assertEquals("旧", messages[0].text)
        assertEquals("新", copied.single().text)
        assertEquals("", branch.summary)
        assertNotEquals(original.id, branch.id)
    }
}
