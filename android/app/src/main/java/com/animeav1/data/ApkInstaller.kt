package com.animeav1.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.animeav1.R
import java.io.File

/**
 * Instala un APK ya descargado y **verificado**.
 *
 * Se usa `PackageInstaller` y no el viejo `ACTION_INSTALL_PACKAGE`: es la API viva desde API 21 y la
 * única que no depende de exponer el fichero por un `FileProvider`. El sistema enseña igualmente su
 * pantalla de confirmación — en una TV se acepta con el mando— porque instalar nunca es silencioso
 * para una app que no es del sistema.
 */
object ApkInstaller {

    /**
     * ⚠️ Desde API 26 el permiso `REQUEST_INSTALL_PACKAGES` no basta: el usuario tiene que haber
     * autorizado a ESTA app en "orígenes desconocidos". Sin comprobarlo, la sesión se abre, se
     * escribe el APK entero y el sistema la rechaza al final sin explicar nada.
     */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** El ajuste donde se concede ese permiso, ya apuntando a esta app. */
    fun permissionSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Abre la sesión, escribe el APK y pide la confirmación. `false` = ni siquiera se pudo empezar. */
    fun install(context: Context, apk: File): Boolean = runCatching {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("animeav1.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            session.commit(pendingIntent(context, sessionId).intentSender)
        }
        true
    }.getOrDefault(false)

    /**
     * ⚠️ `FLAG_MUTABLE` a partir de API 31: el sistema RELLENA extras en este PendingIntent (el
     * estado y, cuando toca, el intent de confirmación). Inmutable, Android 12+ lanza al crearlo.
     */
    private fun pendingIntent(context: Context, sessionId: Int): PendingIntent {
        val intent = Intent(context, InstallResultReceiver::class.java)
            .setAction(InstallResultReceiver.ACTION_INSTALL_STATUS)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(context, sessionId, intent, flags)
    }
}

/**
 * Recibe el desenlace de la sesión de instalación.
 *
 * ⚠️ `STATUS_PENDING_USER_ACTION` **no es un error**: es el caso normal. El sistema devuelve el
 * intent de su propia pantalla de confirmación y hay que lanzarlo; si no se hace, la instalación se
 * queda esperando para siempre y desde fuera parece que el botón "Actualizar" no hizo nada.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
            // El éxito no se anuncia: el proceso se reemplaza y el aviso saldría sobre la app nueva.
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> Toast.makeText(context, R.string.update_install_failed, Toast.LENGTH_LONG).show()
        }
    }

    companion object { const val ACTION_INSTALL_STATUS = "com.animeav1.INSTALL_STATUS" }
}
