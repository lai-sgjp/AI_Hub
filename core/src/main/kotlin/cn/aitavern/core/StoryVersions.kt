package cn.aitavern.core

object StoryVersions {
    fun capture(s: Snapshot, room: ChatRoom): StoryVersion {
        val previous=s.versions.find { it.id==room.activeVersionId }
        return (previous ?: StoryVersion(id=room.activeVersionId.ifBlank { newId() },roomId=room.id)).copy(
            messages=s.messages.filter { it.roomId==room.id },
            memories=s.memories.filter { it.roomId==room.id && it.scope=="room" },
            segments=s.segments.filter { it.roomId==room.id },
            summary=room.summary,longSummary=room.longSummary,summaryThrough=room.summaryThrough
        )
    }
    fun candidate(s: Snapshot, room: ChatRoom, sequence: Int): StoryVersion {
        require(s.messages.any { it.roomId==room.id && it.sequence==sequence && it.speakerId!=null })
        return StoryVersion(roomId=room.id,forkSequence=sequence,parentVersionId=room.activeVersionId.takeIf { it.isNotBlank() },status="generating",
            messages=s.messages.filter { it.roomId==room.id && it.sequence<sequence }.map { it.copy(id=newId()) },
            memories=s.memories.filter { it.roomId==room.id && it.scope=="room" && it.sourceEnd in 0 until sequence }.map { it.copy(id=newId()) })
    }
    fun activate(s: Snapshot, version: StoryVersion): Snapshot {
        require(version.status=="complete")
        val room=requireNotNull(s.rooms.find { it.id==version.roomId })
        val old=capture(s,room)
        return s.copy(rooms=s.rooms.map { if(it.id==room.id) it.copy(activeVersionId=version.id,summary=version.summary,longSummary=version.longSummary,summaryThrough=version.summaryThrough) else it },
            messages=s.messages.filterNot { it.roomId==room.id }+version.messages,
            memories=s.memories.filterNot { it.roomId==room.id && it.scope=="room" }+version.memories.filterNot { fact -> s.memories.any { it.id==fact.id && it.scope=="world" } },
            segments=s.segments.filterNot { it.roomId==room.id }+version.segments,
            versions=s.versions.filterNot { it.id==old.id || it.id==version.id }+old+version)
    }
    fun migrate(s: Snapshot): Snapshot {
        var result=s
        for(room in s.rooms.filter { it.activeVersionId.isBlank() }) {
            val v=capture(result,room)
            result=result.copy(rooms=result.rooms.map { if(it.id==room.id) it.copy(activeVersionId=v.id) else it },versions=result.versions+v)
        }
        return result
    }
}

object ReplyText {
    fun clean(text: String, name: String, streaming: Boolean=false): String {
        if(name.isBlank()) return text
        val n=Regex.escape(name)
        val label=Regex("""^\s*(?:\*\*|__)?(?:[\[【]$n[\]】]|$n(?=\s*(?:(?:\*\*|__)\s*)?[:：]))(?:\*\*|__)?\s*[:：]?(?:\*\*|__)?\s*""")
        var body=text
        while(true) { val match=label.find(body) ?: break; body=body.substring(match.value.length) }
        if(streaming) {
            val prefix=body.trimStart()
            val candidates=listOf("$name:","$name：","[$name]","【$name】").flatMap { listOf(it,"**$it","__$it") }+listOf("**$name**:","**$name**：","__${name}__:","__${name}__：")
            if(prefix.isNotEmpty() && candidates.any { it.startsWith(prefix) }) return ""
        }
        return body
    }
}

object StoryContent {
    fun language(code: String)=when(code) { "en"->"English"; "ja"->"日本語"; else->"简体中文" }
    fun fingerprint(text: String)=java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    fun entries(world: World, characters: List<Character>): List<StoryEntry> = characters.filter { it.id in world.characterIds }.flatMap { c ->
        (listOf(c.greeting)+c.alternateGreetings).filter { it.isNotBlank() }.distinct().mapIndexed { i,g ->
            StoryEntry(title="${c.name} · ${i+1}",background=c.scenario,memberIds=listOf(c.id),personaId=world.personas.firstOrNull()?.id.orEmpty(),opening=g)
        }
    }
}
