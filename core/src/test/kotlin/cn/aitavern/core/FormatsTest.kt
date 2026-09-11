package cn.aitavern.core

import kotlin.test.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.zip.CRC32

class FormatsTest {
    @Test fun standaloneWorldArrayImportsWithSmallerInsertionOrderFirst() {
        val (book,_) = CardCodec.importBook("""[{"keys":["key"],"content":"first","insertion_order":10},{"keys":["key"],"content":"last","insertion_order":100}]""".toByteArray())
        assertEquals(listOf("first","last"),Engine.activeLore(book.entries,listOf(Message(text="key"))).map { it.content })
    }
    private val card = """{"spec":"chara_card_v2","data":{"name":"雪","description":"旅行者","alternate_greetings":["你好"],"character_book":{"entries":[{"keys":["城"],"content":"雪城"}]},"extensions":{"regex_scripts":[]}}}"""
    private val cardV3 = """{"spec":"chara_card_v3","data":{"name":"夏","description":"明确成年角色","first_mes":"你好","assets":[]}}"""
    @Test fun importsV2WithLoreAndReportsExtensions() {
        val result = CardCodec.importCard(card.toByteArray())
        assertEquals("雪", result.character.name)
        assertEquals("你好", result.character.alternateGreetings.single())
        assertEquals("雪城", result.books.single().entries.single().content)
        assertTrue(result.warnings.any { it.contains("extensions") })
    }
    @Test fun importsV1() { assertEquals("旧卡", CardCodec.importCard("""{"name":"旧卡","first_mes":"你好"}""".toByteArray()).character.name) }
    @Test fun importsV3() { assertEquals("夏", CardCodec.importCard(cardV3.toByteArray()).character.name) }
    @Test fun malformedCardRejected() { assertFails { CardCodec.importCard("{}".toByteArray()) } }
    @Test fun unsupportedVersionRejected() { assertFails { CardCodec.importCard("""{"spec":"chara_card_v4","data":{"name":"X"}}""".toByteArray()) } }
    @Test fun readsPngTextChunk() {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
        fun chunk(type: String, payload: ByteArray) {
            val bytes = type.toByteArray() + payload
            data.writeInt(payload.size); data.write(bytes)
            data.writeInt(CRC32().apply { update(bytes) }.value.toInt())
        }
        chunk("tEXt", "chara\u0000${Base64.getEncoder().encodeToString(card.toByteArray())}".toByteArray())
        chunk("IEND", byteArrayOf())
        assertEquals("雪", CardCodec.importCard(out.toByteArray()).character.name)
    }
    @Test fun readsV3PngTextChunk() {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
        val payload="ccv3\u0000${Base64.getEncoder().encodeToString(cardV3.toByteArray())}".toByteArray()
        val bytes="tEXt".toByteArray()+payload
        data.writeInt(payload.size); data.write(bytes)
        data.writeInt(CRC32().apply { update(bytes) }.value.toInt())
        data.writeInt(0); data.write("IEND".toByteArray()); data.writeInt(CRC32().apply { update("IEND".toByteArray()) }.value.toInt())
        assertEquals("夏", CardCodec.importCard(out.toByteArray()).character.name)
    }
    @Test fun importsWorldBookObjectEntries() {
        val (book, warnings) = CardCodec.importBook("""{"entries":{"0":{"key":["城"],"content":"世界","constant":true,"disable":false,"order":42,"selective":true}}}""".toByteArray())
        assertTrue(book.entries.single().constant)
        assertEquals(42, book.entries.single().priority)
        assertTrue(warnings.any { it.contains("selective") })
    }
    @Test fun backupRoundTripRemapsAllReferences() {
        val c = Character(name="雪")
        val p = ApiProfile(name="接口")
        val r = ChatRoom(memberIds=listOf(c.id), profileId=p.id)
        val m = Message(roomId=r.id, speakerId=c.id, text="秘密剧情")
        val source = Snapshot(characters=listOf(c), profiles=listOf(p), rooms=listOf(r), messages=listOf(m))
        val restored = BackupCodec.decode(BackupCodec.encode(source))
        assertNotEquals(c.id, restored.characters.single().id)
        assertEquals(restored.characters.single().id, restored.rooms.single().memberIds.single())
        assertEquals(restored.rooms.single().id, restored.messages.single().roomId)
        assertEquals(restored.characters.single().id, restored.messages.single().speakerId)
        assertEquals("秘密剧情", restored.messages.single().text)
    }
    @Test fun corruptBackupRejected() { assertFails { BackupCodec.decode(byteArrayOf(1,2)) } }
    @Test fun danglingBackupReferenceRejected() {
        assertFails { BackupCodec.decode(BackupCodec.encode(Snapshot(rooms=listOf(ChatRoom(memberIds=listOf("missing")))))) }
    }
}
