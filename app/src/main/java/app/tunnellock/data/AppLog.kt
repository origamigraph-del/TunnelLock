package app.tunnellock.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app log the user can copy or send from Settings for debugging. It is kept
 * in files so it survives the process being killed (force stop, crash), and
 * long enough for a full day of use: app.log, and app.log.1 once that grows
 * past [MAX_FILE_BYTES]. The screen shows the newest [MAX_LINES] lines.
 * Never log secrets: no UUIDs, passwords, or full subscription URLs.
 */
object AppLog {
    private const val MAX_LINES = 400
    private const val MAX_FILE_BYTES = 3_000_000L
    private val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines
    private var file: File? = null

    /** Loads the saved log and keeps appending to it. Call once per process. */
    @Synchronized
    fun init(context: Context) {
        val f = File(context.filesDir, "app.log")
        file = f
        if (f.exists()) _lines.value = runCatching { f.readLines().takeLast(MAX_LINES) }.getOrDefault(emptyList())
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        add("I", tag, message)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        Log.w(tag, message, error)
        add("W", tag, error?.let { "$message: ${it.javaClass.simpleName}: ${it.message}" } ?: message)
    }

    fun text(): String = _lines.value.joinToString("\n")

    /** Everything still on disk, oldest first: up to two files of [MAX_FILE_BYTES]. */
    @Synchronized
    fun fullText(): String {
        val current = file ?: return text()
        val previous = File(current.parentFile, current.name + ".1")
        return listOf(previous, current).filter { it.exists() }
            .joinToString("") { runCatching { it.readText() }.getOrDefault("") }
            .trimEnd()
    }

    @Synchronized
    private fun add(level: String, tag: String, message: String) {
        val line = "${time.format(Date())} $level/$tag: $message"
        _lines.value = (_lines.value + line).takeLast(MAX_LINES)
        file?.let { f ->
            runCatching {
                f.appendText(line + "\n")
                if (f.length() > MAX_FILE_BYTES) {
                    // Keep one older file; the next line starts a new app.log.
                    val previous = File(f.parentFile, f.name + ".1")
                    previous.delete()
                    f.renameTo(previous)
                }
            }
        }
    }
}
