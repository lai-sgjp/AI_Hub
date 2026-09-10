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
@Dao interface RecordDao {
    @Query("SELECT * FROM records") fun observe(): Flow<List<Record>>
    @Query("SELECT * FROM records") suspend fun all(): List<Record>
    @Upsert suspend fun put(records: List<Record>)
    @Query("SELECT EXISTS(SELECT 1 FROM records WHERE id = :id AND kind = 'bundle')") suspend fun hasBundle(id: String): Boolean
    @Query("SELECT payload FROM records WHERE kind = 'bundle'") suspend fun bundleReports(): List<String>
}
@Database(entities=[Record::class],version=1,exportSchema=true)
abstract class TavernDatabase: RoomDatabase() { abstract fun records(): RecordDao }

class Repository(context: Context) {
    private val db = Room.databaseBuilder(context,TavernDatabase::class.java,"tavern.db").build()
    val snapshots = db.records().observe().map(::decode)
    private fun decode(rows: List<Record>): Snapshot {
        fun <T> get(kind: String, decode: (String)->T) = rows.filter { it.kind==kind }.map { decode(it.payload) }
        return Snapshot(characters=get("character") { TavernJson.decodeFromString<Character>(it) }, books=get("book") { TavernJson.decodeFromString<LoreBook>(it) }, profiles=get("profile") { TavernJson.decodeFromString<ApiProfile>(it) }, rooms=get("room") { TavernJson.decodeFromString<ChatRoom>(it) }.sortedByDescending { it.createdAt }, messages=get("message") { TavernJson.decodeFromString<Message>(it) }.sortedBy { it.sequence }, settings=get("settings") { TavernJson.decodeFromString<AppSettings>(it) }.firstOrNull() ?: AppSettings(), worlds=get("world") { TavernJson.decodeFromString<World>(it) },memories=get("memory") { TavernJson.decodeFromString<MemoryFact>(it) },segments=get("segment") { TavernJson.decodeFromString<SummarySegment>(it) })
    }
    suspend fun snapshot(): Snapshot = decode(db.records().all())
    suspend fun save(value: Character) = db.records().put(listOf(Record(value.id,"character",TavernJson.encodeToString(value))))
    suspend fun save(value: LoreBook) = db.records().put(listOf(Record(value.id,"book",TavernJson.encodeToString(value))))
    suspend fun save(value: ApiProfile) = db.records().put(listOf(Record(value.id,"profile",TavernJson.encodeToString(value))))
    suspend fun save(value: ChatRoom) = db.records().put(listOf(Record(value.id,"room",TavernJson.encodeToString(value))))
    suspend fun save(value: Message) = db.records().put(listOf(Record(value.id,"message",TavernJson.encodeToString(value))))
    suspend fun save(value: AppSettings) = db.records().put(listOf(Record("settings","settings",TavernJson.encodeToString(value))))
    suspend fun save(value: World) = db.records().put(listOf(Record(value.id,"world",TavernJson.encodeToString(value))))
    suspend fun save(value: MemoryFact) = db.records().put(listOf(Record(value.id,"memory",TavernJson.encodeToString(value))))
    suspend fun save(value: SummarySegment) = db.records().put(listOf(Record(value.id,"segment",TavernJson.encodeToString(value))))
    suspend fun merge(s: Snapshot, settings: Boolean = false) = db.withTransaction {
        s.characters.forEach { save(it) }; s.books.forEach { save(it) }; s.profiles.forEach { save(it) }; s.rooms.forEach { save(it) }; s.messages.forEach { save(it) }
        s.worlds.forEach { save(it) }; s.memories.forEach { save(it) }; s.segments.forEach { save(it) }
        if(settings) save(s.settings)
    }
    suspend fun recover() = db.withTransaction { snapshot().messages.filter { it.status=="generating" }.forEach { save(it.copy(status="interrupted")) } }
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
