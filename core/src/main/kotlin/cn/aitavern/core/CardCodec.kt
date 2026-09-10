package cn.aitavern.core

import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.Base64
import java.util.zip.CRC32

data class CardImport(val character: Character, val books: List<LoreBook>, val warnings: List<String>)

object CardCodec {
    const val MAX_FILE = 20 * 1024 * 1024
    private val signature = byteArrayOf(137.toByte(),80,78,71,13,10,26,10)
    private fun JsonObject.text(key: String, default: String = "") = this[key]?.jsonPrimitive?.contentOrNull ?: default
    private fun JsonObject.flag(key: String, default: Boolean = false) = this[key]?.jsonPrimitive?.booleanOrNull ?: default
    private fun JsonObject.number(key: String, default: Int = 0) = this[key]?.jsonPrimitive?.intOrNull ?: default
    private fun JsonObject.strings(key: String): List<String> = this[key]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
    private fun parse(bytes: ByteArray): JsonObject = TavernJson.parseToJsonElement(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")).jsonObject
    fun importCard(bytes: ByteArray): CardImport {
        require(bytes.size <= MAX_FILE) { "角色卡超过 20 MB" }
        val png = bytes.take(8).toByteArray().contentEquals(signature)
        val root = parse(if(png) pngCard(bytes) else bytes)
        val spec = root.text("spec")
        require(spec.isEmpty() || spec == "chara_card_v2") { "暂不支持此角色卡版本：$spec" }
        val data = if(spec.isEmpty()) root else root["data"]!!.jsonObject
        val name = data.text("name").trim()
        require(name.isNotEmpty()) { "角色卡缺少名字" }
        val warnings = mutableListOf<String>()
        val supported = setOf("name","description","personality","scenario","first_mes","mes_example","system_prompt","post_history_instructions","alternate_greetings","character_book")
        data.keys.filter { it !in supported }.forEach { warnings += "角色字段未应用：$it" }
        val books = data["character_book"]?.takeUnless { it is JsonNull }?.let {
            val (book, notes) = book(it.jsonObject, "$name · 世界书"); warnings += notes; listOf(book)
        } ?: emptyList()
        return CardImport(Character(name=name,description=data.text("description"),personality=data.text("personality"),scenario=data.text("scenario"),greeting=data.text("first_mes"),examples=data.text("mes_example"),systemPrompt=data.text("system_prompt"),postHistory=data.text("post_history_instructions"),alternateGreetings=data.strings("alternate_greetings"),bookIds=books.map { it.id },avatar=if(png) Base64.getEncoder().encodeToString(bytes) else ""), books, warnings)
    }
    private fun pngCard(bytes: ByteArray): ByteArray {
        val input = DataInputStream(ByteArrayInputStream(bytes,8,bytes.size-8))
        var payload: ByteArray? = null
        var ended = false
        while(input.available() > 0) {
            require(input.available() >= 12) { "PNG 数据不完整" }
            val length = input.readInt()
            require(length >= 0 && length <= input.available()-8) { "PNG 块长度错误" }
            val typeBytes = ByteArray(4).also(input::readFully)
            val data = ByteArray(length).also(input::readFully)
            val crc = input.readInt().toLong() and 0xffffffffL
            require(CRC32().apply { update(typeBytes); update(data) }.value == crc) { "PNG 校验失败" }
            val type = typeBytes.toString(Charsets.US_ASCII)
            if(type == "tEXt") {
                val split = data.indexOf(0)
                if(split >= 0 && data.copyOfRange(0,split).toString(Charsets.US_ASCII) == "chara") {
                    payload = Base64.getDecoder().decode(data.copyOfRange(split+1,data.size))
                }
            }
            if(type == "IEND") { ended=true; break }
        }
        require(ended) { "PNG 缺少结束块" }
        return requireNotNull(payload) { "PNG 不含 V1/V2 chara 角色数据" }
    }
    fun importBook(bytes: ByteArray): Pair<LoreBook,List<String>> {
        require(bytes.size <= MAX_FILE) { "世界书超过 20 MB" }
        val parsed=TavernJson.parseToJsonElement(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF"))
        val root=if(parsed is JsonArray) buildJsonObject { put("entries",parsed) } else parsed.jsonObject
        return book(root, "导入的世界书")
    }
    private fun book(root: JsonObject, defaultName: String): Pair<LoreBook,List<String>> {
        val warnings = mutableListOf<String>()
        val raw = when(val entries=root["entries"]) {
            is JsonArray -> entries.toList()
            is JsonObject -> entries.values.toList()
            else -> throw IllegalArgumentException("世界书缺少 entries")
        }
        require(raw.size <= 10000) { "世界书条目过多" }
        root.keys.filter { it !in setOf("name","entries") }.forEach { warnings += "世界书字段未应用：$it" }
        val supported = setOf("id","uid","key","keys","content","constant","disable","enabled","order","insertion_order","priority","comment","name")
        val entries = raw.mapIndexed { index, item ->
            val entry = item.jsonObject
            entry.keys.filter { it !in supported }.forEach { warnings += "条目 ${index+1} 未应用：$it" }
            LoreEntry(keys=if(entry.containsKey("keys")) entry.strings("keys") else entry.strings("key"),content=entry.text("content"),constant=entry.flag("constant"),enabled=entry.flag("enabled",true) && !entry.flag("disable"),priority=entry.number("priority",entry.number("order",-entry.number("insertion_order"))))
        }
        return LoreBook(name=root.text("name",defaultName), entries=entries) to warnings.distinct()
    }
}
