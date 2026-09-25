package com.animeav1.ui.player

import com.animeav1.data.ByseParser
import com.animeav1.data.StreamUrlParser
import com.animeav1.data.model.EmbedServer

/**
 * Mitad PURA de las decisiones de reproducción que dependen de la fuente — sin Android ni media3,
 * para poder testearlas. Los números salen de medir MP4Upload contra sus nodos reales
 * (2026-09-24, ver CLAUDE.md, "MP4Upload").
 */
internal object PlaybackPolicy {

    // ── Tiempos de espera de red ──────────────────────────────────────────────────────────────

    /** El de media3 por defecto (`DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS`). */
    const val DEFAULT_READ_TIMEOUT_MS = 8_000

    /**
     * Para MP4Upload. ⚠️ En Android el handshake TLS lo limita el tiempo de LECTURA, no el de
     * conexión (la okhttp de la plataforma pone `setSoTimeout(readTimeout)` antes del handshake), y
     * sus nodos tienen rachas en las que tardan 8-42 s en contestar al TLS (TCP en 0,26 s). Con 8 s
     * cada intento moría y MP4Upload no llegaba a sonar nunca en esas rachas. 45 s cubre el peor
     * handshake medido (41,9 s).
     */
    const val MP4UPLOAD_READ_TIMEOUT_MS = 45_000

    fun readTimeoutMs(streamUrl: String): Int =
        if (StreamUrlParser.isMp4Upload(streamUrl)) MP4UPLOAD_READ_TIMEOUT_MS else DEFAULT_READ_TIMEOUT_MS

    // ── Búfer de MP4Upload ────────────────────────────────────────────────────────────────────

    /**
     * Mínimo = máximo, como los valores por defecto de media3: con hueco entre los dos el cargador
     * dejaría de leer la ÚNICA respuesta larga durante minutos, y el servidor (`Connection: close`)
     * la cerraría — cada reconexión cuesta otro handshake de hasta ~42 s. 120 s cubre una
     * reconexión entera (detección + handshake + primer byte ≈ 90 s en el peor caso).
     */
    const val MP4UPLOAD_BUFFER_MS = 120_000

    /**
     * Tope en bytes del búfer (el de media3 son 137,5 MiB), para las teles con poca memoria. Cuenta
     * también lo que se guarda hacia atrás ([MP4UPLOAD_BACK_BUFFER_MS], hasta ~40 s al conservarse
     * desde el clave), así que en los picos de bitrate el búfer hacia delante se queda en ~45-55 s.
     */
    const val MP4UPLOAD_BUFFER_BYTES = 32 * 1024 * 1024

    /** Lo ya visto que se conserva: ⏪10 s o un salto corto no abren una conexión nueva. */
    const val MP4UPLOAD_BACK_BUFFER_MS = 30_000

    // ── Watchdog de stream callado ────────────────────────────────────────────────────────────

    /** Lo de siempre: búfer quieto 25 s = CDN muerto. */
    const val DEFAULT_STALL_MS = 25_000L

    /**
     * MP4Upload puesto por la app (el orden por defecto, un fallback o la preferencia guardada de la
     * serie: ver `PlayerActivity.insisted`). En los nodos medidos el primer byte llega en 2-4 s;
     * más de 8 s de silencio solo pasa en una racha lenta, que puede durar hasta ~42 s: mejor Voe ya. El fallo lo pone detrás en los episodios siguientes
     * (`markSourceFailed`), así que se adapta solo. Mientras lleguen bytes —un `moov` de 1,3 MB en
     * un nodo lento— no cuenta como silencio y se espera.
     */
    const val MP4UPLOAD_AUTO_QUIET_MS = 8_000L

    /**
     * MP4Upload con paciencia: más que el tiempo de lectura (45 s). Cuando el usuario lo ha pedido en
     * ESTE episodio (panel o "Reintentar"), cuando ya ha sonado, y cuando es el ÚLTIMO RECURSO (no
     * queda otra fuente sin probar): hay episodios en los que Voe está muerto (11 de 86 en el
     * sondeo) y la única copia es MP4Upload en un nodo lento; cortarlo a los 8 s acababa en "ninguna
     * fuente responde" con una que sí funcionaba.
     */
    const val MP4UPLOAD_PATIENT_QUIET_MS = 50_000L

    /**
     * Cuánto silencio de RED se tolera con el búfer parado antes de dar la fuente por muerta.
     * Solo cuenta para MP4Upload: con el resto se mantiene la regla de siempre (el búfer quieto
     * [DEFAULT_STALL_MS]), que es la que caza a un Zilla que acepta la conexión y no manda nada.
     */
    fun quietBudgetMs(isMp4Upload: Boolean, patient: Boolean): Long = when {
        !isMp4Upload -> DEFAULT_STALL_MS
        patient -> MP4UPLOAD_PATIENT_QUIET_MS
        else -> MP4UPLOAD_AUTO_QUIET_MS
    }

    /**
     * Un MP4Upload que tarda más que esto en empezar a sonar está en un nodo lento: además del
     * handshake, `a3` frena las transferencias ya en marcha (80-100 kB/s a trompicones al principio),
     * así que bajar su `moov` puede llevar ~17 s aunque la red no llegue a callarse nunca. En `a4`
     * la mediana es ~6 s.
     */
    const val MP4UPLOAD_SLOW_START_MS = 12_000L

    fun isSlowStart(startMs: Long): Boolean = startMs > MP4UPLOAD_SLOW_START_MS

    // ── Caudal (MP4Upload y Byse) ─────────────────────────────────────────────────────────────

    /**
     * Tiempo de transferencia abierta que se mide antes de juzgar la fuente (ver [ThroughputMeter]).
     * Al arrancar el cargador lee sin parar —el búfer tarda en llenarse—, así que esto mide el
     * servidor y no lo que le deja leer media3.
     */
    const val THROUGHPUT_WINDOW_MS = 5_000L

    /**
     * Cuánto por encima del bitrate MEDIO del fichero tiene que servir el nodo. Es VBR: las escenas
     * movidas piden bastante más que la media durante decenas de segundos, y con el caudal justo
     * cada una vacía el búfer. Medido (2026-09-24): `a4` contestaba en 0,8 s pero servía 0,9 Mbit/s
     * con ficheros de 0,7-0,8 de media, y `a3`, tras sus 39 s de handshake, 0,5-1,3.
     */
    const val MP4UPLOAD_THROUGHPUT_MARGIN = 1.5

    /**
     * Lo que se exige mientras no se sabe el bitrate del fichero (sin tamaño en las cabeceras, o
     * sin la duración porque el `moov` aún no ha llegado —lo típico en un nodo lento—): 1,6 Mbit/s,
     * 1,5 × el fichero de más bitrate medido (1,04 Mbit/s).
     */
    const val MP4UPLOAD_FALLBACK_NEEDED_BYTES_PER_S = 200_000L

    fun neededBytesPerSecond(sizeBytes: Long, durationMs: Long): Long =
        if (sizeBytes > 0 && durationMs > 0) (sizeBytes * 1000.0 / durationMs * MP4UPLOAD_THROUGHPUT_MARGIN).toLong()
        else MP4UPLOAD_FALLBACK_NEEDED_BYTES_PER_S

    fun tooSlow(bytesPerSecond: Long, sizeBytes: Long, durationMs: Long): Boolean =
        bytesPerSecond < neededBytesPerSecond(sizeBytes, durationMs)

    /**
     * Lo que pide un HLS de una sola variante a partir del bitrate que media3 le pone a la pista de
     * vídeo (el `BANDWIDTH` de la playlist; en Byse es la media real del fichero, medido), con el
     * mismo margen. null si no se sabe: entonces no se juzga.
     */
    fun neededForBitrate(bitsPerSecond: Int): Long? =
        if (bitsPerSecond > 0) (bitsPerSecond / 8.0 * MP4UPLOAD_THROUGHPUT_MARGIN).toLong() else null

    /**
     * Tamaño TOTAL del fichero según las cabeceras de una respuesta: `Content-Range: bytes a-b/N`,
     * o el `Content-Length` si la petición empezaba en 0 (sin `Range`, media3 no lo manda). 0 si no se
     * sabe. Las claves se buscan sin distinguir mayúsculas: `HttpURLConnection` las deja como las
     * escribió el servidor.
     */
    fun totalSizeFrom(headers: Map<String, List<String>>, requestPosition: Long): Long {
        fun header(name: String) =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
        header("Content-Range")?.substringAfterLast('/', "")?.trim()?.toLongOrNull()?.let { return it }
        if (requestPosition == 0L) header("Content-Length")?.trim()?.toLongOrNull()?.let { return it }
        return 0
    }

    // ── Orden de las fuentes ──────────────────────────────────────────────────────────────────

    /** Fuentes que entregan AV1: MP4Upload (1080p Main 10 bits) y Zilla (segmentos fMP4 en AV1). */
    fun isAv1Source(embed: EmbedServer): Boolean =
        StreamUrlParser.isMp4Upload(embed.url) || StreamUrlParser.isZilla(embed.url)

    /**
     * Qué va antes a igualdad de fallos recientes (0 primero).
     * - **Byse, primero en TODOS los aparatos**: 1080p en H.264 High de 8 bits (lo decodifica por
     *   hardware cualquier tele) a 3,4-4,9 Mbit/s, desde un CDN que sirvió 31-67 Mbit/s. Es lo mejor
     *   que ofrece el sitio. Si no se resuelve (prueba de trabajo demasiado cara, protocolo cambiado)
     *   o no llega el caudal, el fallback sigue con los demás.
     * - Con decodificador AV1 Main10 por HARDWARE: luego MP4Upload (1080p); el resto, como venga.
     * - Sin él: las fuentes AV1 al FINAL. Por software, 1080p de 10 bits va a tirones (o ni hay
     *   decodificador y suena solo el audio), y Voe —H.264 de 8 bits, 720p— se ve bien en cualquier
     *   tele. Eso incluye a Zilla: con él vivo, antes iba el primero en todos los aparatos; y es lo
     *   que evita que huir de un AV1 que no da abasto (`markUnreliable`) acabe en otro AV1.
     */
    fun rank(embed: EmbedServer, av1Hardware: Boolean): Int = when {
        ByseParser.handles(embed.url) -> 0
        !isAv1Source(embed) -> 2
        !av1Hardware -> 3
        StreamUrlParser.isMp4Upload(embed.url) -> 1
        else -> 2
    }

    /** Primero lo que no ha fallado hace poco; luego [rank]; a igualdad, el orden del sitio. */
    fun order(penalty: (EmbedServer) -> Long, av1Hardware: Boolean): Comparator<EmbedServer> =
        compareBy<EmbedServer>({ penalty(it) }, { rank(it, av1Hardware) })

    // ── AV1 que dice ir por hardware y no puede ───────────────────────────────────────────────

    /** Ventana, desde que empieza a sonar, en la que se juzga al decodificador. */
    const val AV1_JUDGE_WINDOW_MS = 60_000L

    /**
     * Fotogramas perdidos en esa ventana que delatan un decodificador que no da abasto. media3 los
     * avisa en bloques de 50 (`DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY`),
     * así que esto son TRES bloques: un atasco sostenido, no un tropiezo.
     */
    const val AV1_MAX_DROPPED = 150

    fun av1Misbehaving(droppedFrames: Int, playingForMs: Long): Boolean =
        playingForMs <= AV1_JUDGE_WINDOW_MS && droppedFrames >= AV1_MAX_DROPPED
}
