package cn.aitavern.core

import kotlinx.serialization.json.*
import kotlin.math.sqrt

object MemoryEngine {
    private fun terms(text: String): Set<String> {
        val words=Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase()).map { it.value }.toList()
        return words.flatMap { word -> if(word.any { it.code>127 }) word.windowed(2,1).ifEmpty { listOf(word) } else listOf(word) }.toSet()
    }
    fun cosine(a: List<Float>,b: List<Float>): Double {
        if(a.isEmpty() || a.size!=b.size || a.any { !it.isFinite() } || b.any { !it.isFinite() }) return 0.0
        val denominator=sqrt(a.sumOf { it.toDouble()*it }*b.sumOf { it.toDouble()*it })
        return if(denominator==0.0) 0.0 else a.indices.sumOf { a[it].toDouble()*b[it] }/denominator
    }
    fun retrieve(all: List<MemoryFact>,worldId: String,roomId: String,query: String,vector: List<Float>?,model: String,limit: Int=8): List<MemoryFact> {
        val keywords=terms(query)
        return all.filter { it.enabled && it.worldId==worldId && (it.roomId==roomId || it.scope=="world") }
            .map { fact ->
                val lexical=terms(fact.subject+" "+fact.content).intersect(keywords).size.toDouble()/keywords.size.coerceAtLeast(1)
                val semantic=if(vector!=null && fact.embeddingModel==model) cosine(vector,fact.embedding).coerceAtLeast(0.0) else 0.0
                fact to if(fact.locked) 10.0+lexical else lexical+semantic
            }.filter { it.second>0.05 }.sortedByDescending { it.second }.take(limit).map { it.first }
    }
    fun merge(existing: List<MemoryFact>,incoming: List<MemoryFact>): List<MemoryFact> {
        val result=existing.toMutableList()
        incoming.forEach { fact ->
            val i=result.indexOfFirst { it.worldId==fact.worldId && it.roomId==fact.roomId && it.category==fact.category && it.subject==fact.subject }
            if(i<0) result+=fact.copy(embedding=emptyList(),embeddingModel="")
            else if(!result[i].locked) result[i]=fact.copy(id=result[i].id,enabled=result[i].enabled,scope=result[i].scope,embedding=if(result[i].content==fact.content) result[i].embedding else emptyList(),embeddingModel=if(result[i].content==fact.content) result[i].embeddingModel else "",updatedAt=System.currentTimeMillis())
        }
        return result
    }
    fun parseObject(text: String): JsonObject {
        val cleaned=text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return TavernJson.parseToJsonElement(cleaned).jsonObject
    }
    fun parseExtraction(text: String,world: String,room: String,start: Int,end: Int): List<MemoryFact> {
        val facts=parseObject(text)["facts"]!!.jsonArray
        require(facts.size<=30) { "自动提取的记忆条目过多，原记忆保持不变" }
        return facts.map { raw ->
            val f=raw.jsonObject
            val category=f["category"]!!.jsonPrimitive.content
            val subject=f["subject"]!!.jsonPrimitive.content
            val content=f["content"]!!.jsonPrimitive.content
            require(category.isNotBlank() && subject.isNotBlank() && content.isNotBlank() && content.length<=2000) { "提取的记忆格式无效" }
            MemoryFact(worldId=world,roomId=room,category=category,subject=subject,content=content,sourceStart=start,sourceEnd=end)
        }
    }
}
