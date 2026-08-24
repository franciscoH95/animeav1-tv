package com.animeav1.ui.update

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.animeav1.R
import com.animeav1.data.ApkInstaller
import com.animeav1.data.UpdateInfo
import com.animeav1.data.UpdateRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * "Hay una versión nueva": descarga el APK, comprueba su huella y lanza la instalación.
 *
 * ⚠️ El usuario puede decir que **no** y el botón por defecto es "Actualizar ahora", pero BACK sale
 * sin actualizar: nadie se queda atrapado por un aviso que él no pidió. Decir "Más tarde" no marca
 * nada como descartado — la pregunta vuelve en la siguiente comprobación (ver `UpdateChecker`), que
 * es lo que hace que una versión con un arreglo importante no se pierda por una pulsación.
 */
class UpdateActivity : AppCompatActivity() {

    private lateinit var info: UpdateInfo
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var btnNow: Button
    private lateinit var btnLater: Button
    private var job: Job? = null

    /** La pantalla está pidiendo el permiso de instalar, no ofreciendo la descarga. */
    private var needsPermission = false

    /** Dónde cae el APK. En la caché a propósito: si algo va mal, el sistema puede tirarlo él. */
    private val apkFile by lazy { File(cacheDir, "update.apk") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update)

        info = fromIntent(intent) ?: run { finish(); return }

        progress = findViewById(R.id.update_progress)
        status   = findViewById(R.id.update_status)
        btnNow   = findViewById(R.id.btn_update_now)
        btnLater = findViewById(R.id.btn_update_later)

        findViewById<TextView>(R.id.update_version).text =
            getString(R.string.update_version, info.versionName, humanSize(info.sizeBytes))
        findViewById<TextView>(R.id.update_notes).apply {
            text = info.notes
            visibility = if (info.notes.isBlank()) View.GONE else View.VISIBLE
        }

        btnNow.setOnClickListener { start() }
        btnLater.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    override fun onStart() {
        super.onStart()
        // El foco arranca en "Actualizar ahora": es a lo que viene esta pantalla, y salir sigue
        // estando a una pulsación de BACK.
        if (currentFocus == null) btnNow.requestFocus()
    }

    private fun start() {
        // ⚠️ Primero el permiso. Desde API 26 sin "instalar apps desconocidas" la instalación se
        // rechaza AL FINAL, después de bajarse el APK entero y sin decir por qué.
        if (!ApkInstaller.canInstall(this)) { askPermission(); return }
        if (job != null) return
        setBusy(true)
        job = lifecycleScope.launch {
            val ok = UpdateRepository.downloadApk(info, apkFile) { p ->
                progress.progress = p
                status.text = getString(R.string.update_downloading, p)
            }
            if (!ok) {
                fail(R.string.update_download_failed)
                return@launch
            }
            status.text = getString(R.string.update_installing)
            if (!ApkInstaller.install(this@UpdateActivity, apkFile)) fail(R.string.update_install_failed)
            // Si sale bien, la confirmación del sistema se pone delante y esta pantalla ya no pinta
            // nada: se cierra para no reaparecer detrás si el usuario cancela el instalador.
            else finish()
        }
    }

    /**
     * Pide el permiso de instalar, y lo deja DICHO en pantalla.
     *
     * ⚠️ Antes era un Toast y un `startActivity` en `runCatching`: si el intent no lo resolvía nadie
     * —pasa en algunas TV de fabricante— el aviso se iba solo a los pocos segundos y no ocurría
     * nada más, así que desde el sofá la app "no hacía nada" al pulsar Actualizar. Ahora el motivo
     * se queda escrito, el botón cambia a DAR PERMISO, y si la pantalla de ajustes no existe se
     * explica el camino a mano en vez de fingir que se abrió.
     */
    private fun askPermission() {
        needsPermission = true
        status.visibility = View.VISIBLE
        status.text = getString(R.string.update_needs_permission)
        btnNow.setText(R.string.update_permission)
        val opened = runCatching { startActivity(ApkInstaller.permissionSettingsIntent(this)) }.isSuccess
        if (!opened) status.text = getString(R.string.update_permission_manual)
    }

    /**
     * Al volver de Ajustes se vuelve a mirar: si ya está concedido, la pantalla deja de pedirlo y
     * el botón vuelve a ser "Actualizar". Sin esto, quien acaba de dar el permiso se encuentra la
     * pantalla igual que la dejó y no sabe si sirvió de algo.
     */
    private fun refreshPermissionState() {
        if (!needsPermission || !ApkInstaller.canInstall(this)) return
        needsPermission = false
        status.visibility = View.GONE
        btnNow.setText(R.string.update_now)
    }

    private fun fail(msgRes: Int) {
        job = null
        setBusy(false)
        Toast.makeText(this, msgRes, Toast.LENGTH_LONG).show()
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        status.visibility   = if (busy) View.VISIBLE else View.GONE
        progress.progress = 0
        // Los botones se ESCONDEN, no se deshabilitan: deshabilitados dejan de ser focusables y el
        // foco se escaparía de la pantalla (el mismo motivo por el que el reproductor atenúa en vez
        // de deshabilitar sus botones de episodio).
        findViewById<View>(R.id.update_buttons).visibility = if (busy) View.INVISIBLE else View.VISIBLE
        if (!busy) btnNow.requestFocus()
    }

    /** Cancelar la descarga es simplemente irse: `lifecycleScope` la corta. */
    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    private fun humanSize(bytes: Long): String =
        if (bytes <= 0) "" else String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)

    companion object {
        private const val E_CODE = "versionCode"
        private const val E_NAME = "versionName"
        private const val E_URL  = "apkUrl"
        private const val E_SHA  = "sha256"
        private const val E_SIZE = "sizeBytes"
        private const val E_NOTES = "notes"

        fun intent(context: Context, info: UpdateInfo): Intent =
            Intent(context, UpdateActivity::class.java).apply {
                putExtra(E_CODE, info.versionCode)
                putExtra(E_NAME, info.versionName)
                putExtra(E_URL, info.apkUrl)
                putExtra(E_SHA, info.sha256)
                putExtra(E_SIZE, info.sizeBytes)
                putExtra(E_NOTES, info.notes)
            }

        private fun fromIntent(intent: Intent): UpdateInfo? {
            val url = intent.getStringExtra(E_URL).orEmpty()
            val sha = intent.getStringExtra(E_SHA).orEmpty()
            if (url.isBlank() || sha.isBlank()) return null
            return UpdateInfo(
                versionCode = intent.getIntExtra(E_CODE, 0),
                versionName = intent.getStringExtra(E_NAME).orEmpty(),
                apkUrl      = url,
                sha256      = sha,
                sizeBytes   = intent.getLongExtra(E_SIZE, 0L),
                notes       = intent.getStringExtra(E_NOTES).orEmpty()
            )
        }
    }
}
