package com.animeav1.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ByseParserTest {

    private fun hex(words: IntArray) = words.joinToString(",") { "%08x".format(it) }

    // ── Prueba de trabajo ─────────────────────────────────────────────────────────────────────

    /**
     * Vectores sacados del JS ORIGINAL de Byse (`gr()` de assets/pow-DEJGtdh2.js, ejecutado en
     * Node). Si esto falla, el port ya no es su función y el servidor contestaría `pow_failed`.
     */
    @Test
    fun `el hash coincide con el del JS original`() {
        val vectors = listOf(
            "f13fba163716aaa76d752b3379a104b5:0" to "ddde18ba,068a1291,a15ff712,759f6f29,689259a3,73b62396,f9e7e154,ba73cb18",
            "f13fba163716aaa76d752b3379a104b5:232" to "00719035,4f05190e,0cf1ca82,6212dc4d,90dbbf79,bc1f146d,3a45d5b9,d27927f9",
            "00000000000000000000000000000000:358" to "007b4645,d325ee25,6cca0759,15030495,b0bea3e7,7ffe8b98,c213a945,75957aab"
        )
        for ((input, expected) in vectors) {
            assertEquals(input, expected, hex(BysePow.hash(BysePow.latin1(input))))
        }
        assertEquals(9, BysePow.leadingZeroBits(BysePow.hash(BysePow.latin1("f13fba163716aaa76d752b3379a104b5:232"))))
    }

    /** Dos retos REALES del servidor (dificultad 16) y la solución mínima que aceptó. */
    @Test
    fun `con un hilo encuentra la solucion minima de retos reales`() {
        assertEquals(7314L, BysePow.solve("0220fdbb448279d844261f4b9ee9ec98", 16, threads = 1) { false })
        assertEquals(12755L, BysePow.solve("902867e075e36aebd0d90f88de128dd0", 16, threads = 1) { false })
    }

    /** Repartida entre hilos puede salir otra solución, no la mínima: el servidor acepta cualquiera. */
    @Test
    fun `con varios hilos la solucion cumple la dificultad`() {
        val nonce = "d390d33896ed533ffc4bd6d1a23b04e7"
        val s = BysePow.solve(nonce, 14, threads = 4) { false }
        assertNotNull(s)
        assertTrue(BysePow.leadingZeroBits(BysePow.hash(BysePow.latin1("$nonce:$s"))) >= 14)
    }

    @Test
    fun `se rinde cuando se le cancela`() {
        val start = System.nanoTime()
        assertNull(BysePow.solve("d390d33896ed533ffc4bd6d1a23b04e7", 32, threads = 2) { true })
        assertTrue("debe parar enseguida", System.nanoTime() - start < 2_000_000_000L)
    }

    // ── URLs y respuestas de la API ───────────────────────────────────────────────────────────

    @Test
    fun `reconoce el embed y arma la api en el mismo host`() {
        assertTrue(ByseParser.handles("https://byselapuix.com/e/uftud7u67ay3"))
        assertFalse(ByseParser.handles("https://voe.sx/e/bnpyhzir3pee"))
        assertFalse(ByseParser.handles("https://ejemplo.com/?ref=byselapuix.com"))
        assertEquals("uftud7u67ay3", ByseParser.codeFrom("https://byselapuix.com/e/uftud7u67ay3"))
        assertEquals(
            "https://byselapuix.com/api/videos/uftud7u67ay3/embed",
            ByseParser.apiBase("https://byselapuix.com/e/uftud7u67ay3")
        )
        assertNull(ByseParser.apiBase("https://byselapuix.com/"))
    }

    @Test
    fun `lee el reto y rechaza uno imposible`() {
        val c = ByseParser.challengeFrom(
            """{"pow_nonce":"f13fba163716aaa76d752b3379a104b5","pow_difficulty":16,"pow_token":"tok","expires_in":1800,"algorithm":"sha256-leading-zero-bits"}"""
        )
        assertEquals(ByseParser.Challenge("f13fba163716aaa76d752b3379a104b5", 16, "tok"), c)
        assertNull(ByseParser.challengeFrom("""{"pow_nonce":"x","pow_difficulty":40,"pow_token":"t"}"""))
        assertNull(ByseParser.challengeFrom("""{"error":"x"}"""))
        assertNull(ByseParser.challengeFrom("no es json"))
    }

    /** Una solución mala no es un error HTTP: 200 con status "error". */
    @Test
    fun `el token solo sale de un verify aceptado`() {
        assertEquals("abc" to 1800L, ByseParser.captchaTokenFrom("""{"status":"ok","token":"abc","expires_in":1800}"""))
        assertNull(ByseParser.captchaTokenFrom("""{"status":"error","reason":"pow_failed"}"""))
    }

    @Test
    fun `el cuerpo del playback lleva la clave fingerprint`() {
        assertTrue(JSONObject(ByseParser.PLAYBACK_BODY).has("fingerprint"))
    }

    // ── Descifrado ────────────────────────────────────────────────────────────────────────────

    /**
     * Respuesta REAL de `playback` (30 trozos de clave, 28 señuelos de 24 bytes, versión 10),
     * descifrada, saneada y vuelta a cifrar con la misma clave: la estructura es la del servidor.
     */
    @Test
    fun `descifra un playback real y saca el master m3u8`() {
        val json = javaClass.classLoader!!.getResource("byse-playback.json")!!.readText()
        assertEquals(
            "https://edge1-waw-sprintcdn.r66nv9ed.com/hls2/08/12080/yzkkh96luuwc_x/master.m3u8" +
                "?t=5yXOV8P5r1SsgZ2iXCKti2nM9LGkrdyNk9XVIR6E7pM&s=1790306861&e=10800&f=60400453&srv=1050&asn=&sp=5500&p=0",
            ByseParser.streamFrom(json)
        )
    }

    private val b64u = Base64.getUrlEncoder().withoutPadding()

    /**
     * Un playback como los del servidor, con la clave en las posiciones (desde 1) [at]. Con
     * [decoy16At], ese señuelo mide 16 bytes como los trozos buenos, y el plan B (probar los dos
     * trozos de 16 bytes) ya no puede decidir: solo acierta la regla de las posiciones.
     */
    private fun playback(version: String, at: Pair<Int, Int>, tamper: Boolean = false, decoy16At: Int? = null): String {
        val key = ByteArray(32) { (it * 7 + 3).toByte() }
        val iv = ByteArray(12) { (it + 1).toByte() }
        val plain = """{"sources":[{"label":"1080p","mime_type":"application/vnd.apple.mpegurl","url":"https://cdn.example/x/master.m3u8"}]}"""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val payload = cipher.doFinal(plain.toByteArray())
        if (tamper) payload[0] = (payload[0].toInt() xor 1).toByte()
        val parts = JSONArray()
        for (i in 1..30) parts.put(
            when (i) {
                at.first -> b64u.encodeToString(key.copyOfRange(0, 16))
                at.second -> b64u.encodeToString(key.copyOfRange(16, 32))
                decoy16At -> b64u.encodeToString(ByteArray(16) { (i * 3 + it).toByte() })
                else -> b64u.encodeToString(ByteArray(24) { (i + it).toByte() })   // señuelo
            }
        )
        return JSONObject().put(
            "playback", JSONObject()
                .put("algorithm", "AES-256-GCM").put("version", version)
                .put("iv", b64u.encodeToString(iv)).put("payload", b64u.encodeToString(payload))
                .put("key_parts", parts)
        ).toString()
    }

    /** Con un señuelo de 16 bytes el plan B no decide: esto prueba la regla de verdad. */
    @Test
    fun `la clave son los trozos v y 31 menos v`() {
        assertEquals("https://cdn.example/x/master.m3u8", ByseParser.streamFrom(playback("7", 7 to 24, decoy16At = 12)))
        assertEquals("https://cdn.example/x/master.m3u8", ByseParser.streamFrom(playback("20", 20 to 11, decoy16At = 2)))
        // Y en ese orden: con los trozos cambiados de sitio no hay clave.
        assertNull(ByseParser.streamFrom(playback("7", 24 to 7, decoy16At = 12)))
    }

    /** Si cambian el reparto, la etiqueta GCM lo delata y se prueban los dos trozos de 16 bytes. */
    @Test
    fun `si cambia el reparto de la clave prueba los trozos de 16 bytes`() {
        assertEquals("https://cdn.example/x/master.m3u8", ByseParser.streamFrom(playback("7", 3 to 28)))
        assertEquals("https://cdn.example/x/master.m3u8", ByseParser.streamFrom(playback("99", 28 to 3)))
    }

    @Test
    fun `un payload manipulado no se da por bueno`() {
        assertNull(ByseParser.streamFrom(playback("7", 7 to 24, tamper = true)))
        assertNull(ByseParser.streamFrom("""{"error":"captcha_required"}"""))
    }

    // ── User-Agent ────────────────────────────────────────────────────────────────────────────

    /** Clase Android (la que Byse trata con una prueba 16 veces más fácil) y sin nada que OkHttp rechace. */
    @Test
    fun `el user agent es de clase android y valido como cabecera`() {
        val ua = ByseParser.androidUserAgent("10", "2021/22 Philips UHD Android TV")
        assertEquals(
            "Mozilla/5.0 (Linux; Android 10; 2021/22 Philips UHD Android TV) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            ua
        )
        val raro = ByseParser.androidUserAgent("", "Télé (4K); ñ")
        assertTrue(raro.all { it in ' '..'~' })
        assertTrue("Android 10" in raro)
    }
}
