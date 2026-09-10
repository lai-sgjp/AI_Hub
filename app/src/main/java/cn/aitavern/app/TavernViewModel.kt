package cn.aitavern.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.aitavern.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class TavernApplication: Application() {
    val repository by lazy { Repository(this) }
    val secrets by lazy { SecretStore(this) }
}

class TavernViewModel(application: Application): AndroidViewModel(application) {
    private val app=application as TavernApplication
    private val repository=app.repository
    private val api=OpenAiClient()
    val snapshot=repository.snapshots.stateIn(viewModelScope,SharingStarted.Eagerly,Snapshot())
    val notice=MutableStateFlow<String?>(null)
    val activeRoom=MutableStateFlow<String?>(null)
    val activeWorld=MutableStateFlow<String?>(null)
    val busy=MutableStateFlow(false)
    val live=MutableStateFlow<Message?>(null)
    private var generation: Job?=null
    private val ready=viewModelScope.async {
        try { BundledContent.install(app,repository) }
        catch(e: Exception) { report("内置资源初始化失败：${e.message ?: "请重试"}") }
        repository.recover()
    }

    fun report(text: String) { notice.value=text }
    fun bundleReport() = action { report(repository.bundleReports().joinToString("\n\n").ifBlank { "此构建未包含内置资料，可自行导入。" }) }
    private fun action(block: suspend ()->Unit) = viewModelScope.launch {
        try { ready.await(); block() } catch(e: CancellationException) { throw e }
        catch(e: Exception) { report(e.message ?: "操作失败，请重试") }
    }
    fun save(character: Character) = action {
        require(character.name.isNotBlank()) { "请填写角色名字" }
        val world=repository.snapshot().worlds.find { it.id==activeWorld.value }
        repository.merge(Snapshot(characters=listOf(character),worlds=listOfNotNull(world?.copy(characterIds=(world.characterIds+character.id).distinct()))))
    }
    fun save(book: LoreBook) = action {
        require(book.name.isNotBlank()) { "请填写世界书名字" }
        val world=repository.snapshot().worlds.find { it.id==activeWorld.value }
        repository.merge(Snapshot(books=listOf(book),worlds=listOfNotNull(world?.copy(bookIds=(world.bookIds+book.id).distinct()))))
    }
    fun save(world: World) = action { require(world.name.isNotBlank()) { "请填写世界名字" }; repository.save(world) }
    fun save(fact: MemoryFact) = action {
        require(!busy.value) { "请先停止生成" }
        require(fact.subject.isNotBlank() && fact.content.isNotBlank()) { "请填写对象和事实" }
        repository.save(fact.copy(embedding=emptyList(),embeddingModel="",updatedAt=System.currentTimeMillis()))
    }
    fun save(room: ChatRoom) = action {
        require(!busy.value) { "请先停止当前生成再修改房间" }
        require(room.name.isNotBlank() && room.memberIds.size in 1..8 && room.memberIds.distinct().size==room.memberIds.size && room.profileId.isNotBlank()) { "填写房间名字，选择 API 和 1～8 位角色" }
        repository.save(room)
        activeRoom.value=room.id
    }
    fun save(profile: ApiProfile, secret: String?) = action {
        require(profile.name.isNotBlank() && profile.model.isNotBlank()) { "请填写 API 名字和模型" }
        require(profile.baseUrl.startsWith("https://") || (BuildConfig.DEBUG && profile.baseUrl.startsWith("http://"))) { "请输入有效的 HTTPS API 地址" }
        require(profile.contextSize>profile.maxOutput && profile.maxOutput>0 && profile.temperature in 0.0..2.0) { "检查上下文、输出上限和温度" }
        if(secret!=null) withContext(Dispatchers.IO) { app.secrets.put(profile.id,secret) }
        repository.save(profile)
    }
    fun theme(value: String) = action { repository.save(AppSettings(value)) }
    fun models(profile: ApiProfile, secret: String?, done: (List<String>)->Unit) = action {
        val key=withContext(Dispatchers.IO) { secret ?: app.secrets.get(profile.id) }
        done(api.models(profile,key)); report("模型列表已获取")
    }
    fun test(profile: ApiProfile, secret: String?) = action {
        val key=withContext(Dispatchers.IO) { secret ?: app.secrets.get(profile.id) }
        api.complete(profile.copy(maxOutput=32),key,listOf(WireMessage("user","只回复 OK"))) {}
        report("连接成功，已收到模型回复。")
    }
    fun stop() { generation?.cancel() }
    fun send(text: String, nominated: String? = null) {
        val id=activeRoom.value ?: return
        if(busy.value) return
        busy.value=true
        generation=viewModelScope.launch {
            try {
                ready.await()
                var current=repository.snapshot()
                val room=requireNotNull(current.rooms.find { it.id==id })
                if(text.isNotBlank()) {
                    repository.save(Message(roomId=id,text=text.trim(),sequence=(current.messages.filter { it.roomId==id }.maxOfOrNull { it.sequence } ?: -1)+1))
                    current=repository.snapshot()
                }
                val key=withContext(Dispatchers.IO) { app.secrets.get(room.profileId) }
                ConversationRunner(api::complete).run(current,id,key,nominated,{ repository.save(it) },{ repository.save(it) },{ live.value=it },
                    commitMemory={ r,facts,segment -> repository.merge(Snapshot(rooms=listOf(r),memories=facts,segments=listOf(segment))) },embed=api::embeddings,warning=::report)
            } catch(e: CancellationException) { /* The runner persists interrupted output. */ }
            catch(e: Exception) { report(e.message ?: "生成失败，请重试") }
            finally { busy.value=false; live.value=null }
        }
    }
    fun branch(message: Message, replacement: String?) = action {
        require(!busy.value) { "请先停止当前生成" }
        val s=repository.snapshot()
        val room=requireNotNull(s.rooms.find { it.id==message.roomId })
        val history=s.messages.filter { it.roomId==room.id }.sortedBy { it.sequence }
        val (newRoom,copied)=Engine.branch(room,history,history.indexOfFirst { it.id==message.id },replacement)
        val cutoff=(copied.maxOfOrNull { it.sequence } ?: -1)-(if(replacement!=null) 1 else 0)
        val copiedFacts=s.memories.filter { it.roomId==room.id && it.scope=="room" && it.sourceEnd<=cutoff && it.sourceEnd>=0 }.map { it.copy(id=newId(),roomId=newRoom.id) }
        repository.merge(Snapshot(rooms=listOf(newRoom),messages=copied,memories=copiedFacts))
        activeRoom.value=newRoom.id
        if(replacement==null || message.speakerId==null) send("",if(replacement==null) message.speakerId else null)
        else report("已建立分支，原剧情保留。可继续聊天。")
    }
    fun greeting(room: ChatRoom, character: Character, text: String) = action {
        require(!busy.value) { "请先停止生成" }
        val s=repository.snapshot()
        require(s.messages.none { it.roomId==room.id }) { "开场白仅能用于空房间" }
        repository.save(Message(roomId=room.id,speakerId=character.id,text=Engine.substitute(text,character.name,room.userName),sequence=0))
    }
    private suspend fun read(uri: Uri, limit: Int): ByteArray = withContext(Dispatchers.IO) {
        app.contentResolver.openInputStream(uri)!!.use { input ->
            val output=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(true) { val count=input.read(buffer); if(count<0) break; require(output.size()+count<=limit) { "文件超过大小上限" }; output.write(buffer,0,count) }
            output.toByteArray()
        }
    }
    fun import(uri: Uri, book: Boolean) = action {
        val bytes=read(uri,CardCodec.MAX_FILE)
        val warnings=withContext(Dispatchers.Default) {
            if(book) {
                val (value,notes)=CardCodec.importBook(bytes)
                val world=repository.snapshot().worlds.find { it.id==activeWorld.value }
                repository.merge(Snapshot(books=listOf(value),worlds=listOfNotNull(world?.copy(bookIds=(world.bookIds+value.id).distinct())))); notes
            } else {
                val result=CardCodec.importCard(bytes)
                val world=repository.snapshot().worlds.find { it.id==activeWorld.value }
                repository.merge(Snapshot(characters=listOf(result.character),books=result.books,worlds=listOfNotNull(world?.copy(characterIds=(world.characterIds+result.character.id).distinct())))); result.warnings
            }
        }
        report(if(warnings.isEmpty()) "导入成功，所有支持字段已应用。" else "导入成功。以下配置未应用：\n"+warnings.joinToString("\n"))
    }
    fun backup(uri: Uri) = action {
        val current=repository.snapshot()
        withContext(Dispatchers.IO) { app.contentResolver.openOutputStream(uri,"wt")!!.use { it.write(BackupCodec.encode(current)) } }
        report("备份已导出，不包含 API 密钥。")
    }
    fun restore(uri: Uri) = action {
        require(!busy.value) { "请先停止生成再恢复备份" }
        val bytes=read(uri,BackupCodec.MAX_BACKUP)
        val restored=withContext(Dispatchers.Default) { BackupCodec.decode(bytes) }
        repository.merge(restored,true)
        report("已恢复为新增副本，请重新填写恢复的 API 密钥。")
    }
    fun organizeMemory(roomId: String, vectorsOnly: Boolean=false) {
        if(busy.value) return
        busy.value=true
        generation=viewModelScope.launch {
            try {
                ready.await()
                var s=repository.snapshot()
                val room=requireNotNull(s.rooms.find { it.id==roomId })
                val profile=requireNotNull(s.profiles.find { it.id==room.profileId }) { "房间未配置 API" }
                val key=withContext(Dispatchers.IO) { app.secrets.get(profile.id) }
                if(!vectorsOnly) {
                    val history=s.messages.filter { it.roomId==room.id && it.status=="complete" }.sortedBy { it.sequence }.takeLast(12)
                    require(history.isNotEmpty()) { "还没有可整理的对话" }
                    val request=listOf(WireMessage("system","提取明确的剧情事实，不虚构。只输出 JSON：{\"facts\":[{\"category\":\"人物/关系/任务/物品/约定\",\"subject\":\"对象及具体属性\",\"content\":\"事实\"}]}。最多15条。"))+history.map { m -> WireMessage("user","[${s.characters.find { it.id==m.speakerId }?.name ?: room.userName}] ${m.text}") }
                    Engine.checkBudget(request,profile.contextSize-profile.maxOutput)
                    val output=StringBuilder()
                    api.complete(profile.copy(stream=false),key,request) { output.append(it) }
                    val facts=MemoryEngine.parseExtraction(output.toString(),room.worldId,room.id,history.first().sequence,history.last().sequence)
                    val merged=MemoryEngine.merge(s.memories,facts)
                    repository.merge(Snapshot(memories=merged))
                    s=repository.snapshot()
                }
                if(profile.embeddingModel.isNotBlank()) {
                    val facts=s.memories.filter { it.worldId==room.worldId && (it.roomId==room.id || it.scope=="world") && it.enabled }
                    for(batch in facts.chunked(16)) {
                        val vectors=api.embeddings(profile,key,batch.map { "${it.category} ${it.subject} ${it.content}" })
                        repository.merge(Snapshot(memories=batch.mapIndexed { i,f -> f.copy(embedding=vectors[i],embeddingModel=profile.baseUrl.trimEnd('/')+"|"+profile.embeddingModel) }))
                    }
                    report("记忆已整理，向量索引已更新。")
                } else report(if(vectorsOnly) "请在 API 设置填写 embedding 模型。当前使用关键词检索。" else "记忆表格已更新。未配置 embedding 模型，当前使用关键词检索。")
            } catch(e: CancellationException) { }
            catch(e: Exception) { report("记忆处理未完成：${e.message ?: "请重试"}。已保存的记忆仍可使用关键词检索。") }
            finally { busy.value=false }
        }
    }
}
