package cn.aitavern.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.aitavern.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundledContentDeviceTest {
    @Test fun builtinCatalogMatchesManifestAndNeverOverwritesUserEdits() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TavernApplication
        val hasManifest=app.assets.list("bundled")?.contains("manifest.json")==true
        if(!hasManifest) {
            // A public source-only build intentionally contains no private catalog.
            BundledContent.install(app,app.repository)
            assertFalse(app.assets.list("bundled").orEmpty().contains("manifest.json"))
            return@runBlocking
        }
        val manifest=app.assets.open("bundled/manifest.json").bufferedReader().use { TavernJson.decodeFromString<BundleManifest>(it.readText()) }
        BundledContent.install(app,app.repository)
        val expected=BundledLibrary.load(manifest) { app.assets.open("bundled/$it").use { stream -> stream.readBytes() } }.snapshot
        val before=app.repository.snapshot()
        val world=before.worlds.single { it.id==expected.worlds.single().id }
        assertEquals(manifest.playerName,world.personas.single().name)
        assertEquals(expected.characters.size,world.characterIds.size)
        assertFalse(before.characters.filter { it.id in world.characterIds }.any { it.name==manifest.playerName })
        assertEquals(expected.books.sumOf { it.entries.size },before.books.filter { it.id in world.bookIds }.sumOf { it.entries.size })
        assertEquals(manifest.documents.size,world.documents.size)
        app.repository.save(world.copy(description=world.description+"\n个人编辑保留"))
        BundledContent.install(app,app.repository)
        val after=app.repository.snapshot()
        assertEquals(before.characters.size,after.characters.size)
        assertTrue(after.worlds.single { it.id==world.id }.description.endsWith("个人编辑保留"))
        app.repository.save(world)
    }

    @Test fun nestedBundleInstallsAsAnotherWorld() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TavernApplication
        val bundleIds=app.assets.list("bundled/bundles").orEmpty()
        if(bundleIds.isEmpty()) return@runBlocking
        for(bundleId in bundleIds) {
            if("manifest.json" !in app.assets.list("bundled/bundles/$bundleId").orEmpty()) continue
            val manifestPath="bundled/bundles/$bundleId/manifest.json"
            val manifest=app.assets.open(manifestPath).bufferedReader().use { TavernJson.decodeFromString<BundleManifest>(it.readText()) }
                .copy(assetPrefix="bundles/$bundleId")
            BundledContent.install(app,app.repository)
            val expected=BundledLibrary.load(manifest) { path -> app.assets.open("bundled/$path").use { stream -> stream.readBytes() } }.snapshot
            val world=app.repository.snapshot().worlds.single { it.id==expected.worlds.single().id }
            assertEquals(expected.characters.size,world.characterIds.size)
            assertTrue(expected.characters.flatMap { it.gallery }.all { it.assetPath.startsWith("bundles/$bundleId/") })
        }
    }
}
