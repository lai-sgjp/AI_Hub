package cn.aitavern.core

import kotlinx.coroutines.*
import kotlin.test.*
import org.junit.Test

class ContentWorkTest {
    @Test fun sourcePrefixIsIdenticalAcrossContentTasks() {
        val first=ContentPrompts.request("same long source","Translate into Japanese")
        val second=ContentPrompts.request("same long source","Make six openings")
        assertEquals(listOf("system","user"),first.map { it.role })
        assertEquals(first.first(),second.first())
        assertEquals(first.last().content.substringBefore("\n\nTask:\n"),second.last().content.substringBefore("\n\nTask:\n"))
        assertNotEquals(first.last(),second.last())
    }
    @Test fun changingMemoryDoesNotInvalidateStaticStoryPrefix() {
        val c=Character(name="A",description="detailed role card",personality="kind")
        val room=ChatRoom(scenario="unchanged world background",persona="visitor",pinned="important goal")
        val a=Engine.prompt(c,room.copy(summary="earlier"),listOf(c),emptyList(),emptyList())
        val b=Engine.prompt(c,room.copy(summary="later"),listOf(c),emptyList(),emptyList())
        assertEquals(a.take(2),b.take(2))
        for(value in listOf("detailed role card","kind","important goal","later")) assertTrue(b.any { value in it.content })
    }
    @Test fun lengthContinuesWithExactPrefixAndNeverRestarts() = runBlocking {
        val requests=mutableListOf<List<WireMessage>>()
        val checkpoints=mutableListOf<String>()
        val work=ContentCompletion({ _,_,messages,delta ->
            requests+=messages
            if(requests.size==1) { delta("{\"text\":\"hello "); throw OutputLimitException() }
            delta("world\"}")
        },retryDelayMillis=0)
        val result=work.run(ApiProfile(model="test"),"",listOf(WireMessage("user","source")),checkpoint={ checkpoints+=it })
        assertEquals("{\"text\":\"hello world\"}",result)
        assertEquals("{\"text\":\"hello ",requests[1].first { it.role=="assistant" }.content)
        assertEquals(listOf("{\"text\":\"hello "),checkpoints)
    }
    @Test fun retryAndContinuationBudgetsAreFinite() = runBlocking {
        var attempts=0
        val retry=ContentCompletion({ _,_,_,_ -> attempts++; throw ApiException("network",retryable=true) },retryDelayMillis=0)
        assertFailsWith<ApiException> { retry.run(ApiProfile(),"",emptyList()) }
        assertEquals(3,attempts)
        attempts=0
        val length=ContentCompletion({ _,_,_,delta -> attempts++; delta("part-$attempts "); throw OutputLimitException() },retryDelayMillis=0)
        var saved=""
        assertFailsWith<ContentContinuationLimit> { length.run(ApiProfile(),"",emptyList(),checkpoint={ saved=it }) }
        assertEquals(5,attempts); assertTrue(saved.contains("part-5"))
    }
    @Test fun cancellationKeepsPrefixWithoutStartingAnotherRequest() = runBlocking {
        val received=CompletableDeferred<Unit>(); var saved=""; var attempts=0
        val work=ContentCompletion({ _,_,_,delta -> attempts++; delta("partial"); received.complete(Unit); awaitCancellation() })
        val job=launch { work.run(ApiProfile(),"",emptyList(),checkpoint={ saved=it }) }
        received.await(); job.cancelAndJoin()
        assertEquals("partial",saved); assertEquals(1,attempts)
    }
    @Test fun permanentErrorsAndNoProgressDoNotRetry() = runBlocking<Unit> {
        var attempts=0
        val permanent=ContentCompletion({ _,_,_,_ -> attempts++; throw ApiException("401") },retryDelayMillis=0)
        assertFailsWith<ApiException> { permanent.run(ApiProfile(),"",emptyList()) }
        assertEquals(1,attempts)
        val repeated=ContentCompletion({ _,_,_,delta -> delta("saved prefix repeated"); throw OutputLimitException() })
        assertFailsWith<IllegalArgumentException> { repeated.run(ApiProfile(),"",emptyList(),initial="saved prefix repeated") }
    }
    @Test fun retryDiscardsOnlyFailedSegmentAndResumesSavedPrefix() = runBlocking {
        var attempts=0
        val work=ContentCompletion({ _,_,messages,delta ->
            assertEquals("saved ",messages.first { it.role=="assistant" }.content)
            if(attempts++==0) { delta("broken"); throw ApiException("network",retryable=true) }
            delta("suffix")
        },retryDelayMillis=0)
        assertEquals("saved suffix",work.run(ApiProfile(),"",emptyList(),initial="saved "))
        assertEquals(2,attempts)
    }
    @Test fun cancellationDuringBackoffDoesNotRetry() = runBlocking {
        val started=CompletableDeferred<Unit>(); var attempts=0
        val work=ContentCompletion({ _,_,_,_ -> attempts++; started.complete(Unit); throw ApiException("network",retryable=true) },retryDelayMillis=60000)
        val job=launch { work.run(ApiProfile(),"",emptyList()) }
        started.await(); yield(); job.cancelAndJoin()
        assertEquals(1,attempts)
    }
    @Test fun continuationDeduplicatesLongBoundaryAndKeepsOrdinaryRepetition() = runBlocking {
        val boundary="abcdefghijklmnop"
        val work=ContentCompletion({ _,_,_,delta -> delta(boundary+" end") })
        assertEquals("start $boundary end",work.run(ApiProfile(),"",emptyList(),initial="start $boundary"))
        val short=ContentCompletion({ _,_,_,delta -> delta("ha ha") })
        assertEquals("ha ha ha",short.run(ApiProfile(),"",emptyList(),initial="ha "))
        val exactShort=ContentCompletion({ _,_,_,delta -> delta("ha ") })
        assertEquals("ha ha ",exactShort.run(ApiProfile(),"",emptyList(),initial="ha "))
    }
    @Test fun storyPrefixPreservesSubstitutionsAndAllActiveContext() {
        val c=Character(name="阿岚",systemPrompt="系统{{char}}",description="描述",personality="性格",scenario="角色场景",examples="对话示例",postHistory="最后{{char}}回复{{user}}")
        val room=ChatRoom(userName="旅人",persona="人设",scenario="世界{{char}}与{{user}}",pinned="置顶",longSummary="长期",summary="近期")
        val prompt=Engine.prompt(c,room,listOf(c),listOf(Message(text="雪城")),listOf(LoreEntry(keys=listOf("雪城"),content="触发资料"),LoreEntry(constant=true,content="常驻资料")),"ja")
        val all=prompt.joinToString { it.content }
        for(value in listOf("系统阿岚","描述","性格","角色场景","对话示例","人设","世界阿岚与旅人","置顶","长期","近期","触发资料","常驻资料","雪城")) assertTrue(value in all,value)
        assertEquals("最后阿岚回复旅人",prompt.last().content)
        assertFalse("{{char}}" in all)
    }
    @Test fun identicalRequestsShareWorkAndPauseCancelsQueue() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val tasks=ContentTasks(scope); val gate=CompletableDeferred<Unit>(); var calls=0
            val one=async { tasks.run("same",false) { calls++; gate.await(); "result" } }
            val two=async { tasks.run("same",false) { calls++; "duplicate" } }
            yield(); gate.complete(Unit)
            assertEquals("result",one.await()); assertEquals("result",two.await()); assertEquals(1,calls)
            val pending=async { tasks.run("queued",true) { awaitCancellation() } }
            yield(); tasks.pause(); pending.cancelAndJoin()
            assertNull(tasks.run("new",true) { error("must not start") })
            assertEquals(0,tasks.pending.value)
            tasks.resume()
            assertEquals("ok",tasks.run("new",true) { "ok" })
        } finally { scope.cancel() }
    }
}
