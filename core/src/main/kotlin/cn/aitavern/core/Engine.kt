package cn.aitavern.core

object Engine {
    fun selectSpeakers(members: List<Character>, history: List<Message>, count: Int, nominated: String? = null): List<Character> {
        require(members.size in 1..8) { "房间需要 1～8 位角色" }
        require(count in 1..2) { "每轮只能选择 1～2 位角色" }
        if (nominated != null) return listOf(requireNotNull(members.find { it.id == nominated }) { "点名角色已不在房间中" })
        return members.sortedBy { c -> history.indexOfLast { it.speakerId == c.id && it.status == "complete" } }.take(count)
    }
    fun activeLore(entries: List<LoreEntry>, history: List<Message>): List<LoreEntry> {
        val recent = history.takeLast(6).joinToString("\n") { it.text }
        return entries.filter { it.enabled && (it.constant || it.keys.any { key -> key.isNotBlank() && recent.contains(key, ignoreCase=true) }) }
            .distinctBy { it.id }.sortedByDescending { it.priority }
    }
    fun substitute(text: String, character: String, user: String) = text.replace("{{char}}", character).replace("{{user}}", user)
    fun prompt(character: Character, room: ChatRoom, members: List<Character>, history: List<Message>, entries: List<LoreEntry>, language: String = "zh"): List<WireMessage> {
        val shared = buildString {
            appendLine("这是角色扮演群聊。保持世界设定、角色身份及已经发生的剧情一致。")
            appendLine("参与者：${members.joinToString { it.name }}；用户：${room.userName}")
            if(room.persona.isNotBlank()) appendLine("[用户人设]\n${room.persona}")
            if(room.scenario.isNotBlank()) appendLine("[房间场景]\n${room.scenario}")
        }
        val actor = buildString {
            appendLine("这是角色扮演群聊。你本轮只扮演「${character.name}」，不要替用户或其他角色发言。")
            appendLine("回复语言：${StoryContent.language(language)}。直接输出正文，不要在开头输出姓名、发言标签或重复角色名。")
            fun section(label: String, text: String) { if (text.isNotBlank()) appendLine("[$label]\n$text") }
            section("角色指令", character.systemPrompt)
            section("角色描述", character.description); section("性格", character.personality)
            section("角色场景", character.scenario); section("对话示例", character.examples)
        }
        val memory = buildString {
            fun section(label: String, text: String) { if (text.isNotBlank()) appendLine("[$label]\n$text") }
            section("置顶记忆", room.pinned); section("长期剧情记忆", room.longSummary); section("近期阶段摘要", room.summary)
            section("世界设定", activeLore(entries, history).joinToString("\n") { it.content })
        }
        // Keep every source and its substitutions; only split stable settings from changing memory.
        val output = mutableListOf(shared, actor).mapTo(mutableListOf()) {
            WireMessage("system", substitute(it, character.name, room.userName))
        }
        if(memory.isNotBlank()) output += WireMessage("system", substitute(memory, character.name, room.userName))
        history.filter { it.sequence > room.summaryThrough && it.status == "complete" }.forEach { msg ->
            val name = if (msg.speakerId == null) room.userName else members.find { it.id == msg.speakerId }?.name ?: "离开的角色"
            output += WireMessage(if (msg.speakerId == null) "user" else "assistant", "[$name] ${if(msg.speakerId==null) msg.text else ReplyText.clean(msg.text,name)}")
        }
        output += WireMessage("system", substitute(character.postHistory.ifBlank { "现在由{{char}}回复{{user}}及在场角色。" }, character.name, room.userName))
        return output
    }
    // Byte count deliberately overestimates typical BPE tokenizers. No universal tokenizer exists for custom endpoints.
    fun estimate(messages: List<WireMessage>): Int = messages.sumOf { it.content.toByteArray(Charsets.UTF_8).size + 12 } + 16
    fun checkBudget(messages: List<WireMessage>, available: Int) {
        require(available > 0 && estimate(messages) <= available) { "设定或近期消息超出上下文预算，请增大上下文长度、减少输出上限或精简设定。" }
    }
    fun branch(room: ChatRoom, history: List<Message>, index: Int, replacement: String? = null): Pair<ChatRoom,List<Message>> {
        require(index in history.indices) { "找不到要编辑的消息" }
        val branch = room.copy(id=newId(), name="${replacement?.take(24)?.ifBlank { null } ?: "新的选择"} · ${index+1}", parentId=room.id, activeVersionId="", forkSequence=history[index].sequence, branchChoice=replacement.orEmpty(), branchNamePending=true, summary="", longSummary="", summaryThrough=-1, createdAt=System.currentTimeMillis())
        val copied = history.take(if (replacement == null) index else index+1).mapIndexed { i, m ->
            m.copy(id=newId(), roomId=branch.id, sequence=i, text=if (i == index) replacement!! else m.text)
        }
        return branch to copied
    }
}
