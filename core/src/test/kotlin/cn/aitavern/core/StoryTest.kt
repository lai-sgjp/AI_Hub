package cn.aitavern.core

import kotlin.test.*
import org.junit.Test

class StoryTest {
    @Test fun repeatedLabelsAreRemovedButNarrativeNamesRemain() {
        assertEquals("你好，阿岚。", ReplyText.clean("[阿岚] **阿岚：** 阿岚: 你好，阿岚。", "阿岚"))
        assertEquals("阿岚走进房间。", ReplyText.clean("阿岚走进房间。", "阿岚"))
        assertEquals("", ReplyText.clean("[阿", "阿岚", streaming=true))
        assertEquals("[阿", ReplyText.clean("[阿", "阿岚"))
    }
    @Test fun regenerationKeepsRoomAndOnlyCopiesPastFacts() {
        val room=ChatRoom(id="r")
        val s=Snapshot(rooms=listOf(room), messages=listOf(Message(roomId="r",sequence=0,text="选择"),Message(roomId="r",sequence=1,speakerId="a",text="旧回复"),Message(roomId="r",sequence=2,text="未来")), memories=listOf(MemoryFact(roomId="r",sourceEnd=0),MemoryFact(roomId="r",sourceEnd=2)))
        val candidate=StoryVersions.candidate(s,room,1)
        assertEquals("r",candidate.roomId)
        assertEquals(listOf("选择"),candidate.messages.map { it.text })
        assertEquals(1,candidate.memories.size)
        assertEquals("",candidate.summary)
        assertEquals(3,s.messages.size)
    }
    @Test fun legacyMigrationIsIdempotentAndKeepsHistory() {
        val s=Snapshot(rooms=listOf(ChatRoom(id="r")),messages=listOf(Message(roomId="r",text="past")))
        val migrated=StoryVersions.migrate(s)
        assertEquals(migrated,StoryVersions.migrate(migrated))
        assertEquals("past",migrated.messages.single().text)
        assertEquals(1,migrated.versions.size)
    }
    @Test fun switchingRestoresFutureAndAllMemoryTogether() {
        val r=ChatRoom(id="r",summary="old summary",longSummary="old long",summaryThrough=2)
        val original=StoryVersions.migrate(Snapshot(rooms=listOf(r),messages=listOf(Message(roomId="r",sequence=0,text="choice"),Message(roomId="r",sequence=1,speakerId="a",text="old"),Message(roomId="r",sequence=2,text="future")),memories=listOf(MemoryFact(roomId="r",sourceEnd=2,content="future fact")),segments=listOf(SummarySegment(roomId="r",text="old stage"))))
        val room=original.rooms.single()
        val v=StoryVersions.candidate(original,room,1).let { it.copy(status="complete",messages=it.messages+Message(roomId="r",sequence=1,text="new",speakerId="a")) }
        val active=StoryVersions.activate(original,v)
        assertEquals(1,active.rooms.size)
        assertEquals(listOf("choice","new"),active.messages.map { it.text })
        assertTrue(active.memories.isEmpty()); assertTrue(active.segments.isEmpty())
        val restored=StoryVersions.activate(active,active.versions.first { it.id==room.activeVersionId })
        assertEquals(original.messages,restored.messages)
        assertEquals(original.memories,restored.memories)
        assertEquals(original.segments,restored.segments)
        assertEquals("old summary",restored.rooms.single().summary)
        assertEquals(2,restored.rooms.single().summaryThrough)
        assertFailsWith<IllegalArgumentException> { StoryVersions.activate(active,v.copy(status="failed")) }
    }
    @Test fun archivedFactsCannotOverwriteExplicitWorldSharing() {
        val r=ChatRoom(id="r",activeVersionId="v")
        val shared=MemoryFact(id="f",roomId="r",scope="world",content="shared edited")
        val next=StoryVersion(roomId="r",memories=listOf(shared.copy(scope="room",content="stale")))
        val s=StoryVersions.activate(Snapshot(rooms=listOf(r),memories=listOf(shared)),next)
        assertEquals(listOf(shared),s.memories)
    }
    @Test fun versionsAndEndingsRoundTripBackup() {
        val c=Character(id="a",name="A")
        val w=World(id="w",characterIds=listOf("a"))
        val p=ApiProfile(id="p",model="test")
        val room=ChatRoom(id="r",worldId="w",profileId="p",memberIds=listOf("a"))
        val s=StoryVersions.migrate(Snapshot(characters=listOf(c),worlds=listOf(w),profiles=listOf(p),rooms=listOf(room),messages=listOf(Message(roomId="r",speakerId="a",sequence=0,text="old"))))
        val v=StoryVersions.candidate(s,s.rooms.single(),0).copy(status="complete",messages=listOf(Message(roomId="r",speakerId="a",text="new")))
        val active=StoryVersions.activate(s,v)
        val withEnding=active.copy(endings=listOf(StoryEnding(roomId="r",versionId=v.id,title="End",groupId="g")))
        val restored=BackupCodec.decode(BackupCodec.encode(withEnding))
        BackupCodec.validate(restored)
        assertEquals(2,restored.versions.size)
        assertNotEquals(v.id,restored.rooms.single().activeVersionId)
        assertEquals(restored.rooms.single().activeVersionId,restored.endings.single().versionId)
        assertNotEquals("g",restored.endings.single().groupId)
        assertEquals("old",restored.versions.first { it.id!=restored.rooms.single().activeVersionId }.messages.single().text)
    }
    @Test fun labelsAreBufferedAtEveryChunkBoundary() {
        val input="**阿岚：** [阿岚] 阿岚：你好"
        for(i in 1 until input.indexOf("你")) assertEquals("",ReplyText.clean(input.take(i),"阿岚",true),"prefix $i")
        assertEquals("你好",ReplyText.clean(input,"阿岚",true))
        assertEquals("别人：你好",ReplyText.clean("别人：你好","阿岚"))
        assertEquals("[阿岚走过]",ReplyText.clean("[阿岚走过]","阿岚"))
        assertEquals("你好",ReplyText.clean("**阿岚**： __阿岚__：你好","阿岚"))
    }
    @Test fun sourceOpeningsAndLanguagesAreDeterministic() {
        val c=Character(id="c",name="A",greeting="one",alternateGreetings=listOf("one","two",""))
        val world=World(characterIds=listOf("c"),personas=listOf(Persona(id="p")))
        assertEquals(listOf("one","two"),StoryContent.entries(world,listOf(c)).map { it.opening })
        assertEquals("English",StoryContent.language("en")); assertEquals("日本語",StoryContent.language("ja"))
        assertEquals("简体中文",StoryContent.language("other"))
        assertEquals(StoryContent.fingerprint("same"),StoryContent.fingerprint("same"))
        assertNotEquals(StoryContent.fingerprint("a"),StoryContent.fingerprint("b"))
    }
}
