package com.animeav1.ui.player

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
     * MP4Upload puesto por la app (el orden por defecto o un fallback). En los dos nodos medidos el
     * primer byte llega en 2-4 s; más de 8 s de silencio solo pasa en una racha lenta, que puede
     * durar hasta ~42 s: mejor Voe ya. El fallo lo pone detrás en los episodios siguientes
     * (`markSourceFailed`), así que se adapta solo. Mientras lleguen bytes —un `moov` de 1,3 MB en
     * un nodo lento— no cuenta como silencio y se espera.
     */
    const val MP4UPLOAD_AUTO_QUIET_MS = 8_000L

    /**
     * MP4Upload con paciencia: más que el tiempo de lectura (45 s). Cuando lo eligió el usuario, cuando
     * ya ha sonado, y cuando es el ÚLTIMO RECURSO (no queda otra fuente sin probar): hay episodios en
     * los que Voe está muerto (11 de 86 en el sondeo) y la única copia es MP4Upload en un nodo lento;
     * cortarlo a los 8 s acababa en "ninguna fuente responde" con una que sí funcionaba.
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

    // ── Orden de las fuentes ──────────────────────────────────────────────────────────────────

    /** Fuentes que entregan AV1: MP4Upload (1080p Main 10 bits) y Zilla (segmentos fMP4 en AV1). */
    fun isAv1Source(embed: EmbedServer): Boolean =
        StreamUrlParser.isMp4Upload(embed.url) || StreamUrlParser.isZilla(embed.url)

    /**
     * Qué va antes a igualdad de fallos recientes (0 primero).
     * - Con decodificador AV1 Main10 por HARDWARE: MP4Upload primero (1080p); el resto, como venga.
     * - Sin él: las fuentes AV1 al FINAL. Por software, 1080p de 10 bits va a tirones (o ni hay
     *   decodificador y suena solo el audio), y Voe —H.264 de 8 bits, 720p— se ve bien en cualquier
     *   tele. Eso incluye a Zilla: con él vivo, antes iba el primero en todos los aparatos; y es lo
     *   que evita que huir de un AV1 que no da abasto (`markUnreliable`) acabe en otro AV1.
     */
    fun rank(embed: EmbedServer, av1Hardware: Boolean): Int = when {
        !isAv1Source(embed) -> 1
        !av1Hardware -> 2
        StreamUrlParser.isMp4Upload(embed.url) -> 0
        else -> 1
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
