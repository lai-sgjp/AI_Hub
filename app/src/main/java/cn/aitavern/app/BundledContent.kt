package cn.aitavern.app

import android.content.Context
import cn.aitavern.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString

object BundledContent {
    suspend fun install(context: Context,repository: Repository) = withContext(Dispatchers.IO) {
        if(context.assets.list("bundled")?.contains("manifest.json")!=true) return@withContext
        val manifest=context.assets.open("bundled/manifest.json").bufferedReader().use { TavernJson.decodeFromString<BundleManifest>(it.readText()) }
        if(repository.hasBundle(manifest.id)) return@withContext
        val content=BundledLibrary.load(manifest) { path -> context.assets.open("bundled/$path").use { it.readBytes() } }
        repository.installBundle(manifest.id,content)
    }
}
