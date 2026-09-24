package com.animeav1.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voe, contra las dos páginas reales de dandadan ep.1 SUB (2026-09-24):
 *
 * - `voe-embed.html` — el stub de `voe.sx/e/bnpyhzir3pee`, tal cual.
 * - `voe-player.html` — la página real a la que redirige. Recortada (se quitó el script de anuncios
 *   del final, 108 KB) y con el payload **re-codificado con el mismo esquema** tras sustituir el
 *   prefijo de IP y el ASN del que la capturó, que Voe mete en la URL del vídeo y en un comentario
 *   HTML. El resto del payload es el original.
 */
class VoeParserTest {

    private val stub = fixture("voe-embed.html")
    private val player = fixture("voe-player.html")

    @Test
    fun `reconoce los embeds de voe y nada mas`() {
        assertTrue(VoeParser.handles("https://voe.sx/e/bnpyhzir3pee"))
        assertFalse(VoeParser.handles("https://jamesbornmain.com/e/bnpyhzir3pee"))
        assertFalse(VoeParser.handles("https://www.mp4upload.com/embed-x.html"))
        assertFalse(VoeParser.handles("https://notvoe.sx.example.com/e/1"))
        assertFalse(VoeParser.handles("no es una url"))
    }

    @Test
    fun `el stub redirige a la pagina real por javascript`() {
        assertEquals("https://jamesbornmain.com/e/bnpyhzir3pee", VoeParser.redirectFrom(stub))
    }

    /** El stub no lleva vídeo: si se leyera como página, no debe inventarse uno. */
    @Test
    fun `el stub no tiene stream`() {
        assertNull(VoeParser.streamFrom(stub))
    }

    @Test
    fun `saca el hls de la pagina real`() {
        val url = VoeParser.streamFrom(player)
        assertNotNull(url)
        assertTrue("no es el master.m3u8: $url", url!!.contains("/master.m3u8?"))
        // El reproductor decide el MIME por esto (la URL de Zilla no lleva extensión).
        assertTrue("m3u8" in url)
    }

    /**
     * EL test de este fichero. La página real trae un Big Buck Bunny de 10 s que se reproduce sin
     * errores, y el scraper genérico lo encuentra antes que nada. Si alguien "simplifica" Voe
     * haciéndolo pasar por `directUrlFrom`, la app pondría otro vídeo y lo daría por visto.
     */
    @Test
    fun `nunca devuelve el video señuelo de la pagina`() {
        assertTrue(
            "el fixture ya no contiene la trampa que motivó este test",
            StreamUrlParser.directUrlFrom(player)!!.contains("test-videos.co.uk")
        )
        assertFalse(VoeParser.streamFrom(player)!!.contains("test-videos.co.uk"))
    }

    /**
     * Aunque la página llegue por el camino GENÉRICO (el sitio enlaza un día el dominio rotatorio
     * en vez de voe.sx), se reconoce por el contenido y el señuelo tampoco pasa.
     */
    @Test
    fun `el camino generico tampoco devuelve el señuelo de una pagina de voe`() {
        assertTrue(VoeParser.isPlayerPage(player))
        assertFalse(VoeParser.isPlayerPage(stub))
        assertEquals(VoeParser.streamFrom(player), StreamUrlParser.streamFromEmbedPage(player))
    }

    /** Y el camino genérico de siempre sigue igual para lo que no es Voe. */
    @Test
    fun `el camino generico sigue sacando el mp4 de mp4upload`() {
        val mp4upload = fixture("mp4upload-embed.html")
        assertFalse(VoeParser.isPlayerPage(mp4upload))
        assertEquals(StreamUrlParser.directUrlFrom(mp4upload), StreamUrlParser.streamFromEmbedPage(mp4upload))
    }

    @Test
    fun `decodifica la configuracion entera`() {
        val config = VoeParser.decodePayload(player)!!
        assertEquals("bnpyhzir3pee", config.getString("file_code"))
        assertEquals("1_1_SUB.mp4", config.getString("title"))
        assertTrue(config.getJSONArray("fallback").getJSONObject(0).getString("file").contains(".mp4"))
    }

    /** Si Voe cambia la ofuscación, null (y el reproductor pasa a la siguiente fuente al momento). */
    @Test
    fun `un payload que no se entiende da null en vez de reventar`() {
        val broken = """<script type="application/json">["no es lo que parece"]</script>"""
        assertNull(VoeParser.decodePayload(broken))
        assertNull(VoeParser.streamFrom(broken))
        assertNull(VoeParser.streamFrom("<html></html>"))
        assertNull(VoeParser.redirectFrom("<html></html>"))
    }

    /** Sin `source`, el mp4 de `fallback` es la segunda opción, antes que `direct_access_url`. */
    @Test
    fun `sin hls cae al mp4 de fallback`() {
        val config = VoeParser.decodePayload(player)!!
        config.remove("source")
        val page = """<script type="application/json">["${VoeTestCodec.encode(config.toString())}"]</script>"""
        assertEquals(
            config.getJSONArray("fallback").getJSONObject(0).getString("file"),
            VoeParser.streamFrom(page)
        )
    }
}

/** El esquema de Voe al revés, solo para fabricar variantes del fixture en los tests. */
private object VoeTestCodec {
    fun encode(json: String): String {
        val b64 = java.util.Base64.getEncoder()
        val first = b64.encodeToString(json.toByteArray(Charsets.UTF_8))
        val shifted = first.reversed().map { it + 3 }.joinToString("")
        val second = b64.encodeToString(shifted.toByteArray(Charsets.ISO_8859_1))
        return second.map { c ->
            when (c) {
                in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
                in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
                else -> c
            }
        }.joinToString("")
    }
}
