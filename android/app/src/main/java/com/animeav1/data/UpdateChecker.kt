package com.animeav1.data

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import com.animeav1.BuildConfig

/**
 * Cuándo se mira si hay versión nueva.
 *
 * La URL del manifiesto vive en `BuildConfig.UPDATE_MANIFEST_URL` (ver `app/build.gradle.kts`), que
 * por defecto apunta al **último release de GitHub**:
 * `https://github.com/<repo>/releases/latest/download/update.json`. Esa URL no cambia al publicar
 * una versión nueva: GitHub siempre la resuelve al release más reciente, así que la app no necesita
 * saber qué versión existe para preguntarlo.
 */
object UpdateChecker {

    private const val PREFS = "updates"

    /** Último manifiesto leído DE VERDAD. */
    private const val KEY_LAST_OK = "last_ok"

    /** Último intento TERMINADO, con o sin suerte. */
    private const val KEY_LAST_TRY = "last_try"

    /** Un día. Mirar más a menudo no descubre nada nuevo y gasta red en cada vuelta al Inicio. */
    private const val OK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    /** Tras un fallo se reintenta antes: la TV puede estar sin red justo en ese momento. */
    private const val RETRY_INTERVAL_MS = 30 * 60 * 1000L

    /**
     * La actualización que hay que ofrecer AHORA, o null.
     *
     * ⚠️ Nunca lanza: una comprobación de actualizaciones no puede impedir usar la app. Sin red, con
     * el manifiesto caído o con el repo aún sin configurar, devuelve null y no se dice nada — no hay
     * ningún aviso de "no se pudo comprobar" porque el usuario no ha pedido comprobar nada.
     *
     * ⚠️ **Los sellos se escriben DESPUÉS de la petición, nunca antes.** Al arrancar, `MainActivity`
     * se resume un instante antes de que el selector de perfiles se ponga encima, así que la primera
     * comprobación se cancela a media petición; sellando antes, esa comprobación fantasma se
     * apuntaba el día entero y la de verdad —la de justo después de elegir perfil— se encontraba con
     * que "ya se había mirado hoy". Resultado medido en el emulador: el aviso no salía NUNCA.
     * Cancelar ahora no deja rastro y el siguiente intento manda.
     */
    suspend fun pending(context: Context, now: Long = System.currentTimeMillis()): UpdateInfo? {
        val url = BuildConfig.UPDATE_MANIFEST_URL
        if (url.isBlank() || url.contains(PLACEHOLDER)) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (now - prefs.getLong(KEY_LAST_OK, 0L) < OK_INTERVAL_MS) return null
        if (now - prefs.getLong(KEY_LAST_TRY, 0L) < RETRY_INTERVAL_MS) return null

        val info = UpdateRepository.fetchManifest(url)   // si cancelan aquí, no se sella nada

        prefs.edit()
            .putLong(KEY_LAST_TRY, now)
            .apply { if (info != null) putLong(KEY_LAST_OK, now) }
            .apply()
        if (info == null) return null
        return info.takeIf {
            UpdateManifest.shouldOffer(it, installedVersionCode(context), android.os.Build.VERSION.SDK_INT)
        }
    }

    /** Para probar a mano: olvida cuándo se miró por última vez. */
    fun forgetLastCheck(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_LAST_OK).remove(KEY_LAST_TRY).apply()

    fun installedVersionCode(context: Context): Int = runCatching {
        PackageInfoCompat.getLongVersionCode(
            context.packageManager.getPackageInfo(context.packageName, 0)
        ).toInt()
    }.getOrDefault(Int.MAX_VALUE)   // si no se sabe, no se ofrece nada

    /** Marca del repo sin configurar; ver `githubRepo` en gradle.properties. */
    private const val PLACEHOLDER = "TU-USUARIO"
}
