package io.github.psd2live

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FreezeWatchdogTest {
	@Test fun reportsOneThreadDumpPerFreezeAndItsEnd() {
		var now = 0L
		val queued = ArrayList<Runnable>()
		val reports = ArrayList<String>()
		val watchdog = FreezeWatchdog(stallMillis = 5_000L, clock = { now }, post = { queued += it }, report = { reports += it })

		watchdog.check()
		queued.removeFirst().run()
		now = 1_000L
		watchdog.check()
		assertTrue(reports.isEmpty(), "An answering thread is not frozen")

		// The ping posted at 1 s is never run: the watched thread is stuck.
		now = 4_000L; watchdog.check()
		assertTrue(reports.isEmpty())
		now = 7_000L; watchdog.check()
		assertEquals(1, reports.size)
		assertTrue(reports[0].startsWith("UI not responding"))
		assertTrue("psd2live" in reports[0] || "main" in reports[0] || "Test worker" in reports[0], reports[0])
		now = 20_000L; watchdog.check()
		assertEquals(1, reports.size, "One dump per freeze")

		queued.removeFirst().run()
		now = 21_000L; watchdog.check()
		assertEquals(2, reports.size)
		assertTrue(reports[1].startsWith("UI responsive again"))
	}
}
