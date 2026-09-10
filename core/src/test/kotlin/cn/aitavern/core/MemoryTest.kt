package cn.aitavern.core

import org.junit.Test
import kotlin.test.*

class MemoryTest {
    @Test fun backupRejectsMemoryWithWrongWorldEvenIfBothIdsExist() {
        val c=Character(name="雪"); val p=ApiProfile()
        val w1=World(characterIds=listOf(c.id)); val w2=World(characterIds=listOf(c.id))
        val room=ChatRoom(worldId=w1.id,memberIds=listOf(c.id),profileId=p.id)
        val fact=MemoryFact(worldId=w2.id,roomId=room.id,scope="world",subject="秘密",content="不应跨世界")
        assertFails { BackupCodec.decode(BackupCodec.encode(Snapshot(characters=listOf(c),profiles=listOf(p),worlds=listOf(w1,w2),rooms=listOf(room),memories=listOf(fact)))) }
    }
    @Test fun memoryRetrievalNeverCrossesWorldBoundary() {
        val memories=listOf(MemoryFact(worldId="w1",roomId="r",subject="雪",content="钥匙在雪手中"),MemoryFact(worldId="w2",roomId="else",subject="雪",content="钥匙在门口",scope="world"))
        assertEquals(1,MemoryEngine.retrieve(memories,"w1","r","钥匙",null,"").size)
    }
    @Test fun vectorRanksRelevantMemoryAndIgnoresOtherModels() {
        val a=MemoryFact(worldId="w",roomId="r",subject="A",content="A",embedding=listOf(1f,0f),embeddingModel="m")
        val b=MemoryFact(worldId="w",roomId="r",subject="B",content="B",embedding=listOf(0f,1f),embeddingModel="m")
        assertEquals(a.id,MemoryEngine.retrieve(listOf(b,a),"w","r","",listOf(1f,0f),"m").first().id)
        assertTrue(MemoryEngine.retrieve(listOf(a,b),"w","r","无关",listOf(1f,0f),"other").isEmpty())
    }
    @Test fun lockedFactsCannotBeOverwrittenByExtraction() {
        val old=MemoryFact(worldId="w",roomId="r",category="人物",subject="雪",content="信任我",locked=true)
        val extracted=listOf(old.copy(id=newId(),content="不认识我",locked=false))
        assertEquals("信任我",MemoryEngine.merge(listOf(old),extracted).single().content)
    }
    @Test fun changedFactClearsStaleVectorAndKeepsIdentity() {
        val old=MemoryFact(worldId="w",roomId="r",category="人物",subject="雪",content="陌生",embedding=listOf(1f),embeddingModel="m")
        val value=MemoryEngine.merge(listOf(old),listOf(old.copy(id=newId(),content="朋友"))).single()
        assertEquals(old.id,value.id); assertTrue(value.embedding.isEmpty())
    }
    @Test fun invalidStructuredExtractionFailsAtomically() {
        assertFails { MemoryEngine.parseExtraction("not json","w","r",2,5) }
    }
    @Test fun extractionGetsTrustedSourceRange() {
        val facts=MemoryEngine.parseExtraction("""{"facts":[{"category":"物品","subject":"钥匙","content":"在雪手中"}]}""","w","r",2,5)
        assertEquals(2,facts.single().sourceStart); assertEquals(5,facts.single().sourceEnd)
    }
    @Test fun backupRemapsWorldPersonaAndMemory() {
        val c=Character(name="雪"); val p=ApiProfile(); val w=World(name="雪城",characterIds=listOf(c.id),personas=listOf(Persona(name="旅人")))
        val r=ChatRoom(worldId=w.id,profileId=p.id,memberIds=listOf(c.id))
        val f=MemoryFact(worldId=w.id,roomId=r.id,subject="钥匙",content="找到了")
        val restored=BackupCodec.decode(BackupCodec.encode(Snapshot(characters=listOf(c),profiles=listOf(p),worlds=listOf(w),rooms=listOf(r),memories=listOf(f))))
        assertEquals(restored.worlds.single().id,restored.rooms.single().worldId)
        assertEquals(restored.worlds.single().id,restored.memories.single().worldId)
        assertEquals(restored.rooms.single().id,restored.memories.single().roomId)
    }
}
