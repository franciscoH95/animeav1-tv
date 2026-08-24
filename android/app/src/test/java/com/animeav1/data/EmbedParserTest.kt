package com.animeav1.data

import com.animeav1.data.model.AudioTrack
import com.animeav1.data.model.EmbedServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Se ejecuta contra una captura real de `animeav1.com/media/dandadan/1/__data.json`, un episodio
 * que trae las DOS pistas y, en SUB, la lista larga de servidores (incluidos los cifrados).
 */
class EmbedParserTest {

    private val episode: JSONObject =
        SvelteKitDecoder.decode(fixture("episodio__data.json"), "episode")!!

    private val parsed = EmbedParser.parse(episode)

    @Test
    fun `devuelve las dos pistas, subtitulado primero`() {
        val tracks = parsed.map { it.audio }.distinct()
        assertEquals(listOf(AudioTrack.SUB, AudioTrack.DUB), tracks)
    }

    /** El bug que motivó el cambio: el doblaje existía en el sitio y la app no lo ofrecía nunca. */
    @Test
    fun `el doblaje llega con su propia URL, distinta de la del subtitulado`() {
        val subHls = parsed.first { it.audio == AudioTrack.SUB && it.server == "HLS" }
        val dubHls = parsed.first { it.audio == AudioTrack.DUB && it.server == "HLS" }
        assertTrue("el doblaje reusó la URL del subtitulado", subHls.url != dubHls.url)
        assertTrue(subHls.url.startsWith("https://player.zilla-networks.com/play/"))
        assertTrue(dubHls.url.startsWith("https://player.zilla-networks.com/play/"))
    }

    /**
     * Solo deben sobrevivir los servidores de los que la app sabe sacar una URL reproducible.
     * El fixture trae los 10 que ofrece el sitio; se comprobó contra el sitio real que únicamente
     * HLS, MP4Upload y YourUpload resuelven (ver el KDoc de [EmbedParser.UNSUPPORTED_SERVERS]).
     */
    @Test
    fun `solo deja pasar los servidores de los que se puede extraer el video`() {
        assertEquals(
            listOf("HLS", "MP4Upload", "YourUpload"),
            parsed.filter { it.audio == AudioTrack.SUB }.map { it.server }
        )
        assertEquals(
            listOf("HLS", "MP4Upload"),
            parsed.filter { it.audio == AudioTrack.DUB }.map { it.server }
        )
    }

    /** Regresión: cada uno de estos estuvo llegando a la UI para acabar en "No se pudo cargar X". */
    @Test
    fun `descarta uno por uno los servidores no reproducibles`() {
        val descartados = listOf(
            "Mega" to "https://mega.nz/embed/rNY2RDBI#xl0",   // cifrado con JS de cliente
            "UPNShare" to "https://animeav1.uns.bio/#ikuzry", // idem
            "TeraBox" to "https://terabox.com/sharing/embed?surl=x",
            "DoodStream" to "https://dooodster.com/e/rhvyfowrkup8",
            "Netu" to "https://hqq.ac/e/dnNMcFlnRGtP",
            "VidHide" to "https://ryderjet.com/embed/e5kvdxcscdyl",
            "StreamTape" to "https://streamtape.com/e/xZldR2Pl3bCkGDG/"
        )
        for ((server, url) in descartados) {
            assertTrue(
                "$server debería estar filtrado",
                EmbedParser.isUnsupported(EmbedServer(server, url))
            )
        }
    }

    @Test
    fun `no descarta los que si funcionan`() {
        val soportados = listOf(
            "HLS" to "https://player.zilla-networks.com/play/aced41de84f231b5095a124e19c63f9c",
            "MP4Upload" to "https://www.mp4upload.com/embed-nzl6vpv2j8fv.html",
            "YourUpload" to "https://www.yourupload.com/embed/H4dQly801Rou"
        )
        for ((server, url) in soportados) {
            assertFalse(
                "$server NO debería estar filtrado",
                EmbedParser.isUnsupported(EmbedServer(server, url))
            )
        }
    }

    /**
     * La lista negra va por nombre exacto justamente para no arrastrar falsos positivos: la URL
     * real de YourUpload redirige a `vidcache.net`, que contiene "net" pero no es Netu.
     */
    @Test
    fun `no filtra por subcadena accidental en la url`() {
        assertFalse(EmbedParser.isUnsupported(EmbedServer("Otro", "https://vidcache.net:8161/a1/video.mp4")))
        assertFalse(EmbedParser.isUnsupported(EmbedServer("Omega", "https://ejemplo.com/v.mp4")))
    }

    /** Un servidor NUEVO que el sitio empiece a ofrecer debe aparecer, no esconderse por defecto. */
    @Test
    fun `deja pasar un servidor desconocido`() {
        assertFalse(EmbedParser.isUnsupported(EmbedServer("ServidorNuevo", "https://nuevo.example/e/1")))
    }

    @Test
    fun `acepta las claves de pista en minusculas`() {
        val lower = JSONObject(
            """{"embeds":{"sub":[{"server":"HLS","url":"https://x/1"}],
                          "dub":[{"server":"HLS","url":"https://x/2"}]}}"""
        )
        val out = EmbedParser.parse(lower)
        assertEquals(listOf(AudioTrack.SUB, AudioTrack.DUB), out.map { it.audio })
    }

    @Test
    fun `tolera embeds anidados bajo episode`() {
        val nested = JSONObject(
            """{"episode":{"embeds":{"DUB":[{"server":"HLS","url":"https://x/1"}]}}}"""
        )
        assertEquals(1, EmbedParser.parse(nested).size)
        assertEquals(AudioTrack.DUB, EmbedParser.parse(nested).first().audio)
    }

    @Test
    fun `descarta entradas sin url en vez de propagar una vacia`() {
        val partial = JSONObject(
            """{"embeds":{"SUB":[{"server":"Roto"},{"server":"HLS","url":"https://x/1"}]}}"""
        )
        val out = EmbedParser.parse(partial)
        assertEquals(1, out.size)
        assertEquals("HLS", out.first().server)
    }

    @Test
    fun `lista vacia si el episodio no trae embeds`() {
        assertTrue(EmbedParser.parse(JSONObject("""{"media":{}}""")).isEmpty())
        assertTrue(EmbedParser.parse(JSONObject("""{"embeds":{}}""")).isEmpty())
    }
}
