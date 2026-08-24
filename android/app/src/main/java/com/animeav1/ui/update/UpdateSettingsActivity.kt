package com.animeav1.ui.update

import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.animeav1.BuildConfig
import com.animeav1.R
import com.animeav1.data.UpdateChecker
import com.animeav1.data.UpdateInfo
import com.animeav1.data.UpdateManifest
import com.animeav1.data.UpdateRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * "Actualizaciones": qué versión hay instalada, buscar a mano y leer las novedades.
 *
 * El aviso automático solo salta cuando hay algo nuevo y como mucho una vez al día, así que sin esta
 * pantalla no había forma de preguntar "¿hay actualización?" ni de leer qué traía la versión que ya
 * está instalada — y quien acaba de actualizar se queda sin saber qué ha cambiado.
 *
 * ⚠️ Aquí NO se respeta el intervalo de [UpdateChecker]: si el usuario pulsa "Buscar", se busca. Ese
 * intervalo existe para no gastar red sola en cada vuelta a Inicio, no para hacer esperar a quien
 * pregunta.
 */
class UpdateSettingsActivity : AppCompatActivity() {

    private lateinit var installed: TextView
    private lateinit var state: TextView
    private lateinit var notes: TextView
    private lateinit var btnCheck: Button
    private lateinit var btnInstall: Button
    private var job: Job? = null
    private var found: UpdateInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update_settings)

        installed  = findViewById(R.id.updates_installed)
        state      = findViewById(R.id.updates_state)
        notes      = findViewById(R.id.updates_notes)
        btnCheck   = findViewById(R.id.btn_check_updates)
        btnInstall = findViewById(R.id.btn_install_update)

        // La versión y el Android del aparato. Lo segundo no es un adorno: qué se puede hacer con
        // los permisos de almacenamiento y de instalación depende de esa cifra, y es lo primero que
        // hay que saber cuando algo no aparece donde debería.
        installed.text = getString(
            R.string.updates_installed,
            BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, Build.VERSION.RELEASE
        )
        state.setText(R.string.updates_idle)

        btnCheck.setOnClickListener { check() }
        btnInstall.setOnClickListener {
            found?.let { startActivity(UpdateActivity.intent(this, it)) }
        }
    }

    override fun onStart() {
        super.onStart()
        if (currentFocus == null) btnCheck.requestFocus()
    }

    private fun check() {
        if (job != null) return
        state.setText(R.string.updates_checking)
        notes.visibility = View.GONE
        btnInstall.visibility = View.GONE
        found = null
        job = lifecycleScope.launch {
            val info = UpdateRepository.fetchManifest(BuildConfig.UPDATE_MANIFEST_URL)
            job = null
            if (info == null) {
                state.setText(R.string.updates_failed)
                return@launch
            }
            val current = UpdateChecker.installedVersionCode(this@UpdateSettingsActivity)
            if (UpdateManifest.shouldOffer(info, current, Build.VERSION.SDK_INT)) {
                found = info
                state.text = getString(R.string.updates_available, info.versionName)
                btnInstall.visibility = View.VISIBLE
                btnInstall.requestFocus()
            } else {
                state.setText(R.string.updates_up_to_date)
            }
            // Las novedades se enseñan en los DOS casos. El manifiesto describe siempre la última
            // versión publicada, así que estando al día son las de la que ya tienes: justo lo que
            // no había forma de leer después de actualizar.
            if (info.notes.isNotBlank()) {
                notes.text = info.notes
                notes.visibility = View.VISIBLE
            }
        }
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }
}
