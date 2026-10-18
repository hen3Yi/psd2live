package io.github.psd2live.scenario

import io.github.psd2live.core.Side
import io.github.psd2live.core.RigBuildProfile
import io.github.psd2live.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

/** Model > Split Layers by Mesh, from a PSD generated on the start page: the offer, the split, and the editor staying responsive. */
class MeshSplitMenuScenario {
	@TempDir lateinit var temp: Path

	private fun editor(body: suspend (PSD2LiveViewModel) -> Unit) = runBlocking {
		val choices = AppSettings.startChoices
        val profile = System.getenv("PSD2LIVE_IMPORT_PROFILE") == "1"
        val wasRecording = RigBuildProfile.recording
        if (profile) { RigBuildProfile.reset(); RigBuildProfile.recording = true }
		try {
			PSD2LiveViewModel().use { vm ->
				vm.setStateForTest(vm.state.value.copy(atlasSize = 512, exportMoc3 = false, generatePhysics = false))
				DesktopWorkspace(vm, temp.resolve("store")).use { workspace ->
					vm.attachWorkspace(workspace)
					body(vm)
				}
			}
		} finally {
            if (profile) {
                RigBuildProfile.recording = wasRecording
                RigBuildProfile.snapshot().forEach { (stage, measurement) ->
                    println("$stage: ${measurement.first} ms (${measurement.second} calls)")
                }
            }
			AppSettings.startChoices = choices
		}
	}

	private suspend fun until(what: String, condition: () -> Boolean) {
		withTimeoutOrNull(120_000) { while (!condition()) delay(20) } ?: fail("Timed out waiting for $what")
	}

	private suspend fun settle(vm: PSD2LiveViewModel) {
		withTimeout(120_000) { vm.state.first { !it.workspaceEditBusy && !it.editorDraftBusy && !it.isAnalyzing && !it.isGenerating } }
	}

	/** The longest the UI thread went without running a 10 ms tick while [block] ran. */
	private suspend fun longestUiStall(block: suspend () -> Unit): Long = coroutineScope {
		var longest = 0L
        val ready = CompletableDeferred<Unit>()
		val ticker = launch(Dispatchers.Main) {
			var last = System.nanoTime()
            ready.complete(Unit)
			while (isActive) {
				delay(10)
				val now = System.nanoTime()
				longest = maxOf(longest, (now - last) / 1_000_000)
				last = now
			}
		}
		try { ready.await(); block() } finally { ticker.cancelAndJoin() }
		longest
	}

	private fun sourceNames(vm: PSD2LiveViewModel) = vm.state.value.analysis?.source?.layers?.map { it.name }.orEmpty()

	@Test fun theMenuSplitsAPsdGeneratedOnTheStartPage() = editor { vm ->
		val scale = (System.getProperty("psd2live.scenario.splitScale") ?: System.getenv("PSD2LIVE_SPLIT_SCALE"))?.toFloat() ?: 1f
		vm.setStartChoices(StartQuickPreset.DEFAULT.choices)
		vm.setInputPath(Characters.eyesPsd(temp, scale).toString())
		val importStall = longestUiStall {
			vm.analyze(discardUnsaved = true)
			until("the import") { vm.state.value.previewModel != null && !vm.startPageShown }
			settle(vm)
		}
		val scanStall = longestUiStall {
			vm.requestMeshSplitScan()
			until("the split offer") { vm.pendingMeshSplit != null || vm.pendingMeshSplitBatch != null }
		}
		val offer = assertNotNull(vm.pendingMeshSplit, "One layer falls apart: ${vm.pendingMeshSplitBatch?.splits?.map { it.layerName }}")
		assertEquals("eyes", offer.layerName)
		val applyStall = longestUiStall {
			vm.confirmMeshSplit(listOf("eyes-r", "eyes-l"), listOf(Side.RIGHT, Side.LEFT))
			until("the split") { "eyes-r" in sourceNames(vm) }
			settle(vm)
		}
		println("UI stalls at scale $scale: import ${importStall} ms, scan ${scanStall} ms, apply ${applyStall} ms")
		assertTrue("eyes-l" in sourceNames(vm) && "eyes" !in sourceNames(vm), "${sourceNames(vm)}")
		// The menu answers again once the split is in.
		vm.requestMeshSplitScan()
		until("the second scan's answer") { vm.state.value.statusText == io.github.psd2live.i18n.tr("start.splits.none") ||
			vm.pendingMeshSplit != null || vm.pendingMeshSplitBatch != null }
	}
}
