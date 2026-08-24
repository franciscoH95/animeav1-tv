package com.animeav1.data

import java.net.URI

/**
 * Pure parsing half of stream resolution — no network, no Android APIs, so it can be unit-tested
 * against captured fixtures. [AnimeRepository] keeps the I/O and calls in here for the actual
 * pattern work, which is where every historical breakage has been.
 */
internal object StreamUrlParser {

    /** Zilla embed ids are exactly 32 hex-ish chars; anything else isn't a play URL. */
    private const val ZILLA_ID_LENGTH = 32

    /**
     * `player.zilla-networks.com/play/<id>` → `/m3u8/<id>`. A plain URL transform: no JS, no
     * token, which is why this is the one server that always works natively.
     */
    fun zillaM3u8(embedUrl: String): String? {
        // Strip fragment and query BEFORE taking the last path segment: doing it the other way
        // round turns ".../play/<id>/?x=1" into an empty id.
        val id = embedUrl
            .substringBefore('#')
            .substringBefore('?')
            .trimEnd('/')
            .substringAfterLast('/')
        if (id.length != ZILLA_ID_LENGTH) return null
        return "https://player.zilla-networks.com/m3u8/$id"
    }

    /**
     * Finds a directly playable URL in an embed page, preferring HLS (ExoPlayer handles it best).
     *
     * The extension MUST be anchored to the end of the URL (before a quote/space/angle bracket,
     * an optional query, or end of input). Without the anchor, ".mp4" also matches inside the
     * host "mp4upload.com", and the first hit in a real MP4Upload page is the truncated garbage
     * "https://www.mp4" taken from one of its player script tags.
     */
    fun directUrlFrom(html: String): String? {
        for (pattern in PATTERNS) {
            val match = pattern.find(html) ?: continue
            return match.groupValues[1]
        }
        return null
    }

    private val PATTERNS = listOf(
        Regex("""(https?://[^\s"'<>]+?\.m3u8(?:[?#][^\s"'<>]*)?)(?=["'\s<>]|$)"""),
        Regex("""(https?://[^\s"'<>]+?\.mp4(?:[?#][^\s"'<>]*)?)(?=["'\s<>]|$)"""),
    )

    // ── Sec-Fetch-Site ────────────────────────────────────────────────────────────────────────

    /**
     * Valor que un navegador pondría en `Sec-Fetch-Site` al pedir [url] desde una página en
     * [referer].
     *
     * ⚠️ **No es cosmético.** Cloudflare, delante del CDN de Zilla, responde **403** a toda
     * petición de segmento (`/segs/<id>/000.html`) que no traiga literalmente `same-origin` en
     * esta cabecera — y solo ese valor exacto: `same-site`, `cross-site`, `none` o un valor
     * inventado siguen dando 403. La playlist `/m3u8/<id>` sí pasa sin ella, así que el síntoma
     * no es un error de red: media3 carga la playlist, no consigue ni un segmento y se queda en
     * `STATE_BUFFERING` para siempre hasta que el watchdog lo mata con "HLS no responde".
     *
     * Se calcula de verdad en vez de mandar `same-origin` a ciegas: para Zilla los segmentos van
     * al mismo host que el embed, así que sale `same-origin` solo cuando es cierto.
     */
    fun secFetchSite(url: String, referer: String): String {
        val target = originOf(url) ?: return "cross-site"
        val from   = originOf(referer) ?: return "cross-site"
        return when {
            target == from -> "same-origin"
            registrableDomain(target.host) == registrableDomain(from.host) -> "same-site"
            else -> "cross-site"
        }
    }

    private data class Origin(val scheme: String, val host: String, val port: Int)

    private fun originOf(url: String): Origin? = runCatching {
        val u      = URI(url)
        val scheme = u.scheme?.lowercase() ?: return@runCatching null
        val host   = u.host?.lowercase()   ?: return@runCatching null
        Origin(scheme, host, if (u.port != -1) u.port else if (scheme == "https") 443 else 80)
    }.getOrNull()

    /**
     * Aproximación a "dominio registrable" por las dos últimas etiquetas, sin Public Suffix List.
     * Solo decide entre `same-site` y `cross-site`, y ningún CDN al que le mandamos esto
     * distingue entre esos dos: el único valor que carga peso es `same-origin`, que se compara
     * por origen completo más arriba.
     */
    private fun registrableDomain(host: String): String {
        val parts = host.split('.')
        return if (parts.size <= 2) host else parts.takeLast(2).joinToString(".")
    }
}
