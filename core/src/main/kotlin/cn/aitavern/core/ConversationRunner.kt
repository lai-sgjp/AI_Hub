package cn.aitavern.core

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

typealias Completion = suspend (ApiProfile, String, List<WireMessage>, (String)->Unit) -> Unit

class ConversationRunner(private val complete: Completion) {
    suspend fun run(snapshot: Snapshot, roomId: String, key: String, nominated: String?, saveRoom: suspend (ChatRoom)->Unit, saveMessage: suspend (Message)->Unit, live: (Message?)->Unit,
        commitMemory: suspend (ChatRoom,List<MemoryFact>,SummarySegment)->Unit = { r,_,_ -> saveRoom(r) },
        embed: (suspend (ApiProfile,String,List<String>)->List<List<Float>>)? = null,
        warning: (String)->Unit = {}) {
        var room=requireNotNull(snapshot.rooms.find { it.id==roomId }) { "房间不存在" }
        val profile=requireNotNull(snapshot.profiles.find { it.id==room.profileId }) { "请先为房间配置 API" }
        val members=room.memberIds.map { id -> requireNotNull(snapshot.characters.find { it.id==id }) { "角色不存在" } }
        val history=snapshot.messages.filter { it.roomId==roomId }.sortedBy { it.sequence }.toMutableList()
        val available=profile.contextSize-profile.maxOutput
        var memories=snapshot.memories
        val world=snapshot.worlds.find { it.id==room.worldId }
        for(character in Engine.selectSpeakers(members,history,room.replies,nominated)) {
            currentCoroutineContext().ensureActive()
            val bookIds=(room.bookIds+character.bookIds+world?.bookIds.orEmpty()).toSet()
            val entries=snapshot.books.filter { it.id in bookIds }.flatMap { it.entries }
            val query=history.takeLast(3).joinToString("\n") { it.text }
            var queryVector: List<Float>?=null
            val vectorModel=profile.baseUrl.trimEnd('/')+"|"+profile.embeddingModel
            if(embed!=null && profile.embeddingModel.isNotBlank() && memories.any { it.worldId==room.worldId && it.enabled }) {
                try { queryVector=embed(profile,key,listOf(query.ifBlank { room.scenario })).single() }
                catch(e: CancellationException) { throw e }
                catch(e: Exception) { warning("向量检索不可用，本轮使用关键词记忆检索。请检查 embedding 模型和接口。") }
            }
            fun prompt(): List<WireMessage> {
                val retrieved=MemoryEngine.retrieve(memories,room.worldId,room.id,query,queryVector,vectorModel)
                val facts=retrieved.joinToString("\n") { "[${it.category}] ${it.subject}：${it.content}" }
                val base=Engine.prompt(character,room.copy(scenario=listOfNotNull(world?.description,room.scenario).filter { it.isNotBlank() }.joinToString("\n")),members,history,entries).toMutableList()
                if(facts.isNotBlank()) base.add(1,WireMessage("system","相关记忆表格（依据已发生的剧情）：\n$facts"))
                return base
            }
            // Fixed settings are never silently truncated.
            Engine.checkBudget(Engine.prompt(character,room,members,emptyList(),Engine.activeLore(entries,history).map { it.copy(constant=true) }),available)
            if(Engine.estimate(prompt()) > available*0.8 || (room.autoMemory && history.count { it.sequence>room.summaryThrough && it.status=="complete" }>=12)) {
                val pending=history.filter { it.status=="complete" && it.sequence>room.summaryThrough }.dropLast(6).toMutableList()
                while(pending.isNotEmpty()) {
                    val instruction=WireMessage("system","整理角色扮演记忆。只输出 JSON：{\"short_summary\":\"本批剧情摘要，200字内\",\"long_summary\":\"合并已有长期记忆与本批新事实，500字内\",\"facts\":[{\"category\":\"人物/关系/物品/任务/地点/约定\",\"subject\":\"具体对象和属性\",\"content\":\"明确事实\"}]}。不要虚构，不确定的内容不要提取，每次最多 15 条事实。已有长期记忆：\n${room.longSummary}\n此前阶段摘要：${room.summary}")
                    val batch=mutableListOf<Message>()
                    val request=mutableListOf(instruction)
                    for(message in pending) {
                        val name=members.find { it.id==message.speakerId }?.name ?: room.userName
                        val wire=WireMessage("user","[$name] ${message.text}")
                        if(Engine.estimate(request+wire)>available) break
                        batch+=message; request+=wire
                    }
                    require(batch.isNotEmpty()) { "单条历史消息过长，无法在当前预算内摘要；请增大上下文长度。" }
                    val summary=StringBuilder()
                    complete(profile.copy(stream=false),key,request) { summary.append(it) }
                    require(summary.isNotBlank()) { "摘要为空，已保留原记忆，请重试。" }
                    currentCoroutineContext().ensureActive()
                    val structured=MemoryEngine.parseObject(summary.toString())
                    val short=requireNotNull(structured["short_summary"]?.jsonPrimitive?.contentOrNull).also { require(it.isNotBlank()) }
                    val long=requireNotNull(structured["long_summary"]?.jsonPrimitive?.contentOrNull).also { require(it.isNotBlank()) }
                    val extracted=MemoryEngine.parseExtraction(summary.toString(),room.worldId,room.id,batch.first().sequence,batch.last().sequence)
                    memories=MemoryEngine.merge(memories,extracted)
                    if(embed!=null && profile.embeddingModel.isNotBlank()) {
                        try {
                            for(group in memories.filter { it.roomId==room.id && it.enabled && (it.embedding.isEmpty() || it.embeddingModel!=vectorModel) }.chunked(16)) {
                                val vectors=embed(profile,key,group.map { "${it.category} ${it.subject} ${it.content}" })
                                val indexed=group.mapIndexed { i,f -> f.copy(embedding=vectors[i],embeddingModel=vectorModel) }.associateBy { it.id }
                                memories=memories.map { indexed[it.id] ?: it }
                            }
                        } catch(e: CancellationException) { throw e }
                        catch(e: Exception) { warning("新记忆的向量化未完成，已保留表格记忆并使用关键词检索。") }
                    }
                    room=room.copy(summary=short,longSummary=long,summaryThrough=batch.last().sequence)
                    commitMemory(room,memories.filter { it.roomId==room.id },SummarySegment(worldId=room.worldId,roomId=room.id,start=batch.first().sequence,end=batch.last().sequence,text=short))
                    pending.subList(0,batch.size).clear()
                }
            }
            val request=prompt()
            Engine.checkBudget(request,available)
            val initial=Message(roomId=room.id,speakerId=character.id,sequence=(history.maxOfOrNull { it.sequence } ?: -1)+1,status="generating")
            val latest=AtomicReference(initial)
            saveMessage(initial); live(initial)
            coroutineScope {
                val persistence=launch {
                    var previous=""
                    while(isActive) { delay(400); val value=latest.get(); if(value.text!=previous) { saveMessage(value); previous=value.text } }
                }
                var status="complete"
                try {
                    complete(profile,key,request) { delta ->
                        val value=latest.updateAndGet { it.copy(text=it.text+delta) }
                        live(value)
                    }
                    ensureActive()
                } catch(e: CancellationException) { status="interrupted"; throw e }
                catch(e: Exception) { status="failed"; throw e }
                finally {
                    withContext(NonCancellable) {
                        persistence.cancelAndJoin()
                        val final=latest.get().copy(status=status)
                        saveMessage(final)
                        if(status=="complete") history+=final
                        live(null)
                    }
                }
            }
        }
    }
}
