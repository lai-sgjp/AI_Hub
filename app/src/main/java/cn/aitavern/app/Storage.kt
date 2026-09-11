package cn.aitavern.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.*
import cn.aitavern.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Entity(tableName="records")
data class Record(@PrimaryKey val id: String, val kind: String, val payload: String)
data class RecordHead(val id: String, val kind: String, val payload: String, val size: Int)
@Dao interface RecordDao {
    @Query("DELETE FROM records WHERE id IN (:ids)") suspend fun deleteIds(ids: List<String>)
    @Query("SELECT id FROM records") fun observe(): Flow<List<String>>
    // Keep every cursor row small, including existing cards with large embedded avatars.
    @Query("SELECT id, kind, substr(payload, 1, 262144) AS payload, length(payload) AS size FROM records")
    suspend fun heads(): List<RecordHead>
    @Query("SELECT substr(payload, :offset, 262144) FROM records WHERE id = :id")
    suspend fun chunk(id: String, offset: Int): String
    @Transaction suspend fun all(): List<Record> = heads().map { head ->
        val payload=StringBuilder(head.payload)
        // SQLite offsets count Unicode code points, not Kotlin UTF-16 code units.
        var offset=262145
        while(offset<=head.size) {
            payload.append(chunk(head.id,offset))
            offset+=262144
        }
        Record(head.id,head.kind,payload.toString())
    }
    @Upsert suspend fun put(records: List<Record>)
    @Query("DELETE FROM records WHERE id = :id AND kind = 'profile'") suspend fun deleteProfile(id: String)
    @Query("SELECT EXISTS(SELECT 1 FROM records WHERE id = :id AND kind = 'bundle')") suspend fun hasBundle(id: String): Boolean
    @Query("SELECT payload FROM records WHERE kind = 'bundle'") suspend fun bundleReports(): List<String>
}
@Database(entities=[Record::class],version=1,exportSchema=true)
abstract class TavernDatabase: RoomDatabase() { abstract fun records(): RecordDao }

class Repository(context: Context) {
    private val db = Room.databaseBuilder(context,TavernDatabase::class.java,"tavern.db").build()
    val snapshots = db.records().observe().map { snapshot() }
    private fun decode(rows: List<Record>): Snapshot {
        fun <T> get(kind: String, decode: (String)->T) = rows.filter { it.kind==kind }.map { decode(it.payload) }
        return Snapshot(versions=get("version") { TavernJson.decodeFromString<StoryVersion>(it) },endings=get("ending") { TavernJson.decodeFromString<StoryEnding>(it) },contentCache=get("cache") { TavernJson.decodeFromString<ContentCache>(it) },characters=get("character") { TavernJson.decodeFromString<Character>(it) }, books=get("book") { TavernJson.decodeFromString<LoreBook>(it) }, profiles=get("profile") { TavernJson.decodeFromString<ApiProfile>(it) }, rooms=get("room") { TavernJson.decodeFromString<ChatRoom>(it) }.sortedByDescending { it.createdAt }, messages=get("message") { TavernJson.decodeFromString<Message>(it) }.sortedBy { it.sequence }, settings=get("settings") { TavernJson.decodeFromString<AppSettings>(it) }.firstOrNull() ?: AppSettings(), worlds=get("world") { TavernJson.decodeFromString<World>(it) },memories=get("memory") { TavernJson.decodeFromString<MemoryFact>(it) },segments=get("segment") { TavernJson.decodeFromString<SummarySegment>(it) })
    }
    suspend fun snapshot(): Snapshot = decode(db.records().all())
    suspend fun deleteUnusedProfile(id: String) = db.withTransaction {
        require(snapshot().rooms.none { it.profileId==id }) { "仍有房间使用此 API，请先为这些房间切换 API，再删除。" }
        db.records().deleteProfile(id)
    }
    suspend fun save(value: Character) = db.records().put(listOf(Record(value.id,"character",TavernJson.encodeToString(value))))
    suspend fun save(value: LoreBook) = db.records().put(listOf(Record(value.id,"book",TavernJson.encodeToString(value))))
    suspend fun save(value: ApiProfile) = db.records().put(listOf(Record(value.id,"profile",TavernJson.encodeToString(value))))
    suspend fun save(value: ChatRoom) = db.withTransaction {
        val existing=if(value.activeVersionId.isBlank()) snapshot().rooms.find { it.id==value.id }?.activeVersionId?.takeIf { it.isNotBlank() } else null
        val room=if(value.activeVersionId.isBlank()) value.copy(activeVersionId=existing ?: newId()) else value
        if(value.activeVersionId.isBlank() && existing==null) save(StoryVersion(id=room.activeVersionId,roomId=room.id))
        db.records().put(listOf(Record(room.id,"room",TavernJson.encodeToString(room))))
    }
    suspend fun save(value: Message) = db.records().put(listOf(Record(value.id,"message",TavernJson.encodeToString(value))))
    suspend fun save(value: AppSettings) = db.records().put(listOf(Record("settings","settings",TavernJson.encodeToString(value))))
    suspend fun save(value: World) = db.records().put(listOf(Record(value.id,"world",TavernJson.encodeToString(value))))
    suspend fun save(value: MemoryFact) = db.records().put(listOf(Record(value.id,"memory",TavernJson.encodeToString(value))))
    suspend fun save(value: SummarySegment) = db.records().put(listOf(Record(value.id,"segment",TavernJson.encodeToString(value))))
    suspend fun save(value: StoryVersion) = db.records().put(listOf(Record(value.id,"version",TavernJson.encodeToString(value))))
    suspend fun save(value: StoryEnding) = db.records().put(listOf(Record(value.id,"ending",TavernJson.encodeToString(value))))
    suspend fun save(value: ContentCache) = db.withTransaction {
        val matching=snapshot().contentCache.filter { it.key==value.key }
        val saved=value.copy(id=matching.firstOrNull()?.id ?: value.id)
        if(matching.size>1) db.records().deleteIds(matching.drop(1).map { it.id })
        db.records().put(listOf(Record(saved.id,"cache",TavernJson.encodeToString(saved))))
    }
    suspend fun removeCache(key: String) = db.withTransaction {
        val ids=snapshot().contentCache.filter { it.key==key }.map { it.id }
        if(ids.isNotEmpty()) db.records().deleteIds(ids)
    }
    suspend fun updateSettings(change: (AppSettings)->AppSettings) = db.withTransaction { save(change(snapshot().settings)) }
    suspend fun activate(version: StoryVersion) = db.withTransaction {
        val old=snapshot()
        val next=StoryVersions.activate(old,version)
        val removed=old.messages.filter { it.roomId==version.roomId }.map { it.id } + old.memories.filter { it.roomId==version.roomId && it.scope=="room" }.map { it.id } + old.segments.filter { it.roomId==version.roomId }.map { it.id }
        if(removed.isNotEmpty()) db.records().deleteIds(removed)
        merge(next)
    }
    suspend fun merge(s: Snapshot, settings: Boolean = false) = db.withTransaction {
        s.characters.forEach { save(it) }; s.books.forEach { save(it) }; s.profiles.forEach { save(it) }; s.rooms.forEach { save(it) }; s.messages.forEach { save(it) }
        s.worlds.forEach { save(it) }; s.memories.forEach { save(it) }; s.segments.forEach { save(it) }
        s.versions.forEach { save(it) }; s.endings.forEach { save(it) }; s.contentCache.forEach { save(it) }
        if(settings) save(s.settings)
    }
    suspend fun recover() = db.withTransaction {
        val original=snapshot()
        val migrated=StoryVersions.migrate(original)
        migrated.rooms.filter { it !in original.rooms }.forEach { save(it) }
        migrated.versions.filter { it !in original.versions }.forEach { save(it) }
        original.messages.filter { it.status=="generating" }.forEach { save(it.copy(status="interrupted")) }
        original.versions.filter { it.status=="generating" }.forEach { v -> save(v.copy(status="interrupted",messages=v.messages.map { if(it.status=="generating") it.copy(status="interrupted") else it })) }
        if(original.settings.language.isBlank()) save(original.settings.copy(language=java.util.Locale.getDefault().language.takeIf { it in listOf("en","ja") } ?: "zh"))
    }
    suspend fun hasBundle(id: String)=db.records().hasBundle("bundle:$id")
    suspend fun bundleReports()=db.records().bundleReports()
    suspend fun installBundle(id: String,content: BundleImport)=db.withTransaction {
        if(!hasBundle(id)) {
            merge(content.snapshot)
            db.records().put(listOf(Record("bundle:$id","bundle",content.report)))
        }
    }
}

class SecretStore(context: Context) {
    private val preferences = context.getSharedPreferences("api-secrets",Context.MODE_PRIVATE)
    @Suppress("ApplySharedPref")
    @android.annotation.SuppressLint("UseKtx")
    fun remove(id: String) { check(preferences.edit().remove(id).commit()) { "密钥删除失败" } }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("tavern-api",null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("tavern-api",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Suppress("ApplySharedPref") // Must know whether the encrypted key reached storage before saving the profile.
    @android.annotation.SuppressLint("UseKtx")
    fun put(id: String, secret: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(secret.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)
        check(preferences.edit().putString(id,encoded).commit()) { "密钥保存失败" }
    }
    fun get(id: String): String {
        val encoded=preferences.getString(id,null) ?: return ""
        return try {
            val bytes=Base64.decode(encoded,Base64.NO_WRAP)
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12))) }.doFinal(bytes.copyOfRange(12,bytes.size)).toString(Charsets.UTF_8)
        } catch(e: Exception) { throw IllegalStateException("无法读取此 API 密钥，请在设置中重新填写。") }
    }
}
