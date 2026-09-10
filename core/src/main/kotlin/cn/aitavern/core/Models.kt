package cn.aitavern.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
val TavernJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable data class Character(
    val id: String = newId(), val name: String = "", val description: String = "",
    val personality: String = "", val scenario: String = "", val greeting: String = "",
    val examples: String = "", val systemPrompt: String = "", val postHistory: String = "",
    val alternateGreetings: List<String> = emptyList(), val bookIds: List<String> = emptyList(),
    val avatar: String = ""
)
@Serializable data class LoreEntry(
    val id: String = newId(), val keys: List<String> = emptyList(), val content: String = "",
    val constant: Boolean = false, val enabled: Boolean = true, val priority: Int = 0
)
@Serializable data class LoreBook(val id: String = newId(), val name: String = "世界书", val entries: List<LoreEntry> = emptyList())
@Serializable data class ApiProfile(
    val id: String = newId(), val name: String = "API", val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "", val contextSize: Int = 32768, val maxOutput: Int = 1024,
    val temperature: Double = 0.8, val stream: Boolean = true
    ,val embeddingModel: String = ""
)
@Serializable data class ChatRoom(
    val id: String = newId(), val name: String = "新的故事", val memberIds: List<String> = emptyList(),
    val profileId: String = "", val bookIds: List<String> = emptyList(), val userName: String = "我",
    val persona: String = "", val scenario: String = "", val pinned: String = "",
    val summary: String = "", val summaryThrough: Int = -1, val replies: Int = 1,
    val parentId: String? = null, val createdAt: Long = System.currentTimeMillis()
    ,val worldId: String = "", val longSummary: String = "", val autoMemory: Boolean = true
)
@Serializable data class Message(
    val id: String = newId(), val roomId: String = "", val speakerId: String? = null,
    val text: String = "", val sequence: Int = 0, val status: String = "complete",
    val createdAt: Long = System.currentTimeMillis()
)
@Serializable data class AppSettings(val theme: String = "system")
@Serializable data class Snapshot(
    val version: Int = 1, val characters: List<Character> = emptyList(), val books: List<LoreBook> = emptyList(),
    val profiles: List<ApiProfile> = emptyList(), val rooms: List<ChatRoom> = emptyList(),
    val messages: List<Message> = emptyList(), val settings: AppSettings = AppSettings()
    ,val worlds: List<World> = emptyList(), val memories: List<MemoryFact> = emptyList(),
    val segments: List<SummarySegment> = emptyList()
)
@Serializable data class WireMessage(val role: String, val content: String)

@Serializable data class Persona(val id: String = newId(),val name: String = "我",val description: String = "")
@Serializable data class World(val id: String = newId(),val name: String = "新的世界",val description: String = "",val characterIds: List<String> = emptyList(),val bookIds: List<String> = emptyList(),val personas: List<Persona> = emptyList(),val documents: List<WorldDocument> = emptyList())
@Serializable data class WorldDocument(val title: String,val body: String)
@Serializable data class MemoryFact(
    val id: String = newId(),val worldId: String = "",val roomId: String = "",val scope: String = "room",
    val category: String = "事实",val subject: String = "",val content: String = "",val locked: Boolean = false,
    val enabled: Boolean = true,val sourceStart: Int = -1,val sourceEnd: Int = -1,
    val embedding: List<Float> = emptyList(),val embeddingModel: String = "",val updatedAt: Long = System.currentTimeMillis()
)
@Serializable data class SummarySegment(val id: String = newId(),val worldId: String = "",val roomId: String = "",val start: Int = 0,val end: Int = 0,val text: String = "")
