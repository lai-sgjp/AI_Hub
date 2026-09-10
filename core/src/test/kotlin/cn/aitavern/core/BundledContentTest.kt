package cn.aitavern.core

import org.junit.Test
import kotlin.test.*

class BundledContentTest {
    @Test fun bundlesPlayerPersonaCardsLoreAndDocumentsWithStableIds() {
        val manifest=BundleManifest(id="fixture-pack",worldName="示例世界",playerName="玩家",playerDescription="由用户扮演",cards=listOf("cards/a.json"),books=listOf("world/book.json"),documents=listOf(BundleDocument("参考资料","world/readme.md")))
        val files=mapOf("cards/a.json" to """{"name":"向导","description":"帮助玩家"}""","world/book.json" to """[{"keys":["钥匙"],"content":"银色的钥匙"}]""","world/readme.md" to "示例参考资料")
        val first=BundledLibrary.load(manifest) { files.getValue(it).toByteArray() }.snapshot
        val second=BundledLibrary.load(manifest) { files.getValue(it).toByteArray() }.snapshot
        assertEquals(first.worlds.single().id,second.worlds.single().id)
        assertEquals(first.characters.single().id,second.characters.single().id)
        assertEquals("玩家",first.worlds.single().personas.single().name)
        assertEquals("示例参考资料",first.worlds.single().documents.single().body)
        assertEquals(first.books.single().id,first.worlds.single().bookIds.single())
    }
    @Test fun playerIsNotAddedAsAiCharacter() {
        val manifest=BundleManifest(id="pack",worldName="世界",playerName="玩家",cards=listOf("a.json","b.json"))
        val result=BundledLibrary.load(manifest) { if(it=="a.json") """{"name":"玩家"}""".toByteArray() else """{"name":"向导"}""".toByteArray() }
        assertEquals(listOf("向导"),result.snapshot.characters.map { it.name })
    }
    @Test fun rejectsParentTraversalBeforeReadingAssets() {
        var read=false
        assertFails { BundledLibrary.load(BundleManifest(id="x",worldName="w",cards=listOf("../private.json"))) { read=true; byteArrayOf() } }
        assertFalse(read)
    }
}
