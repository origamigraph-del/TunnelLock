package app.tunnellock.core

import app.tunnellock.core.parser.LinkExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkExtractorTest {
    @Test fun plainUrl() = assertEquals("https://example.com/a?x=1", LinkExtractor.firstUrl("https://example.com/a?x=1"))

    @Test fun urlInASentence() =
        assertEquals("https://acme.io/verify?t=abc", LinkExtractor.firstUrl("Tap to activate your account: https://acme.io/verify?t=abc — thanks"))

    @Test fun trailingPunctuationTrimmed() =
        assertEquals("https://acme.io/x", LinkExtractor.firstUrl("Open (https://acme.io/x)."))

    @Test fun appScheme() = assertEquals("myapp://activate/abc", LinkExtractor.firstUrl("myapp://activate/abc"))

    @Test fun firstOfSeveral() =
        assertEquals("https://a.example", LinkExtractor.firstUrl("https://a.example and https://b.example"))

    @Test fun none() {
        assertNull(LinkExtractor.firstUrl("no link here"))
        assertNull(LinkExtractor.firstUrl(""))
        assertNull(LinkExtractor.firstUrl(null))
        assertNull(LinkExtractor.firstUrl("https://"))
    }
}
