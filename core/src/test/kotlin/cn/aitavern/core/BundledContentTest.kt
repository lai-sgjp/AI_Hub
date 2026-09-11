package cn.aitavern.core

import org.junit.Test
import kotlin.test.*

class BundledContentTest {
    @Test fun bundlesCharacterGalleryWithStableAssetReferences() {
        val manifest=BundleManifest(
            id="gallery-pack",
            worldName="画廊世界",
            cards=listOf("characters/a.json"),
            galleries=mapOf("characters/a.json" to listOf(BundleImage("正式立绘","characters/a/gallery/portrait.png","image/png")))
        )
        val files=mapOf(
            "characters/a.json" to "{\"name\":\"向导\"}".toByteArray(),
            "characters/a/gallery/portrait.png" to byteArrayOf(1,2,3)
        )
        val first=BundledLibrary.load(manifest) { files.getValue(it) }.snapshot
        val second=BundledLibrary.load(manifest) { files.getValue(it) }.snapshot
        val image=first.characters.single().gallery.single()
        assertEquals("正式立绘",image.title)
        assertEquals("characters/a/gallery/portrait.png",image.assetPath)
        assertEquals("image/png",image.mimeType)
        assertEquals(image.id,second.characters.single().gallery.single().id)
    }

    @Test fun rejectsGalleryPathTraversalBeforeReadingExternalAsset() {
        var galleryRead=false
        val manifest=BundleManifest(
            id="gallery-path",
            worldName="路径校验",
            cards=listOf("characters/a.json"),
            galleries=mapOf("characters/a.json" to listOf(BundleImage("坏图","../private.png","image/png")))
        )
        assertFails {
            BundledLibrary.load(manifest) {
                if(it=="../private.png") galleryRead=true
                if(it=="characters/a.json") "{\"name\":\"向导\"}".toByteArray() else byteArrayOf()
            }
        }
        assertFalse(galleryRead)
    }

    @Test fun oldBundleManifestKeepsEmptyGalleryByDefault() {
        val manifest=TavernJson.decodeFromString<BundleManifest>("{\"id\":\"old\",\"worldName\":\"旧世界\"}")
        assertTrue(manifest.galleries.isEmpty())
    }

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
