package com.animeav1.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Descarga del manifiesto y del APK de una actualización.
 *
 * ⚠️ Cliente HTTP **propio** y no el de [AnimeRepository]: aquel fuerza `Cache-Control: max-age=300`
 * sobre toda respuesta correcta y tiene 50 MB de caché en disco. Con él, el manifiesto se serviría
 * rancio durante cinco minutos —justo el fichero cuyo trabajo es decir la verdad AHORA— y cada APK
 * de 9 MB se guardaría dos veces en disco.
 */
object UpdateRepository {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** El manifiesto publicado, o null si no se puede leer (sin red, 404, JSON inválido…). */
    suspend fun fetchManifest(url: String): UpdateInfo? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        runCatching {
            val req = Request.Builder().url(url)
                .header("User-Agent", AnimeRepository.USER_AGENT)
                .header("Cache-Control", "no-cache")
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@runCatching null
                UpdateManifest.parse(res.body?.string().orEmpty())
            }
        // ⚠️ La cancelación se relanza en vez de convertirse en "no hay manifiesto": quien llama
        // distingue "he mirado y no hay nada" de "ni he llegado a mirar", y de eso depende que se
        // selle o no la comprobación del día (ver UpdateChecker).
        }.getOrElse { e -> if (e is CancellationException) throw e else null }
    }

    /**
     * Baja el APK a [dest] y **verifica el SHA-256**.
     *
     * @return true solo si el fichero está entero y su huella coincide con la del manifiesto. Si no
     *   coincide se BORRA: un APK a medias o cambiado por el camino no puede quedarse en disco donde
     *   otro intento pueda darlo por bueno.
     */
    suspend fun downloadApk(
        info: UpdateInfo,
        dest: File,
        onProgress: (percent: Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        dest.delete()
        val ok = runCatching {
            val req = Request.Builder().url(info.apkUrl)
                .header("User-Agent", AnimeRepository.USER_AGENT)
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@runCatching false
                val body = res.body ?: return@runCatching false
                val total = body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
                val digest = MessageDigest.getInstance("SHA-256")
                var read = 0L
                var lastPercent = -1
                body.byteStream().use { input ->
                    dest.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            // Cancelar la pantalla cancela la descarga: sin esto seguiría bajando
                            // megabytes contra un fichero que ya no mira nadie.
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val p = ((read * 100) / total).toInt().coerceIn(0, 100)
                                if (p != lastPercent) { lastPercent = p; onProgress(p) }
                            }
                        }
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) } == info.sha256
            }
        }.getOrElse { e -> if (e is CancellationException) { dest.delete(); throw e } else false }
        if (!ok) dest.delete()
        ok
    }
}
