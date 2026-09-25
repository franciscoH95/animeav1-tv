package com.animeav1.data

import okio.ByteString.Companion.decodeBase64
import org.json.JSONObject
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Mitad PURA de Byse (`byselapuix.com`): qué URLs pedir y cómo leer lo que contestan. La red y el
 * orden de las llamadas viven en `AnimeRepository.resolveByse`; la prueba de trabajo en [BysePow].
 *
 * El embed es el cascarón de una SPA sin ninguna URL. Su web hace, contra el MISMO host del embed:
 * 1. `POST /api/videos/<code>/embed/captcha` `{}` → reto (`pow_nonce`, `pow_difficulty`, `pow_token`).
 * 2. Resolver el reto ([BysePow]).
 * 3. `POST …/embed/captcha/verify` `{pow_token, solution}` → `{status:"ok", token, expires_in:1800}`.
 *    Una solución mala NO es un error HTTP: 200 con `{status:"error", reason:"pow_failed"}`.
 * 4. `POST …/embed/playback` con `X-Captcha-Token` y `{"fingerprint":{}}` → `playback` cifrado.
 *    Sin la clave `fingerprint` da 405, y su contenido hoy no se comprueba: su JS trae un cliente de
 *    atestación del aparato que aún no exigen. Si lo exigen, esto deja de funcionar (y la app cae a
 *    Voe). Sin token o caducado: 428 `captcha_required`.
 * 5. Descifrar ([streamFrom]) → `sources[0].url`, un `master.m3u8` 1080p H.264 High de 8 bits.
 *
 * ⚠️ El token del paso 3 vale 30 min y para CUALQUIER vídeo (su web lo guarda por "modo", no por
 * vídeo): la prueba de trabajo se hace una vez cada media hora, no una por episodio.
 * ⚠️ La URL del CDN va atada a la CLASE de User-Agent (escritorio frente a Android/iOS) con la que se
 * pidió el `playback`: con otra clase da 404. Ver `AnimeRepository.userAgentFor`.
 */
internal object ByseParser {

    private val HOSTS = listOf("byselapuix.com")

    /** Solo el host: el nombre del servidor no llega hasta aquí, y el scraper genérico no sirve. */
    fun handles(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /** `https://byselapuix.com/e/<code>` → `<code>`. */
    fun codeFrom(embedUrl: String): String? {
        val path = runCatching { URI(embedUrl).path }.getOrNull() ?: return null
        return Regex("""/(?:e|d|embed|v)/([A-Za-z0-9]+)""").find(path)?.groupValues?.get(1)
    }

    /** Base de la API para ese embed (mismo host), o null si no es un embed de Byse reconocible. */
    fun apiBase(embedUrl: String): String? {
        if (!handles(embedUrl)) return null
        val host = hostOf(embedUrl) ?: return null
        val code = codeFrom(embedUrl) ?: return null
        return "https://$host/api/videos/$code/embed"
    }

    data class Challenge(val nonce: String, val difficulty: Int, val token: String)

    /**
     * El reto del paso 1. Una dificultad fuera de lo razonable se rechaza en vez de intentarla: por
     * encima de 32 bits no se resolvería en ningún aparato (2^32 hashes de media).
     */
    fun challengeFrom(json: String): Challenge? = runCatching {
        val o = JSONObject(json)
        val nonce = o.optString("pow_nonce")
        val token = o.optString("pow_token")
        val difficulty = o.optInt("pow_difficulty", -1)
        if (nonce.isBlank() || token.isBlank() || difficulty !in 0..32) null
        else Challenge(nonce, difficulty, token)
    }.getOrNull()

    /** El token del paso 3 y cuántos segundos vale, o null si la solución no se aceptó. */
    fun captchaTokenFrom(json: String): Pair<String, Long>? = runCatching {
        val o = JSONObject(json)
        val token = o.optString("token")
        if (o.optString("status") != "ok" || token.isBlank()) null
        else token to o.optLong("expires_in", 1800)
    }.getOrNull()

    fun verifyBody(challenge: Challenge, solution: Long): String =
        JSONObject().put("pow_token", challenge.token).put("solution", solution.toString()).toString()

    /**
     * El cuerpo del paso 4. ⚠️ La clave `fingerprint` es obligatoria (sin ella, 405) aunque vaya
     * vacía: es donde su web pondría el resultado de la atestación del aparato.
     */
    const val PLAYBACK_BODY = """{"fingerprint":{}}"""

    /**
     * Descifra la respuesta del paso 4 y devuelve la URL del stream.
     *
     * `playback` = `{iv, payload, key_parts[30], version}`, AES-256-GCM, `payload` = cifrado ‖
     * etiqueta de 16 bytes, todo en base64url. La clave son DOS de los 30 trozos, en las posiciones
     * (desde 1) `v` y `31 − v`, con `v = version` (1..20) y en ese orden; los otros 28 son señuelos
     * de 24 bytes. La versión cambia en cada respuesta. Si el reparto cambia, GCM lo delata (la
     * etiqueta no casa) y se prueba el plan B: los trozos de 16 bytes, en los dos órdenes.
     */
    fun streamFrom(playbackJson: String): String? {
        val pb = runCatching { JSONObject(playbackJson).getJSONObject("playback") }.getOrNull() ?: return null
        val parts = pb.optJSONArray("key_parts") ?: return null
        val raw = (0 until parts.length()).map { parts.optString(it).decodeBase64Url() }
        val iv = pb.optString("iv").decodeBase64Url() ?: return null
        val payload = pb.optString("payload").decodeBase64Url() ?: return null

        val plain = keyCandidates(raw, pb.optString("version")).firstNotNullOfOrNull { key ->
            decrypt(key, iv, payload)
        } ?: return null
        return urlFrom(plain)
    }

    /** Claves a probar, la de verdad primero. */
    private fun keyCandidates(parts: List<ByteArray?>, version: String): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        val v = version.trim().toIntOrNull()
        if (v != null && v in 1..20 && 31 - v <= parts.size) {
            val a = parts.getOrNull(v - 1)
            val b = parts.getOrNull(31 - v - 1)
            if (a != null && b != null) out += a + b
        }
        val halves = parts.filterNotNull().filter { it.size == 16 }
        if (halves.size == 2) {
            out += halves[0] + halves[1]
            out += halves[1] + halves[0]
        }
        return out
    }

    private fun decrypt(key: ByteArray, iv: ByteArray, payload: ByteArray): String? = runCatching {
        if (key.size !in setOf(16, 24, 32)) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        String(cipher.doFinal(payload), Charsets.UTF_8)
    }.getOrNull()

    /** De `{sources:[{url, mime_type, …}], …}`: la primera HLS, y si no la primera con URL. */
    private fun urlFrom(plain: String): String? = runCatching {
        val sources = JSONObject(plain).optJSONArray("sources") ?: return null
        val all = (0 until sources.length()).mapNotNull { sources.optJSONObject(it) }
            .filter { it.optString("url").startsWith("http") }
        val hls = all.firstOrNull { "mpegurl" in it.optString("mime_type") || "m3u8" in it.optString("url") }
        (hls ?: all.firstOrNull())?.optString("url")
    }.getOrNull()

    /**
     * User-Agent de clase Android para Byse: a esa clase le pide una prueba de trabajo 4 bits más
     * fácil (16 veces menos trabajo) que a un navegador de escritorio, y la app ES un aparato
     * Android. Con la versión y el modelo reales, sin caracteres que OkHttp rechace en una cabecera.
     */
    fun androidUserAgent(release: String, model: String): String {
        fun clean(s: String) = s.filter { it in ' '..'~' && it != ';' && it != '(' && it != ')' }.trim()
        val r = clean(release).ifBlank { "10" }
        val m = clean(model).ifBlank { "Android TV" }
        return "Mozilla/5.0 (Linux; Android $r; $m) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"
    }

    private fun String.decodeBase64Url(): ByteArray? = decodeBase64()?.toByteArray()

    private fun hostOf(url: String): String? = runCatching { URI(url).host?.lowercase() }.getOrNull()
}
