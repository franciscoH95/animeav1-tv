package com.animeav1.data

import com.animeav1.data.model.AudioTrack
import com.animeav1.data.model.EmbedServer
import org.json.JSONObject

/**
 * Mitad PURA de la lectura de servidores del episodio — sin red ni Android, para poder testearla
 * contra una captura real (`episodio__data.json`). Igual que [StreamUrlParser], está aquí porque
 * la forma del JSON del sitio es lo que más veces ha roto la app.
 *
 * El nodo `embeds` del episodio es un objeto con una clave por pista de audio:
 *
 * ```
 * "embeds": { "SUB": [ {server,url}, … ], "DUB": [ {server,url}, … ] }
 * ```
 *
 * Devuelve las DOS pistas (SUB primero) en una sola lista, cada entrada etiquetada con su
 * [AudioTrack]. Antes solo se devolvía la primera pista con algún servidor reproducible, así que
 * un episodio doblado era inalcanzable desde la app aunque el sitio lo ofreciera.
 */
internal object EmbedParser {

    /**
     * Servidores que NUNCA llegan a la UI. Dos motivos distintos, misma consecuencia — la app no
     * puede sacar de ellos una URL reproducible sin ejecutar su JavaScript:
     *
     * - `mega`, `upnshare` → entregan el stream **cifrado** y lo descifra su propio JS de cliente.
     * - `terabox`, `streamtape`, `vidhide`, `netu`, `doodstream` → el HTML del embed **no contiene
     *   ninguna URL directa** que `StreamUrlParser.directUrlFrom` pueda encontrar. Comprobado
     *   contra el sitio real: TeraBox/StreamTape/VidHide dan 0 coincidencias en todos los embeds
     *   probados; DoodStream responde el reto JS de Cloudflare ("Just a moment…", HTTP 403), que
     *   un GET plano no puede resolver; y Netu sí deja un `.m3u8` en el HTML, pero es un **señuelo**
     *   (ruta de 2018, timestamp de 2020 y la IP `94.25.170.26` incrustada) que ni siquiera conecta.
     *
     * ⚠️ Esto es una lista negra a propósito, no una lista blanca: un servidor **nuevo** que el
     * sitio empiece a ofrecer sigue apareciendo y se le da la oportunidad de resolver. Solo se
     * esconde lo que se ha comprobado que no funciona.
     *
     * Se compara con el nombre EXACTO del servidor (la etiqueta del sitio es estable) en vez de por
     * subcadena: `"netu" in url` casaría con cualquier URL que contenga esa secuencia por accidente.
     */
    private val UNSUPPORTED_SERVERS = setOf(
        "mega", "upnshare",
        "terabox", "streamtape", "vidhide", "netu", "doodstream"
    )

    /**
     * Respaldo por host, para el caso de que el sitio renombre la etiqueta pero siga apuntando al
     * mismo proveedor. `uns.bio` es el host de UPNShare; estaba ya en la lista original.
     */
    private val UNSUPPORTED_HOSTS = listOf(
        "mega.nz", "uns.bio", "upnshare", "terabox.com", "streamtape", "hqq.ac"
    )

    /** Orden de presentación: subtitulado primero, que es lo que trae casi todo el catálogo. */
    private val TRACK_ORDER = listOf(AudioTrack.SUB, AudioTrack.DUB)

    /**
     * @param decoded nodo ya resuelto por [SvelteKitDecoder] para la clave raíz `episode`.
     * @return servidores reproducibles de ambas pistas; vacío si el episodio no trae ninguno.
     */
    fun parse(decoded: JSONObject): List<EmbedServer> {
        val embeds = decoded.optJSONObject("embeds")
            ?: decoded.optJSONObject("episode")?.optJSONObject("embeds")
            ?: return emptyList()

        val out = mutableListOf<EmbedServer>()
        for (track in TRACK_ORDER) {
            // Las claves llegan en mayúsculas, pero se acepta la variante en minúsculas por si
            // el sitio cambia de criterio: es un `optJSONArray` de más, no una suposición.
            val arr = embeds.optJSONArray(track.name)
                ?: embeds.optJSONArray(track.name.lowercase())
                ?: continue
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val url = e.optString("url")
                if (url.isBlank()) continue
                val embed = EmbedServer(e.optString("server"), url, track)
                if (!isUnsupported(embed)) out.add(embed)
            }
        }
        return out
    }

    /** Se filtran ANTES de llegar a la UI: ofrecer un servidor que nunca va a reproducir es peor
     *  que no ofrecerlo. Si ninguna pista deja nada, la lista sale vacía y el reproductor lo dice. */
    fun isUnsupported(e: EmbedServer): Boolean {
        if (e.server.trim().lowercase() in UNSUPPORTED_SERVERS) return true
        val url = e.url.lowercase()
        return UNSUPPORTED_HOSTS.any { it in url }
    }
}
