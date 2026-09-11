package cn.aitavern.app

import android.content.Context
import cn.aitavern.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString

object BundledContent {
    suspend fun install(context: Context,repository: Repository) = withContext(Dispatchers.IO) {
        for(manifestPath in manifestPaths(context)) {
            val relativeManifestPath=manifestPath.removePrefix("bundled/")
            val assetPrefix=if(relativeManifestPath == "manifest.json") "" else relativeManifestPath.removeSuffix("/manifest.json")
            val manifest=context.assets.open(manifestPath).bufferedReader().use { TavernJson.decodeFromString<BundleManifest>(it.readText()) }
                .copy(assetPrefix=assetPrefix)
            if(repository.hasBundle(manifest.id)) continue
            val content=BundledLibrary.load(manifest) { path -> context.assets.open("bundled/$path").use { it.readBytes() } }
            val thumbnails=content.snapshot.characters.map { it.copy(avatar=AvatarThumbnail.create(it.avatar)) }
            repository.installBundle(manifest.id,content.copy(snapshot=content.snapshot.copy(characters=thumbnails)))
        }
    }

    private fun manifestPaths(context: Context): List<String> {
        val rootEntries=context.assets.list("bundled").orEmpty()
        val paths=buildList {
            if("manifest.json" in rootEntries) add("bundled/manifest.json")
            if("bundles" in rootEntries) {
                for(bundleId in context.assets.list("bundled/bundles").orEmpty()) {
                    if("manifest.json" in context.assets.list("bundled/bundles/$bundleId").orEmpty()) {
                        add("bundled/bundles/$bundleId/manifest.json")
                    }
                }
            }
        }
        return paths
    }
}
