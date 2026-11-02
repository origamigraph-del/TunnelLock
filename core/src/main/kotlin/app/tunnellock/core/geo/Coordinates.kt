package app.tunnellock.core.geo

data class Coordinates(val latitude: Double, val longitude: Double) {
    /** "35.689200, 51.389000", trimmed to six decimals (about 0.1 m, enough for any map). */
    fun format(): String = "%.6f, %.6f".format(latitude, longitude).trimZeros()

    private fun String.trimZeros() = split(", ").joinToString(", ") {
        it.trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }
}

/**
 * Reads coordinates the way a user would paste them: two numbers, or a Google Maps
 * or `geo:` link. Only well-formed, in-range values come back; anything else is null,
 * so a typo cannot turn into a silent wrong location.
 */
object CoordinateParser {
    private val PAIR = Regex("""(-?\d{1,3}(?:\.\d+)?)\s*[,; ]\s*(-?\d{1,3}(?:\.\d+)?)""")
    private val AT = Regex("""@(-?\d{1,3}(?:\.\d+)?),(-?\d{1,3}(?:\.\d+)?)""")
    private val QUERY = Regex("""[?&](?:q|ll|query|center|destination)=(-?\d{1,3}(?:\.\d+)?)(?:,|%2[Cc])(-?\d{1,3}(?:\.\d+)?)""")
    private val GEO = Regex("""geo:(-?\d{1,3}(?:\.\d+)?),(-?\d{1,3}(?:\.\d+)?)""")

    fun parse(text: String): Coordinates? {
        val input = normalizeDigits(text).trim()
        if (input.isEmpty()) return null
        // A map link first: its @lat,lon or q=lat,lon is the place, not the zoom or other numbers.
        val match = GEO.find(input) ?: QUERY.find(input) ?: AT.find(input)
            ?: PAIR.find(input)?.takeIf { it.value == input } // a bare pair must be the whole text
            ?: return null
        val lat = match.groupValues[1].toDoubleOrNull() ?: return null
        val lon = match.groupValues[2].toDoubleOrNull() ?: return null
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) Coordinates(lat, lon) else null
    }

    /** Whether the text is a link, so "no coordinates" can be told apart from a typo. */
    fun looksLikeUrl(text: String): Boolean = "://" in text.trim()

    /** Turns Persian/Arabic digits and the Arabic decimal mark into the ASCII a parser expects. */
    private fun normalizeDigits(text: String): String = buildString(text.length) {
        for (c in text) {
            append(
                when (c) {
                    in '۰'..'۹' -> '0' + (c - '۰') // Persian 0-9
                    in '٠'..'٩' -> '0' + (c - '٠') // Arabic-Indic 0-9
                    '٫' -> '.' // Arabic decimal separator
                    '،', '٬' -> ',' // Arabic comma / thousands separator
                    else -> c
                },
            )
        }
    }
}
