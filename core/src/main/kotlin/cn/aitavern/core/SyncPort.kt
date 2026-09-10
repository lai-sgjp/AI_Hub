package cn.aitavern.core

/** V1 has no remote implementation. Tokens/credentials must remain outside this payload. */
interface SyncPort {
    suspend fun pull(cursor: String?): SyncEnvelope
    suspend fun push(payload: SyncEnvelope, expectedRevision: String?): String
}
data class SyncEnvelope(val schemaVersion: Int = 1,val snapshot: Snapshot,val revision: String?,val cursor: String?,val deletedIds: Set<String> = emptySet())
