package io.github.psd2live.application

import io.github.psd2live.history.WorkspaceHistoryTree
import io.github.psd2live.project.*
import io.github.psd2live.scenario.Characters
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class WorkspaceInstallationTest {
    @Test fun preparingAnInstallationDoesNotHoldTheRuntimeLockAndRejectsAConcurrentEdit() = runBlocking {
        val document = Characters.head(physics = false)
        val runtime = WorkspaceRuntime<String>({ "rebuilt" })
        val initial = runtime.install(runtime.state.value.state, "old", document, "old model")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val source = object : SourceArt by document.source {
            override val layers: List<SourceLayer> get() {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return document.source.layers
            }
        }
        var projected = false
        val candidate = async(Dispatchers.Default) {
            val installation = WorkspaceInstallation.prepare(document.copy(source = source))
            runCatching { runtime.installPrepared(initial.state, "new", installation, "new model", discardUnsaved = true) {
                projected = true
            } }
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
            // This takes the runtime lock; a held install lock would deadlock until the gate timed out.
            withTimeout(5_000) { withContext(Dispatchers.Default) {
                runtime.updateAuxiliary(initial.projectId, initial.state, buildJsonObject { put("edit", "newer") })
            } }
        } finally { release.countDown() }
        assertIs<WorkspaceConflict>(candidate.await().exceptionOrNull())
        assertFalse(projected)
        assertEquals(initial.historyHead, runtime.capture().historyHead)
        assertEquals("old model", runtime.capture().model)
    }

    @Test fun preparedInstallStillProtectsDirtyStateAndAFailedProjectionPublishesNothing() {
        val document = Characters.head(physics = false)
        val installation = WorkspaceInstallation.prepare(document)
        val runtime = WorkspaceRuntime<String>({ "unused" })
        val initial = runtime.installPrepared(runtime.state.value.state, "old", installation, "old model", dirty = true)
        val before = runtime.read()
        assertFailsWith<WorkspaceUnsavedChanges> {
            runtime.installPrepared(initial.state, "new", installation, "new model") { fail("Must not project") }
        }
        assertSame(before, runtime.read())
        assertFailsWith<IllegalArgumentException> {
            runtime.installPrepared(initial.state, "new", installation, "new model", discardUnsaved = true) {
                throw IllegalArgumentException("Projection failed")
            }
        }
        assertSame(before, runtime.read())
    }

    @Test fun mismatchedHistoryIsRejectedDuringPreparation() {
        val document = Characters.head(physics = false)
        val changed = document.copy(layerVisibility = mapOf("face" to false))
        val revision = WorkspaceRevisions.of(changed)
        val history = WorkspaceHistoryTree(changed, revision, revision).state()
        assertFailsWith<IllegalArgumentException> { WorkspaceInstallation.prepare(document, history) }
    }
}
