package cn.aitavern.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BackupCodec {
    const val MAX_BACKUP = 100 * 1024 * 1024
    fun encode(snapshot: Snapshot): ByteArray {
        val payload = TavernJson.encodeToString(snapshot.copy(version=2)).toByteArray(Charsets.UTF_8)
        require(payload.size <= MAX_BACKUP) { "备份超过 100 MB" }
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { it.putNextEntry(ZipEntry("tavern-v1.json")); it.write(payload); it.closeEntry() }
        return bytes.toByteArray()
    }
    fun decode(bytes: ByteArray): Snapshot {
        require(bytes.size <= MAX_BACKUP) { "备份超过 100 MB" }
        val raw = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            val entry = requireNotNull(zip.nextEntry) { "不是有效的酒馆备份" }
            require(entry.name == "tavern-v1.json" && !entry.isDirectory) { "备份文件结构不支持" }
            val buffer = ByteArrayOutputStream()
            val block = ByteArray(8192)
            while(true) { val n=zip.read(block); if(n<0) break; require(buffer.size()+n<=MAX_BACKUP) { "解压后超过 100 MB" }; buffer.write(block,0,n) }
            require(zip.nextEntry == null) { "备份包含未知附加文件" }
            buffer.toString("UTF-8")
        }
        val snapshot = TavernJson.decodeFromString<Snapshot>(raw)
        validate(snapshot)
        return remap(snapshot)
    }
    fun validate(s: Snapshot) {
        require(s.version in 1..2) { "不支持此备份版本" }
        fun ids(values: List<String>): Set<String> { require(values.all { it.isNotBlank() } && values.distinct().size==values.size) { "备份 ID 无效或重复" }; return values.toSet() }
        val chars=ids(s.characters.map { it.id }); val books=ids(s.books.map { it.id }); val profiles=ids(s.profiles.map { it.id }); val rooms=ids(s.rooms.map { it.id }); ids(s.messages.map { it.id })
        val worlds=ids(s.worlds.map { it.id }); ids(s.memories.map { it.id }); ids(s.segments.map { it.id })
        require(s.worlds.all { w -> w.characterIds.all { it in chars } && w.bookIds.all { it in books } }) { "世界引用无效" }
        require(s.rooms.all { it.worldId.isEmpty() || it.worldId in worlds }) { "房间所属世界无效" }
        val roomsById=s.rooms.associateBy { it.id }
        val worldsById=s.worlds.associateBy { it.id }
        require(s.rooms.all { r -> r.worldId.isEmpty() || r.memberIds.all { it in worldsById.getValue(r.worldId).characterIds } }) { "房间角色不属于其世界" }
        require(s.memories.all { it.worldId in worlds && it.roomId in rooms && it.scope in setOf("room","world") && it.embedding.all(Float::isFinite) }) { "记忆引用无效" }
        require(s.memories.all { roomsById[it.roomId]?.worldId==it.worldId }) { "记忆与来源房间的世界不一致" }
        require(s.segments.all { it.worldId in worlds && it.roomId in rooms && it.start<=it.end }) { "摘要引用无效" }
        require(s.segments.all { roomsById[it.roomId]?.worldId==it.worldId }) { "摘要与来源房间的世界不一致" }
        require(s.characters.all { c -> c.name.isNotBlank() && c.bookIds.all { it in books } }) { "角色引用无效" }
        require(s.rooms.all { r -> r.memberIds.size in 1..8 && r.memberIds.distinct().size==r.memberIds.size && r.memberIds.all { it in chars } && r.bookIds.all { it in books } && r.profileId in profiles && (r.parentId==null || r.parentId in rooms) && r.replies in 1..2 }) { "房间引用无效" }
        require(s.messages.all { it.roomId in rooms && (it.speakerId==null || it.speakerId in chars) && it.sequence>=0 && it.status in setOf("complete","generating","interrupted","failed") }) { "消息引用无效" }
        require(s.messages.groupBy { it.roomId }.values.all { list -> list.map { it.sequence }.distinct().size==list.size }) { "消息序号重复" }
        val versions=ids(s.versions.map { it.id })
        ids(s.endings.map { it.id }); ids(s.contentCache.map { it.id })
        require(s.rooms.all { it.activeVersionId.isBlank() || s.versions.any { v -> v.id==it.activeVersionId && v.roomId==it.id } }) { "Invalid active version" }
        require(s.versions.all { v -> v.roomId in rooms && v.status in setOf("complete","generating","interrupted","failed") && (v.parentVersionId==null || s.versions.any { it.id==v.parentVersionId && it.roomId==v.roomId }) }) { "Invalid version" }
        s.versions.forEach { v ->
            require(v.messages.all { it.roomId==v.roomId } && v.memories.all { it.roomId==v.roomId && it.scope=="room" } && v.segments.all { it.roomId==v.roomId }) { "Invalid timeline ownership" }
            validate(s.copy(rooms=s.rooms.map { it.copy(activeVersionId="") },versions=emptyList(),endings=emptyList(),messages=v.messages,memories=v.memories,segments=v.segments))
        }
        require(s.endings.all { e -> e.roomId in rooms && e.versionId in versions && s.versions.any { it.id==e.versionId && it.roomId==e.roomId } }) { "Invalid ending" }
        require(s.profiles.all { it.contextSize > it.maxOutput && it.maxOutput > 0 && it.temperature in 0.0..2.0 }) { "API 参数无效" }
    }
    private fun remap(s: Snapshot): Snapshot {
        val chars=s.characters.associate { it.id to newId() }; val books=s.books.associate { it.id to newId() }; val profiles=s.profiles.associate { it.id to newId() }; val rooms=s.rooms.associate { it.id to newId() }
        val worlds=s.worlds.associate { it.id to newId() }
        val versions=s.versions.associate { it.id to newId() }
        val groups=(s.endings.map { it.groupId }+s.endings.flatMap { it.rejectedGroups }).filter { it.isNotBlank() }.distinct().associateWith { newId() }
        return s.copy(
            versions=s.versions.map { v -> v.copy(id=versions.getValue(v.id),roomId=rooms.getValue(v.roomId),parentVersionId=v.parentVersionId?.let(versions::getValue),status=if(v.status=="generating") "interrupted" else v.status,
                messages=v.messages.map { m -> m.copy(id=newId(),roomId=rooms.getValue(m.roomId),speakerId=m.speakerId?.let(chars::getValue),status=if(m.status=="generating") "interrupted" else m.status) },
                memories=v.memories.map { m -> m.copy(id=newId(),worldId=worlds.getValue(m.worldId),roomId=rooms.getValue(m.roomId),embedding=emptyList(),embeddingModel="") },
                segments=v.segments.map { seg -> seg.copy(id=newId(),worldId=worlds.getValue(seg.worldId),roomId=rooms.getValue(seg.roomId)) }) },
            endings=s.endings.map { e -> e.copy(id=newId(),roomId=rooms.getValue(e.roomId),versionId=versions.getValue(e.versionId),groupId=groups[e.groupId].orEmpty(),rejectedGroups=e.rejectedGroups.mapNotNull { groups[it] },fingerprint="") },
            contentCache=emptyList(),
            characters=s.characters.map { c -> c.copy(id=chars.getValue(c.id),bookIds=c.bookIds.map(books::getValue)) },
            books=s.books.map { b -> b.copy(id=books.getValue(b.id),entries=b.entries.map { it.copy(id=newId()) }) },
            profiles=s.profiles.map { it.copy(id=profiles.getValue(it.id),name=it.name+" · 恢复") },
            worlds=s.worlds.map { w -> w.copy(id=worlds.getValue(w.id),characterIds=w.characterIds.map(chars::getValue),bookIds=w.bookIds.map(books::getValue),personas=w.personas.map { it.copy(id=newId()) }) },
            rooms=s.rooms.map { r -> r.copy(id=rooms.getValue(r.id),worldId=worlds[r.worldId].orEmpty(),memberIds=r.memberIds.map(chars::getValue),profileId=profiles.getValue(r.profileId),bookIds=r.bookIds.map(books::getValue),parentId=r.parentId?.let(rooms::getValue),activeVersionId=versions[r.activeVersionId].orEmpty(),name=r.name+" · 恢复") },
            memories=s.memories.map { m -> m.copy(id=newId(),worldId=worlds.getValue(m.worldId),roomId=rooms.getValue(m.roomId),embedding=emptyList(),embeddingModel="") },
            segments=s.segments.map { seg -> seg.copy(id=newId(),worldId=worlds.getValue(seg.worldId),roomId=rooms.getValue(seg.roomId)) },
            messages=s.messages.map { m -> m.copy(id=newId(),roomId=rooms.getValue(m.roomId),speakerId=m.speakerId?.let(chars::getValue),status=if(m.status=="generating") "interrupted" else m.status) }
        )
    }
}
