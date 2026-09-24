package com.animeav1.data

import okio.ByteString.Companion.decodeBase64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Mitad PURA de la resolución de Voe (`voe.sx`) — sin red ni Android, para poder testearla contra
 * páginas reales capturadas, igual que [StreamUrlParser] y [EmbedParser].
 *
 * Voe no deja la URL del vídeo en texto plano. Son dos páginas:
 *
 * 1. `voe.sx/e/<id>` es un **stub** de 745 bytes que redirige con JavaScript
 *    (`window.location.href = 'https://<dominio rotatorio>/e/<id>'`), sin un 3xx de HTTP, así que
 *    OkHttp se queda en el stub. De ahí sale [redirectFrom].
 * 2. La página real lleva la configuración del reproductor ofuscada en
 *    `<script type="application/json">["…"]</script>`. De ahí sale [streamFrom].
 *
 * ⚠️ **La página real trae un SEÑUELO**: `var source='https://test-videos.co.uk/…/Big_Buck_Bunny…mp4'`,
 * un clip AV1 de 10 s que SÍ se reproduce. Pasarle esa página a [StreamUrlParser.directUrlFrom] lo
 * devuelve a él: la app reproduciría el vídeo equivocado sin ningún error, y a los 10 s el
 * `STATE_ENDED` lo daría por visto. Por eso Voe **nunca** pasa por el scraper genérico: todo lo que
 * sale de aquí sale del payload decodificado, y si el payload no se entiende se devuelve null.
 */
internal object VoeParser {

    /** Hosts de los embeds que publica el sitio. El dominio de la página real rota y no se fija. */
    private val EMBED_HOSTS = listOf("voe.sx")

    private val REDIRECT = Regex("""window\.location\.href\s*=\s*['"](https?://[^'"\s]+)['"]""")

    private val PAYLOAD = Regex(
        """<script type="application/json">\s*(\[.*?])\s*</script>""",
        RegexOption.DOT_MATCHES_ALL
    )

    /** Relleno que Voe intercala en la cadena para que no sea base64 válido a simple vista. */
    private val JUNK = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")

    /** Desplazamiento de cada carácter entre las dos capas de base64. */
    private const val SHIFT = 3

    fun handles(embedUrl: String): Boolean {
        val host = runCatching { URI(embedUrl).host?.lowercase() }.getOrNull() ?: return false
        return EMBED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /**
     * Si [html] es la página real de un reproductor de Voe, venga del dominio que venga: lleva el
     * payload ofuscado. Ver [StreamUrlParser.streamFromEmbedPage].
     */
    fun isPlayerPage(html: String): Boolean = PAYLOAD.containsMatchIn(html)

    /**
     * Destino de la redirección por JavaScript del stub. Solo casa con una cadena literal: la otra
     * rama del stub (`window.location.href = currentUrl.toString()`, la del `permanentToken` que
     * guarda un navegador) no es una URL y se ignora.
     */
    fun redirectFrom(html: String): String? = REDIRECT.find(html)?.groupValues?.get(1)

    /**
     * URL reproducible de la página real: el HLS (`source`, un `master.m3u8`) primero, luego el mp4
     * de `fallback` y por último `direct_access_url`. Null si la página no trae payload o si Voe ha
     * cambiado la ofuscación — y entonces el reproductor pasa a la siguiente fuente en ~0,5 s.
     */
    fun streamFrom(html: String): String? {
        val config = decodePayload(html) ?: return null
        config.optString("source").takeIf(::isHttp)?.let { return it }
        config.optJSONArray("fallback")?.let { list ->
            for (i in 0 until list.length()) {
                list.optJSONObject(i)?.optString("file")?.takeIf(::isHttp)?.let { return it }
            }
        }
        return config.optString("direct_access_url").takeIf(::isHttp)
    }

    /**
     * ROT13 → quitar el relleno → base64 → cada carácter −3 → invertir → base64 → JSON.
     * Comprobado en 7 de 7 embeds reales (SUB y DUB, 2026-09-24).
     */
    internal fun decodePayload(html: String): JSONObject? = runCatching {
        val blob = PAYLOAD.find(html)?.groupValues?.get(1) ?: return null
        var s = rot13(JSONArray(blob).getString(0))
        for (junk in JUNK) s = s.replace(junk, "")
        val shifted = s.decodeBase64()?.toByteArray() ?: return null
        // La capa intermedia es ASCII (base64 desplazado): byte a carácter sin pasar por un
        // charset, deshaciendo el desplazamiento y la inversión en la misma pasada.
        val inner = buildString(shifted.size) {
            for (i in shifted.indices.reversed()) append(((shifted[i].toInt() and 0xFF) - SHIFT).toChar())
        }
        val json = inner.decodeBase64()?.utf8() ?: return null
        JSONObject(json)
    }.getOrNull()

    private fun rot13(s: String): String = buildString(s.length) {
        for (c in s) append(
            when (c) {
                in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
                in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
                else -> c
            }
        )
    }

    private fun isHttp(url: String): Boolean =
        url.startsWith("https://") || url.startsWith("http://")
}
