package io.github.psd2live

import java.io.OutputStream
import java.io.PrintStream
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The editor's console in a file. An installed app has no visible console, so what it prints there (GPU fallbacks,
 * uncaught exceptions on the EDT, [FreezeWatchdog]'s thread dumps) would otherwise be lost to a user reporting a
 * problem. `psd2live.log` holds this run, `psd2live.previous.log` the one before, so a run that froze and was killed
 * is still there after the next start.
 */
internal object SessionLog {
	/** Above this the file stops growing; the console still gets everything. */
	private const val MAX_BYTES = 16L * 1024 * 1024

	fun install(directory: Path): Path? = runCatching {
		Files.createDirectories(directory)
		val file = directory.resolve("psd2live.log")
		if (Files.exists(file)) Files.move(file, directory.resolve("psd2live.previous.log"), StandardCopyOption.REPLACE_EXISTING)
		val sink = CappedFile(file, MAX_BYTES)
		System.setOut(PrintStream(Tee(System.out, sink), true, Charsets.UTF_8))
		System.setErr(PrintStream(Tee(System.err, sink), true, Charsets.UTF_8))
		println("PSD2Live ${AppVersion.value} · ${now()} · Java ${System.getProperty("java.version")} · " +
			"${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}")
		file
	}.onFailure { System.err.println("Session log unavailable: $it") }.getOrNull()

	fun now(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

	/** Writes to both streams; a failing file never stops the console. */
	private class Tee(private val console: OutputStream, private val file: OutputStream) : OutputStream() {
		override fun write(b: Int) { console.write(b); runCatching { file.write(b) } }
		override fun write(b: ByteArray, off: Int, len: Int) { console.write(b, off, len); runCatching { file.write(b, off, len) } }
		override fun flush() { console.flush(); runCatching { file.flush() } }
	}

	private class CappedFile(path: Path, private val limit: Long) : OutputStream() {
		private val out = Files.newOutputStream(path)
		private var written = 0L

		@Synchronized override fun write(b: Int) {
			if (written >= limit) return
			out.write(b)
			written++
		}

		@Synchronized override fun write(b: ByteArray, off: Int, len: Int) {
			val room = (limit - written).coerceAtLeast(0L).toInt().coerceAtMost(len)
			if (room == 0) return
			out.write(b, off, room)
			written += room
		}

		@Synchronized override fun flush() = out.flush()
	}
}

/**
 * Writes every thread's stack to the console when the EDT stops answering for [stallMillis]: a frozen window then
 * leaves in the log what it was waiting on, which a user can send instead of only a screen recording. Once per
 * freeze; the end of the freeze is logged too.
 */
internal class FreezeWatchdog(
	private val stallMillis: Long = 5_000L,
	private val pollMillis: Long = 500L,
	private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
	/** Runs the ping on the thread watched; the EDT in the app. */
	private val post: (Runnable) -> Unit = { javax.swing.SwingUtilities.invokeLater(it) },
	private val report: (String) -> Unit = { System.err.println(it) },
) {
	@Volatile private var answered = clock()
	@Volatile private var pending = false
	private var frozenSince = -1L
	@Volatile private var running = false

	fun start() {
		if (running) return
		running = true
		Thread({
			while (running) {
				check()
				try { Thread.sleep(pollMillis) } catch (_: InterruptedException) { return@Thread }
			}
		}, "psd2live-freeze-watchdog").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }.start()
	}

	fun stop() { running = false }

	/** One poll: pings the watched thread when it has answered, and reports when it has not for too long. */
	internal fun check() {
		val now = clock()
		if (!pending) {
			if (frozenSince >= 0) {
				report("UI responsive again after ${(now - frozenSince) / 1000.0} s · ${SessionLog.now()}")
				frozenSince = -1L
			}
			pending = true
			post(Runnable { answered = clock(); pending = false })
			return
		}
		if (frozenSince < 0 && now - answered >= stallMillis) {
			frozenSince = answered
			report("UI not responding for ${(now - answered) / 1000} s · ${SessionLog.now()}; thread dump follows\n${threadDump()}")
		}
	}

	private fun threadDump(): String = runCatching {
		ManagementFactory.getThreadMXBean().dumpAllThreads(true, true).joinToString("") { info ->
			// ThreadInfo.toString cuts each stack at 8 frames; the frames that matter are often deeper.
			buildString {
				append('"').append(info.threadName).append("\" ").append(info.threadState)
				info.lockName?.let { append(" on ").append(it) }
				info.lockOwnerName?.let { append(" owned by \"").append(it).append('"') }
				append('\n')
				for ((index, frame) in info.stackTrace.withIndex()) {
					append("\tat ").append(frame).append('\n')
					for (monitor in info.lockedMonitors) if (monitor.lockedStackDepth == index) append("\t- locked ").append(monitor).append('\n')
				}
				append('\n')
			}
		}
	}.getOrElse { "Thread dump unavailable: $it" }
}
