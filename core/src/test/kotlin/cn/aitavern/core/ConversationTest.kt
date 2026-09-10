package cn.aitavern.core

import kotlinx.coroutines.*
import kotlin.test.*
import org.junit.Test

class ConversationTest {
    @Test fun summariesFactsAndVectorsCommitTogether() = runBlocking {
        val a=Character(name="A"); val p=ApiProfile(model="test",embeddingModel="vec",contextSize=16000)
        val w=World(name="雪城",description="永夜的雪城",characterIds=listOf(a.id))
        val r=ChatRoom(worldId=w.id,memberIds=listOf(a.id),profileId=p.id)
        val history=List(12) { Message(roomId=r.id,sequence=it,text="我们找到了钥匙") }
        var committed: ChatRoom?=null; var facts=emptyList<MemoryFact>(); var segment: SummarySegment?=null
        val requests=mutableListOf<List<WireMessage>>()
        ConversationRunner { _,_,messages,delta ->
            requests+=messages
            if(messages.first().content.contains("short_summary")) delta("""{"short_summary":"找到钥匙","long_summary":"旅人在雪城找到钥匙","facts":[{"category":"物品","subject":"钥匙位置","content":"旅人持有"}]}""") else delta("继续前进")
        }.run(Snapshot(characters=listOf(a),profiles=listOf(p),rooms=listOf(r),worlds=listOf(w),messages=history),r.id,"",null,{}, {}, {},
            commitMemory={ room,values,part -> committed=room; facts=values; segment=part },embed={ _,_,texts -> texts.map { listOf(1f,0f) } })
        assertEquals(5,committed!!.summaryThrough)
        assertEquals("找到钥匙",segment!!.text)
        assertEquals(listOf(1f,0f),facts.single().embedding)
        assertTrue(requests.last().first().content.contains("永夜的雪城"))
        assertTrue(requests.last().any { it.content.contains("旅人持有") })
    }
    @Test fun cancellationPreservesPartialAndDoesNotStartNextRole() = runBlocking {
        val a=Character(name="A"); val b=Character(name="B"); val p=ApiProfile(model="test")
        val r=ChatRoom(memberIds=listOf(a.id,b.id),profileId=p.id,replies=2)
        val started=CompletableDeferred<Unit>(); val saved=mutableListOf<Message>(); var calls=0
        val job=launch {
            ConversationRunner { _,_,_,delta -> calls++; delta("正在写"); started.complete(Unit); delay(10000) }
                .run(Snapshot(characters=listOf(a,b),profiles=listOf(p),rooms=listOf(r)),r.id,"",null,{}, { saved+=it },{})
        }
        started.await(); job.cancelAndJoin()
        assertEquals(1,calls); assertEquals("interrupted",saved.last().status); assertEquals("正在写",saved.last().text)
    }
    @Test fun invalidSummaryJsonDoesNotOverwriteExistingMemory() = runBlocking {
        val a=Character(name="A"); val p=ApiProfile(model="test",contextSize=16000)
        val r=ChatRoom(memberIds=listOf(a.id),profileId=p.id,longSummary="已有事实")
        var commits=0
        assertFails {
            ConversationRunner { _,_,_,delta -> delta("不是JSON") }.run(Snapshot(characters=listOf(a),profiles=listOf(p),rooms=listOf(r),messages=List(12) { Message(roomId=r.id,sequence=it,text="剧情") }),r.id,"",null,{ commits++ },{}, {})
        }
        assertEquals(0,commits)
    }
    @Test fun secondCharacterSeesFirstReply() = runBlocking {
        val a=Character(name="A"); val b=Character(name="B"); val p=ApiProfile(model="test")
        val r=ChatRoom(memberIds=listOf(a.id,b.id),profileId=p.id,replies=2)
        val requests=mutableListOf<List<WireMessage>>()
        val saved=mutableListOf<Message>()
        ConversationRunner { _,_,messages,delta -> requests+=messages; delta("回复${requests.size}") }
            .run(Snapshot(characters=listOf(a,b),profiles=listOf(p),rooms=listOf(r)),r.id,"",null,{}, { saved+=it }, {})
        assertEquals(2, requests.size)
        assertTrue(requests[1].any { it.content.contains("回复1") })
        assertEquals(2,saved.count { it.status=="complete" })
    }
    @Test fun failedFirstReplyPreventsSecondAndPreservesPartial() = runBlocking {
        val a=Character(name="A"); val b=Character(name="B"); val p=ApiProfile(model="test")
        val r=ChatRoom(memberIds=listOf(a.id,b.id),profileId=p.id,replies=2)
        var calls=0; val saved=mutableListOf<Message>()
        assertFailsWith<ApiException> {
            ConversationRunner { _,_,_,delta -> calls++; delta("部分"); throw ApiException("断流") }
                .run(Snapshot(characters=listOf(a,b),profiles=listOf(p),rooms=listOf(r)),r.id,"",null,{}, { saved+=it }, {})
        }
        assertEquals(1,calls)
        assertEquals("部分",saved.last().text)
        assertEquals("failed",saved.last().status)
    }
    @Test fun failedSummaryDoesNotAdvanceCursor() = runBlocking {
        val a=Character(name="A"); val p=ApiProfile(model="test",contextSize=3500,maxOutput=500)
        val r=ChatRoom(memberIds=listOf(a.id),profileId=p.id)
        val history=List(12) { Message(roomId=r.id,sequence=it,text="字".repeat(100)) }
        val updated=mutableListOf<ChatRoom>()
        assertFailsWith<ApiException> {
            ConversationRunner { _,_,_,_ -> throw ApiException("摘要失败") }
                .run(Snapshot(characters=listOf(a),profiles=listOf(p),rooms=listOf(r),messages=history),r.id,"",null,{ updated+=it },{}, {})
        }
        assertTrue(updated.isEmpty())
    }
}
