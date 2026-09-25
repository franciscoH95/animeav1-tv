package com.animeav1.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThroughputMeterTest {

    /** Lo que tarda en abrirse cada conexión (el handshake de a3) no cuenta como transferencia. */
    @Test
    fun `solo cuenta el tiempo con una transferencia abierta`() {
        val m = ThroughputMeter()
        m.start(now = 39_000)          // 39 s de handshake antes: fuera de la cuenta
        m.add(500_000)
        m.end(now = 41_000)
        m.start(now = 60_000)          // otra conexión (el salto al punto de reanudación)
        m.add(300_000)
        assertEquals(3_000, m.activeMs(now = 61_000))
        assertEquals(800_000L * 1000 / 3_000, m.bytesPerSecond(now = 61_000))
    }

    @Test
    fun `sin transferencia no hay caudal ni division por cero`() {
        val m = ThroughputMeter()
        assertEquals(0, m.activeMs(now = 5_000))
        assertEquals(0, m.bytesPerSecond(now = 5_000))
        m.end(now = 6_000)             // un cierre sin apertura no suma nada
        assertEquals(0, m.activeMs(now = 7_000))
    }

    @Test
    fun `un segundo start sin cerrar no reinicia la cuenta`() {
        val m = ThroughputMeter()
        m.start(now = 1_000)
        m.start(now = 3_000)
        assertEquals(4_000, m.activeMs(now = 5_000))
    }
}

class Mp4UploadThroughputPolicyTest {

    private val episodeMs = (23 * 60 + 40) * 1000L

    /** Los nodos medidos el 2026-09-24 contra ficheros reales de ~0,75 Mbit/s de media. */
    @Test
    fun `a4 a 0,9 Mbit por segundo no basta y a1 a 3 si`() {
        val size = 133_665_247L                    // 0,75 Mbit/s de media en 23:40
        assertTrue("a4", PlaybackPolicy.tooSlow(113_749, size, episodeMs))
        assertFalse("a1", PlaybackPolicy.tooSlow(382_082, size, episodeMs))
    }

    @Test
    fun `sin tamano o sin duracion se exige el minimo fijo`() {
        assertEquals(PlaybackPolicy.MP4UPLOAD_FALLBACK_NEEDED_BYTES_PER_S, PlaybackPolicy.neededBytesPerSecond(0, episodeMs))
        assertEquals(PlaybackPolicy.MP4UPLOAD_FALLBACK_NEEDED_BYTES_PER_S, PlaybackPolicy.neededBytesPerSecond(133_665_247, 0))
        assertTrue(PlaybackPolicy.tooSlow(113_749, 0, 0))
    }

    /** Una película larga pide menos por segundo que un episodio del mismo tamaño. */
    @Test
    fun `lo que se exige sale del bitrate del fichero`() {
        val needEpisode = PlaybackPolicy.neededBytesPerSecond(133_665_247, episodeMs)
        val needMovie = PlaybackPolicy.neededBytesPerSecond(133_665_247, 94 * 60_000L)
        assertEquals(141_195, needEpisode)         // 94 130 B/s de media × 1,5
        assertTrue(needMovie < needEpisode)
    }

    @Test
    fun `el tamano total sale de Content-Range o de Content-Length`() {
        val ranged = mapOf<String, List<String>>("content-range" to listOf("bytes 0-1023/133665247"))
        assertEquals(133_665_247, PlaybackPolicy.totalSizeFrom(ranged, requestPosition = 0))
        val full = mapOf<String, List<String>>("Content-Length" to listOf("133665247"))
        assertEquals(133_665_247, PlaybackPolicy.totalSizeFrom(full, requestPosition = 0))
        assertEquals("un Content-Length a mitad de fichero es lo que queda, no el total",
            0, PlaybackPolicy.totalSizeFrom(full, requestPosition = 5_000_000))
        val unknown = mapOf<String, List<String>>("Content-Range" to listOf("bytes 0-1023/*"))
        assertEquals(0, PlaybackPolicy.totalSizeFrom(unknown, requestPosition = 0))
        assertEquals(0, PlaybackPolicy.totalSizeFrom(emptyMap(), requestPosition = 0))
    }
}
