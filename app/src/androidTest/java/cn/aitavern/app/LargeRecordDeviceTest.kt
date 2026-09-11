package cn.aitavern.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.aitavern.core.World
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LargeRecordDeviceTest {
    @Test fun largeUnicodeRecordSurvivesSnapshotAndObservation() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repository=Repository(context)
        val world=World(id="large-record-regression",name="Large record regression",description="中😀".repeat(800_000))
        repository.save(world)
        try {
            assertEquals(world,repository.snapshot().worlds.single { it.id==world.id })
            val observed=withTimeout(15_000) { repository.snapshots.first() }
            assertEquals(world,observed.worlds.single { it.id==world.id })
        } finally {
            repository.save(world.copy(description=""))
        }
    }
}
