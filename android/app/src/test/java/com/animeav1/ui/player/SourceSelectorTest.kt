package com.animeav1.ui.player

import com.animeav1.data.model.AudioTrack
import com.animeav1.data.model.EmbedServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSelectorTest {

    private val hlsSub = EmbedServer("HLS", "https://player.zilla-networks.com/play/sub", AudioTrack.SUB)
    private val voeSub = EmbedServer("Voe", "https://voe.sx/e/sub", AudioTrack.SUB)
    private val yourSub = EmbedServer("YourUpload", "https://www.yourupload.com/embed/sub", AudioTrack.SUB)
    private val voeDub = EmbedServer("Voe", "https://voe.sx/e/dub", AudioTrack.DUB)
    private val yourDub = EmbedServer("YourUpload", "https://www.yourupload.com/embed/dub", AudioTrack.DUB)
    private val mp4Sub = EmbedServer("MP4Upload", "https://www.mp4upload.com/embed-x.html", AudioTrack.SUB)
    private val byseSub = EmbedServer("Byse", "https://byselapuix.com/e/x", AudioTrack.SUB)
    private val all = listOf(hlsSub, voeSub, yourSub, voeDub, yourDub)

    private val penalties = mutableMapOf<String, Long>()

    private fun selector(
        embeds: List<EmbedServer> = all,
        av1Hardware: Boolean = true
    ) = SourceSelector({ penalties[it] ?: 0L }, { av1Hardware }).also { it.embeds = embeds }

    private fun SourceSelector.playsPick(embed: EmbedServer) {
        onSelected(embed, fromUser = true)
        onStreamResolved(embed)
    }

    // ── Arranque del episodio ─────────────────────────────────────────────────────────────────

    @Test
    fun `sin preferencia arranca en la primera pista y sigue el orden del sitio`() {
        assertEquals(hlsSub, selector().pickDefault(all))
    }

    @Test
    fun `la preferencia guardada manda sobre los extras del intent`() {
        val s = selector().apply {
            startFromIntent("HLS", "SUB", fromUser = false)
            applySaved(audio = "DUB", server = "YourUpload")
        }
        assertEquals(yourDub, s.pickDefault(all))
    }

    @Test
    fun `una eleccion traida manda sobre la preferencia guardada`() {
        val s = selector().apply {
            startFromIntent("Voe", "DUB", fromUser = true)
            applySaved(audio = "SUB", server = "HLS")
        }
        assertEquals(voeDub, s.pickDefault(all))
    }

    @Test
    fun `una eleccion traida sin servidor usa el de la tabla solo si es de su pista`() {
        val sameTrack = selector().apply {
            startFromIntent(null, "DUB", fromUser = true)
            applySaved(audio = "DUB", server = "YourUpload")
        }
        val otherTrack = selector().apply {
            startFromIntent(null, "DUB", fromUser = true)
            applySaved(audio = "SUB", server = "YourUpload")
        }
        assertEquals(yourDub, sameTrack.pickDefault(all))
        assertEquals(voeDub, otherTrack.pickDefault(all))
    }

    @Test
    fun `lo que viene del intent sin marca de usuario no se trae como eleccion`() {
        val s = selector().apply { startFromIntent("Voe", "DUB", fromUser = false) }
        assertNull(s.carried)
    }

    @Test
    fun `al recrear la Activity lo restaurado pisa a los extras`() {
        val s = selector().apply {
            startFromIntent("Voe", "DUB", fromUser = true)
            restoreCarried("HLS", "SUB")
        }
        assertEquals(SourceSelector.CarriedPref("HLS", AudioTrack.SUB), s.carried)
    }

    @Test
    fun `al recrear la Activity sin nada guardado no revive los extras`() {
        val s = selector().apply {
            startFromIntent("Voe", "DUB", fromUser = true)
            restoreCarried(null, null)
        }
        assertNull(s.carried)
    }

    @Test
    fun `la pista se decide antes de mirar los fallos`() {
        penalties[hlsSub.url] = 1_000
        penalties[voeSub.url] = 1_000
        penalties[yourSub.url] = 1_000
        assertEquals(AudioTrack.SUB, selector().pickDefault(all).audio)
    }

    @Test
    fun `la pista preferida que el episodio no trae cae en la primera`() {
        val subOnly = listOf(hlsSub, voeSub)
        val s = selector(subOnly).apply { startFromIntent(null, "DUB", fromUser = false) }
        assertEquals(hlsSub, s.pickDefault(subOnly))
    }

    @Test
    fun `una preferida que ha fallado hace poco cede ante una viva`() {
        penalties[yourSub.url] = 1_000
        val s = selector().apply { startFromIntent("YourUpload", "SUB", fromUser = false) }
        assertEquals(hlsSub, s.pickDefault(all))
    }

    @Test
    fun `si han fallado todas la preferida sigue siendo la mejor apuesta`() {
        all.forEach { penalties[it.url] = 5_000 }
        penalties[hlsSub.url] = 9_000
        val s = selector().apply { startFromIntent("YourUpload", "SUB", fromUser = false) }
        assertEquals(yourSub, s.pickDefault(all))
    }

    @Test
    fun `el servidor preferido se compara sin distinguir mayusculas`() {
        val s = selector().apply { startFromIntent("voe", "SUB", fromUser = false) }
        assertEquals(voeSub, s.pickDefault(all))
    }

    @Test
    fun `Byse va delante del resto y MP4Upload solo con AV1 por hardware`() {
        val embeds = listOf(mp4Sub, voeSub, byseSub)
        assertEquals(byseSub, selector(embeds).pickDefault(embeds))
        val noByse = listOf(voeSub, mp4Sub)
        assertEquals(mp4Sub, selector(noByse, av1Hardware = true).pickDefault(noByse))
        assertEquals(voeSub, selector(noByse, av1Hardware = false).pickDefault(noByse))
    }

    // ── Fallback ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `la siguiente fuente es de la misma pista y sin probar`() {
        val s = selector().apply { onSelected(voeDub, fromUser = false) }
        assertEquals(yourDub, s.nextUntried(voeDub))
    }

    @Test
    fun `una pista agotada no salta a la otra`() {
        val s = selector().apply {
            onSelected(voeDub, fromUser = false)
            onSelected(yourDub, fromUser = false)
        }
        assertNull(s.nextUntried(yourDub))
    }

    @Test
    fun `lo que ha fallado hace poco va al final de la cadena`() {
        penalties[voeSub.url] = 1_000
        val s = selector().apply { onSelected(hlsSub, fromUser = false) }
        assertEquals(yourSub, s.nextUntried(hlsSub))
    }

    @Test
    fun `antes que una desconocida vuelve a la que estaba sonando`() {
        val s = selector().apply {
            onSelected(hlsSub, fromUser = false)
            onSourceWorking(hlsSub)
            onSelected(yourSub, fromUser = true)
        }
        assertEquals(hlsSub, s.nextUntried(yourSub))
    }

    @Test
    fun `solo se vuelve UNA vez a la que estaba sonando`() {
        val s = selector().apply {
            onSelected(hlsSub, fromUser = false)
            onSourceWorking(hlsSub)
            onSelected(yourSub, fromUser = true)
        }
        assertEquals(hlsSub, s.takeNext(yourSub))
        assertEquals(voeSub, s.nextUntried(yourSub))
    }

    @Test
    fun `la que falla deja de ser a la que volver`() {
        val s = selector().apply {
            onSelected(hlsSub, fromUser = false)
            onSourceWorking(hlsSub)
            onFailed(hlsSub)
        }
        assertEquals(voeSub, s.nextUntried(hlsSub))
    }

    @Test
    fun `la que suena en otra pista no sirve de vuelta`() {
        val s = selector().apply {
            onSelected(hlsSub, fromUser = false)
            onSourceWorking(hlsSub)
            onSelected(voeDub, fromUser = true)
        }
        assertEquals(yourDub, s.nextUntried(voeDub))
    }

    @Test
    fun `reintentar olvida lo probado`() {
        val s = selector().apply {
            onSelected(voeDub, fromUser = false)
            onSelected(yourDub, fromUser = false)
            onRetry(yourDub)
        }
        assertEquals(voeDub, s.nextUntried(yourDub))
    }

    // ── Qué se guarda como preferencia ────────────────────────────────────────────────────────

    @Test
    fun `una eleccion del panel se guarda al sonar`() {
        val s = selector().apply { playsPick(voeDub) }
        assertTrue(s.onSourceWorking(voeDub))
        assertEquals(SourceSelector.CarriedPref("Voe", AudioTrack.DUB), s.carried)
    }

    @Test
    fun `una eleccion del panel se guarda una sola vez`() {
        val s = selector().apply { playsPick(voeDub) }
        s.onSourceWorking(voeDub)
        assertFalse(s.onSourceWorking(voeDub))
    }

    @Test
    fun `un pick automatico o un fallback no se guardan`() {
        val s = selector().apply {
            onSelected(voeSub, fromUser = false)
            onStreamResolved(voeSub)
        }
        assertFalse(s.onSourceWorking(voeSub))
        assertNull(s.carried)
    }

    @Test
    fun `un READY del stream viejo no consume la eleccion que aun resuelve`() {
        val s = selector().apply {
            onSelected(hlsSub, fromUser = false)
            onStreamResolved(hlsSub)
            onSelected(voeDub, fromUser = true)
        }
        assertFalse(s.onSourceWorking(hlsSub))
        s.onStreamResolved(voeDub)
        assertTrue(s.onSourceWorking(voeDub))
    }

    @Test
    fun `una eleccion traida se guarda al sonar aunque la ponga el pick por defecto`() {
        val s = selector().apply {
            startFromIntent("Voe", "DUB", fromUser = true)
            onSelected(voeDub, fromUser = false)
            onStreamResolved(voeDub)
        }
        assertTrue(s.onSourceWorking(voeDub))
    }

    @Test
    fun `olvidar el stream olvida que era una eleccion`() {
        val s = selector().apply { playsPick(voeDub) }
        s.onStreamForgotten()
        assertFalse(s.onSourceWorking(voeDub))
    }

    // ── Qué viaja al episodio siguiente ───────────────────────────────────────────────────────

    @Test
    fun `viaja la eleccion que aun resuelve`() {
        val s = selector().apply { onSelected(voeDub, fromUser = true) }
        assertEquals(SourceSelector.CarriedPref("Voe", AudioTrack.DUB), s.unsavedChoice())
    }

    @Test
    fun `viaja la que suena sin haberse guardado`() {
        val s = selector().apply { playsPick(voeDub) }
        assertEquals(SourceSelector.CarriedPref("Voe", AudioTrack.DUB), s.unsavedChoice())
    }

    @Test
    fun `una eleccion sustituida por otra no viaja`() {
        val s = selector().apply {
            onSelected(voeDub, fromUser = true)
            onSelected(yourSub, fromUser = true)
        }
        assertEquals(SourceSelector.CarriedPref("YourUpload", AudioTrack.SUB), s.unsavedChoice())
    }

    @Test
    fun `lo que trajo un fallback no viaja`() {
        val s = selector().apply {
            onSelected(voeSub, fromUser = false)
            onStreamResolved(voeSub)
        }
        assertNull(s.unsavedChoice())
    }

    @Test
    fun `una eleccion que falla al probarla no viaja`() {
        val s = selector().apply { onSelected(voeDub, fromUser = true) }
        s.onFailed(voeDub)
        assertNull(s.unsavedChoice())
    }

    @Test
    fun `la traida que falla pierde el servidor y conserva la pista`() {
        val s = selector().apply {
            startFromIntent("HLS", "DUB", fromUser = true)
        }
        val hlsDub = EmbedServer("HLS", "https://player.zilla-networks.com/play/dub", AudioTrack.DUB)
        s.onFailed(hlsDub)
        assertEquals(SourceSelector.CarriedPref(null, AudioTrack.DUB), s.unsavedChoice())
    }

    @Test
    fun `la traida sigue viajando aunque este episodio no la tenga`() {
        val s = selector().apply {
            startFromIntent("Mega", "DUB", fromUser = true)
            onSelected(voeDub, fromUser = false)
            onStreamResolved(voeDub)
        }
        assertFalse(s.onSourceWorking(voeDub))
        assertEquals(SourceSelector.CarriedPref("Mega", AudioTrack.DUB), s.unsavedChoice())
    }

    // ── Quién manda ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `insiste quien pulsa en el panel o le da a Reintentar`() {
        val clicked = selector().apply { onSelected(voeDub, fromUser = true) }
        val retried = selector().apply { onRetry(voeDub) }
        assertTrue(clicked.insisted(voeDub))
        assertTrue(retried.insisted(voeDub))
    }

    @Test
    fun `una preferencia guardada o traida no es insistir`() {
        val s = selector().apply {
            startFromIntent("Voe", "DUB", fromUser = true)
            applySaved(audio = "DUB", server = "Voe")
            onSelected(voeDub, fromUser = false)
        }
        assertFalse(s.insisted(voeDub))
    }

    @Test
    fun `reintentar no es elegir`() {
        val s = selector().apply {
            onRetry(voeDub)
            onSelected(voeDub, fromUser = false)
            onStreamResolved(voeDub)
        }
        assertFalse(s.onSourceWorking(voeDub))
    }

    @Test
    fun `la preferencia guardada cuenta como eleccion del usuario`() {
        val s = selector().apply { applySaved(audio = "DUB", server = "voe") }
        assertTrue(s.chosenByUser(voeDub))
        assertFalse(s.chosenByUser(voeSub))
        assertFalse(s.chosenByUser(null))
    }

    @Test
    fun `lo que puso la app no cuenta como eleccion del usuario`() {
        assertFalse(selector().chosenByUser(voeDub))
    }

    @Test
    fun `cuenta como eleccion del usuario la que suena elegida, la traida y la reintentada`() {
        assertTrue(selector().apply { playsPick(voeDub) }.chosenByUser(voeDub))
        assertTrue(selector().apply { startFromIntent("Voe", "DUB", fromUser = true) }.chosenByUser(voeDub))
        assertTrue(selector().apply { onRetry(voeDub) }.chosenByUser(voeDub))
    }

    @Test
    fun `la eleccion que sigue sonando viaja aunque falle la siguiente`() {
        val s = selector().apply {
            playsPick(voeDub)
            onSelected(yourSub, fromUser = true)
            onFailed(yourSub)
        }
        assertEquals(SourceSelector.CarriedPref("Voe", AudioTrack.DUB), s.unsavedChoice())
    }

    // ── Un fallo del stream viejo mientras la elección resuelve ───────────────────────────────

    @Test
    fun `el fallo del stream viejo cede ante la eleccion que resuelve`() {
        val s = selector().apply {
            onSelected(hlsSub, fromUser = false)
            onStreamResolved(hlsSub)
            onSelected(voeDub, fromUser = true)
        }
        assertEquals(voeDub, s.pickToDeferTo(hlsSub))
    }

    @Test
    fun `si falla la propia eleccion no se cede`() {
        val s = selector().apply { onSelected(voeDub, fromUser = true) }
        assertNull(s.pickToDeferTo(voeDub))
    }

    @Test
    fun `sin eleccion pendiente no se cede`() {
        assertNull(selector().pickToDeferTo(hlsSub))
    }

    @Test
    fun `abandonar la eleccion la saca de lo pendiente sin tocar la que suena`() {
        val s = selector().apply {
            onSelected(hlsSub, fromUser = false)
            onSourceWorking(hlsSub)
            onSelected(hlsSub, fromUser = true)
        }
        s.abandonPick(hlsSub)
        assertFalse(s.hasPendingPick)
        assertEquals(hlsSub, s.nextUntried(voeSub))
    }

    @Test
    fun `restaurar la seleccion devuelve la que sigue sonando`() {
        val s = selector().apply {
            onSelected(hlsSub, fromUser = false)
            onSelected(voeDub, fromUser = true)
            restoreSelection(hlsSub)
        }
        assertEquals(hlsSub, s.selected)
    }
}
