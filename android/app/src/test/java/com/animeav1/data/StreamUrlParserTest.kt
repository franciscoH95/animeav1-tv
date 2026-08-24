package com.animeav1.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stream-URL patterns, exercised against a real MP4Upload embed page. This is the piece
 * with the worst track record in the project, and the only way to check it before was to open
 * the app and play an episode.
 */
class StreamUrlParserTest {

    private val mp4uploadPage = fixture("mp4upload-embed.html")

    private val realVideoUrl =
        "https://a3.mp4upload.com:183/d/xsx67fvpz3b4quuo6crrap2qjbbig4ppkm63xqjojsqgicfjscu3uqfrqegfuzx72s6ok7dz/video.mp4"

    @Test
    fun `extracts the direct mp4 from a real MP4Upload page`() {
        assertEquals(realVideoUrl, StreamUrlParser.directUrlFrom(mp4uploadPage))
    }

    /**
     * Regression. An unanchored `\.mp4` also matches inside the host `www.mp4upload.com`, and
     * the first such hit on this page is a player `<script>` — the extractor used to hand
     * ExoPlayer the string "https://www.mp4".
     */
    @Test
    fun `does not mistake the mp4upload host for a video file`() {
        assertTrue(
            "el fixture ya no contiene la trampa que motivó este test",
            mp4uploadPage.contains("www.mp4upload.com/player")
        )
        val url = StreamUrlParser.directUrlFrom(mp4uploadPage)!!
        assertTrue("extrajo algo que no es un vídeo: $url", url.endsWith(".mp4"))
        assertFalse("extrajo un script del player: $url", url.contains("/player/"))
    }

    @Test
    fun `prefers hls over progressive when the page offers both`() {
        val html = """
            <source src="https://cdn.example.com/a/video.mp4" type="video/mp4">
            <source src="https://cdn.example.com/a/master.m3u8" type="application/x-mpegURL">
        """.trimIndent()
        assertEquals("https://cdn.example.com/a/master.m3u8", StreamUrlParser.directUrlFrom(html))
    }

    @Test
    fun `keeps a signed query string but stops at the closing quote`() {
        val html = """<video src="https://cdn.example.com/v.mp4?token=abc123&e=99"></video>"""
        assertEquals("https://cdn.example.com/v.mp4?token=abc123&e=99", StreamUrlParser.directUrlFrom(html))
    }

    @Test
    fun `returns null when the page has nothing playable`() {
        assertNull(StreamUrlParser.directUrlFrom("<html><body>Video not found</body></html>"))
    }

    @Test
    fun `turns a zilla play url into its m3u8 twin`() {
        assertEquals(
            "https://player.zilla-networks.com/m3u8/6a71c5a6e6fe622dc1ad7e764ccc41ed",
            StreamUrlParser.zillaM3u8("https://player.zilla-networks.com/play/6a71c5a6e6fe622dc1ad7e764ccc41ed")
        )
    }

    @Test
    fun `ignores trailing slash query and fragment on a zilla url`() {
        assertEquals(
            "https://player.zilla-networks.com/m3u8/6a71c5a6e6fe622dc1ad7e764ccc41ed",
            StreamUrlParser.zillaM3u8("https://player.zilla-networks.com/play/6a71c5a6e6fe622dc1ad7e764ccc41ed/?x=1#f")
        )
    }

    @Test
    fun `rejects a zilla url whose id is not 32 chars`() {
        assertNull(StreamUrlParser.zillaM3u8("https://player.zilla-networks.com/play/deadbeef"))
    }

    // ── Sec-Fetch-Site ────────────────────────────────────────────────────────────────────────

    /**
     * El caso que arregla HLS. Cloudflare exige literalmente `same-origin` en los segmentos de
     * Zilla; cualquier otro valor (o ninguno) devuelve 403 y media3 se queda buffereando para
     * siempre sin lanzar ningún error.
     */
    @Test
    fun `los segmentos de zilla salen como same-origin`() {
        assertEquals(
            "same-origin",
            StreamUrlParser.secFetchSite(
                "https://player.zilla-networks.com/segs/6a71c5a6e6fe622dc1ad7e764ccc41ed/000.html",
                "https://player.zilla-networks.com/"
            )
        )
    }

    @Test
    fun `un subdominio distinto del mismo sitio es same-site`() {
        assertEquals(
            "same-site",
            StreamUrlParser.secFetchSite(
                "https://a3.mp4upload.com:183/d/xyz/video.mp4",
                "https://www.mp4upload.com/"
            )
        )
    }

    @Test
    fun `un dominio ajeno es cross-site`() {
        assertEquals(
            "cross-site",
            StreamUrlParser.secFetchSite("https://cdn.example.com/v.mp4", "https://animeav1.com/")
        )
    }

    /** Mismo host y esquema pero distinto puerto = otro origen. No mentir aquí es gratis. */
    @Test
    fun `distinto puerto deja de ser same-origin`() {
        assertEquals(
            "same-site",
            StreamUrlParser.secFetchSite("https://cdn.example.com:8443/v.mp4", "https://cdn.example.com/")
        )
    }

    @Test
    fun `el puerto por defecto no rompe la comparacion de origen`() {
        assertEquals(
            "same-origin",
            StreamUrlParser.secFetchSite("https://cdn.example.com:443/v.mp4", "https://cdn.example.com/")
        )
    }

    @Test
    fun `una url ilegible degrada a cross-site en vez de reventar`() {
        assertEquals("cross-site", StreamUrlParser.secFetchSite("no es una url", "https://x.com/"))
        assertEquals("cross-site", StreamUrlParser.secFetchSite("https://x.com/v.mp4", ""))
    }
}
