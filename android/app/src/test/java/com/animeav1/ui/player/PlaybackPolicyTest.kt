package com.animeav1.ui.player

import com.animeav1.data.StreamUrlParser
import com.animeav1.data.model.AudioTrack
import com.animeav1.data.model.EmbedServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPolicyTest {

    private val hls = EmbedServer("HLS", "https://player.zilla-networks.com/play/6a71c5a6e6fe622dc1ad7e764ccc41ed", AudioTrack.SUB)
    private val voe = EmbedServer("Voe", "https://voe.sx/e/bnpyhzir3pee", AudioTrack.SUB)
    private val mp4u = EmbedServer("MP4Upload", "https://www.mp4upload.com/embed-nzl6vpv2j8fv.html", AudioTrack.SUB)
    private val siteOrder = listOf(hls, voe, mp4u)
    private val noFailures: (EmbedServer) -> Long = { 0L }

    @Test
    fun `reconoce mp4upload por host, embed o nodo`() {
        assertTrue(StreamUrlParser.isMp4Upload("https://www.mp4upload.com/embed-x.html"))
        assertTrue(StreamUrlParser.isMp4Upload("https://a3.mp4upload.com:183/d/xyz/video.mp4"))
        assertTrue(StreamUrlParser.isMp4Upload("https://mp4upload.com/x"))
        assertFalse(StreamUrlParser.isMp4Upload("https://voe.sx/e/x?ref=mp4upload.com"))
        assertFalse(StreamUrlParser.isMp4Upload("https://notmp4upload.com/x"))
        assertFalse(StreamUrlParser.isMp4Upload("no es una url"))
    }

    /** El tiempo de lectura acota el handshake TLS en Android: 8 s dejaban MP4Upload sin sonar. */
    @Test
    fun `solo mp4upload tiene tiempo de lectura largo`() {
        assertEquals(45_000, PlaybackPolicy.readTimeoutMs("https://a3.mp4upload.com:183/d/xyz/video.mp4"))
        assertEquals(8_000, PlaybackPolicy.readTimeoutMs("https://cdn.cloudwindow-route.com/engine/hls2/x/master.m3u8"))
        assertTrue("el peor handshake medido fue 41,9 s", PlaybackPolicy.MP4UPLOAD_READ_TIMEOUT_MS > 41_900)
    }

    @Test
    fun `la paciencia del watchdog depende de la fuente y de si hay que tenerla`() {
        assertEquals(25_000L, PlaybackPolicy.quietBudgetMs(isMp4Upload = false, patient = true))
        assertEquals(25_000L, PlaybackPolicy.quietBudgetMs(isMp4Upload = false, patient = false))
        assertEquals(8_000L, PlaybackPolicy.quietBudgetMs(isMp4Upload = true, patient = false))
        assertEquals(50_000L, PlaybackPolicy.quietBudgetMs(isMp4Upload = true, patient = true))
        assertTrue(
            "con paciencia tiene que aguantar más que el tiempo de lectura",
            PlaybackPolicy.MP4UPLOAD_PATIENT_QUIET_MS > PlaybackPolicy.MP4UPLOAD_READ_TIMEOUT_MS
        )
    }

    @Test
    fun `un arranque lento delata un nodo lento`() {
        assertFalse(PlaybackPolicy.isSlowStart(6_000))
        assertTrue(PlaybackPolicy.isSlowStart(17_000))
    }

    @Test
    fun `con av1 por hardware mp4upload va primero`() {
        val sorted = siteOrder.sortedWith(PlaybackPolicy.order(noFailures, av1Hardware = true))
        assertEquals(listOf(mp4u, hls, voe), sorted)
    }

    /**
     * Sin AV1 por hardware, las fuentes AV1 (Zilla y MP4Upload) van detrás de las H.264: por
     * software van a tirones, y huir de un AV1 que no da abasto no puede acabar en otro AV1.
     */
    @Test
    fun `sin av1 por hardware las fuentes av1 van detras`() {
        val sorted = siteOrder.sortedWith(PlaybackPolicy.order(noFailures, av1Hardware = false))
        assertEquals(listOf(voe, hls, mp4u), sorted)
    }

    @Test
    fun `sabe que fuentes son av1`() {
        assertTrue(PlaybackPolicy.isAv1Source(hls))
        assertTrue(PlaybackPolicy.isAv1Source(mp4u))
        assertFalse(PlaybackPolicy.isAv1Source(voe))
        assertFalse(PlaybackPolicy.isAv1Source(EmbedServer("YourUpload", "https://www.yourupload.com/embed/x", AudioTrack.SUB)))
    }

    /** Un fallo reciente pesa más que el rango: un MP4Upload en racha lenta no se reintenta primero. */
    @Test
    fun `lo que ha fallado hace poco va detras aunque sea mp4upload`() {
        val penalty: (EmbedServer) -> Long = { if (it == mp4u || it == hls) 600_000L else 0L }
        val sorted = siteOrder.sortedWith(PlaybackPolicy.order(penalty, av1Hardware = true))
        assertEquals(voe, sorted.first())
        assertEquals("entre las que fallaron, también manda el rango", listOf(voe, mp4u, hls), sorted)
    }

    /** media3 avisa en bloques de 50: el umbral son tres bloques. */
    @Test
    fun `un av1 que pierde fotogramas a chorros en el primer minuto se delata`() {
        assertFalse(PlaybackPolicy.av1Misbehaving(droppedFrames = 100, playingForMs = 30_000))
        assertTrue(PlaybackPolicy.av1Misbehaving(droppedFrames = 150, playingForMs = 30_000))
        assertFalse("fuera de la ventana ya no se juzga", PlaybackPolicy.av1Misbehaving(droppedFrames = 500, playingForMs = 90_000))
    }
}
