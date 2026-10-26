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
 * in a file so it survives the process being killed (force stop, crash).
 * Never log secrets: no UUIDs, passwords, or full subscription URLs.
 */
object AppLog {
    private const val MAX_LINES = 400
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

    @Synchronized
    private fun add(level: String, tag: String, message: String) {
        val line = "${time.format(Date())} $level/$tag: $message"
        _lines.value = (_lines.value + line).takeLast(MAX_LINES)
        file?.let { f ->
            runCatching {
                f.appendText(line + "\n")
                if (f.length() > 200_000) f.writeText(_lines.value.joinToString("\n", postfix = "\n"))
            }
        }
    }
}
