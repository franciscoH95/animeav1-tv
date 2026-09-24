package com.animeav1.ui.player

import android.content.Context
import androidx.core.content.edit
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

/**
 * Si este aparato decodifica por HARDWARE el AV1 que sirve MP4Upload: Main 10 bits, 1920×1080 a 24
 * fps (`av01.0.08M.10`, medido sobre el `av1C` de un fichero real). De eso depende que MP4Upload
 * vaya primero (ver [PlaybackPolicy.rank]).
 *
 * ⚠️ El codec string explícito no es un adorno: media3 1.3.1 NO deriva uno para AV1 al leer el MP4
 * (solo lo hace con avcC/hvcC/dvcC), así que por su cuenta no comprobaría los 10 bits y daría por
 * bueno un decodificador que solo hace 8.
 *
 * Se calcula UNA vez, fuera del hilo principal (`MediaCodecList` puede tardar cientos de ms), al
 * arrancar la app. Mientras no se sepa, vale false: el orden de siempre, con Voe delante.
 *
 * Y se puede desmentir: si un decodificador que dice ir por hardware pierde fotogramas a chorros
 * ([PlaybackPolicy.av1Misbehaving]), [markUnreliable] lo apunta para siempre en este aparato.
 */
@UnstableApi
object Av1Support {

    private const val PREFS = "playback"
    private const val KEY_UNRELIABLE = "av1_unreliable"

    private val PROBE_FORMAT: Format = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_AV1)
        .setCodecs("av01.0.08M.10")
        .setWidth(1920)
        .setHeight(1080)
        .setFrameRate(24f)
        .build()

    /** true solo si hay un decodificador AV1 Main10 1080p por hardware y no se ha desmentido. */
    @Volatile var hardwareMain10: Boolean = false
        private set

    fun probeAsync(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        Thread({
            val unreliable = prefs.getBoolean(KEY_UNRELIABLE, false)
            hardwareMain10 = !unreliable && detect()
        }, "av1-probe").apply { isDaemon = true }.start()
    }

    /** El decodificador "por hardware" no ha dado abasto: MP4Upload deja de ir primero aquí. */
    fun markUnreliable(context: Context) {
        hardwareMain10 = false
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_UNRELIABLE, true) }
    }

    private fun detect(): Boolean = runCatching {
        MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_AV1, /* secure = */ false, /* tunneling = */ false)
            .any { it.hardwareAccelerated && !it.softwareOnly && it.isFormatSupported(PROBE_FORMAT) }
    }.getOrDefault(false)
}
