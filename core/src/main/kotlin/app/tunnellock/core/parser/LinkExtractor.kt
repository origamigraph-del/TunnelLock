package app.tunnellock.core.parser

/**
 * Pulls the link out of what an email or messaging app shares, which is often the URL
 * inside a sentence ("Tap to activate: https://… — thanks"). Used to open an activation
 * link inside the secure space instead of the personal copy of the app.
 */
object LinkExtractor {
    // A scheme (http, https, or an app's own like myapp) then ://, up to the first space or quote.
    private val URL = Regex("""[a-zA-Z][a-zA-Z0-9+.\-]*://[^\s<>"'`]+""")

    /** The first link in [text], with trailing sentence punctuation trimmed; null if none. */
    fun firstUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val match = URL.find(text) ?: return null
        val url = match.value.trimEnd('.', ',', ';', ')', ']', '}', '>', '"', '\'', '!', '?')
        // Must have something after the scheme, not just "https://".
        return url.takeIf { it.substringAfter("://").isNotEmpty() }
    }
}
