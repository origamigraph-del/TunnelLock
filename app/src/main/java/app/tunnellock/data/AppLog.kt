package app.tunnellock.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app log the user can copy from Settings and send for debugging.
 * Never log secrets: no UUIDs, passwords, or full subscription URLs.
 */
object AppLog {
    private const val MAX_LINES = 400
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

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
    }
}
