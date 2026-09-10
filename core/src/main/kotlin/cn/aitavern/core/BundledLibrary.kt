package cn.aitavern.core

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable data class BundleDocument(val title: String,val path: String)
@Serializable data class BundleManifest(
    val id: String,val worldName: String,val worldDescription: String = "",val playerName: String = "我",
    val playerDescription: String = "",val cards: List<String> = emptyList(),val books: List<String> = emptyList(),
    val documents: List<BundleDocument> = emptyList(),val preferredCharacters: List<String> = emptyList()
)
data class BundleImport(val snapshot: Snapshot,val report: String)

object BundledLibrary {
    fun load(manifest: BundleManifest,read: (String)->ByteArray): BundleImport {
        require(manifest.id.isNotBlank() && manifest.worldName.isNotBlank()) { "内置世界清单不完整" }
        fun stable(key: String)=UUID.nameUUIDFromBytes("${manifest.id}/$key".toByteArray(Charsets.UTF_8)).toString()
        fun input(path: String): ByteArray {
            require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path && ':' !in path && path.split('/').none { it==".." || it=="." || it.isBlank() }) { "内置资源路径无效" }
            return read(path).also { require(it.size<=CardCodec.MAX_FILE) { "内置资源过大" } }
        }
        val books=mutableListOf<LoreBook>(); val characters=mutableListOf<Character>(); val warnings=mutableListOf<String>()
        fun stableBook(book: LoreBook,path: String)=book.copy(id=stable(path),entries=book.entries.mapIndexed { i,e -> e.copy(id=stable("$path/$i")) })
        for(path in manifest.books) {
            val (book,notes)=CardCodec.importBook(input(path))
            books+=stableBook(book.copy(name=manifest.worldName+" · 世界书"),path)
            warnings+=notes.map { "$path：$it" }
        }
        val globalBooks=books.map { it.id }
        for(path in manifest.cards) {
            val card=CardCodec.importCard(input(path))
            warnings+=card.warnings.map { "$path：$it" }
            if(card.character.name==manifest.playerName) { warnings+="玩家人格已排除出 AI 角色列表：$path"; continue }
            val internalBooks=card.books.mapIndexed { i,b -> stableBook(b,"$path/book/$i") }
            books+=internalBooks
            characters+=card.character.copy(id=stable(path),bookIds=internalBooks.map { it.id })
        }
        val ordered=characters.sortedBy { c -> manifest.preferredCharacters.indexOf(c.name).let { if(it<0) Int.MAX_VALUE else it } }
        val documents=manifest.documents.map { WorldDocument(it.title,input(it.path).toString(Charsets.UTF_8).removePrefix("\uFEFF")) }
        val world=World(id=stable("world"),name=manifest.worldName,description=manifest.worldDescription,characterIds=ordered.map { it.id },bookIds=globalBooks,
            personas=listOf(Persona(id=stable("player"),name=manifest.playerName,description=manifest.playerDescription)),documents=documents)
        return BundleImport(Snapshot(worlds=listOf(world),characters=ordered,books=books),"内置世界已准备：${manifest.worldName}\n${ordered.size} 张角色卡，${books.sumOf { it.entries.size }} 条世界书，${documents.size} 份参考资料。\n玩家人格：${manifest.playerName}\n\n"+if(warnings.isEmpty()) "未发现不支持字段。" else "未应用的扩展字段（保留原文件，未执行扩展）：\n"+warnings.distinct().joinToString("\n"))
    }
}
