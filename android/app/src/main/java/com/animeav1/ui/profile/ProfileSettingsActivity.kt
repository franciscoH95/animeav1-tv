package com.animeav1.ui.profile

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.animeav1.R
import com.animeav1.data.LocalRepository
import com.animeav1.data.ProfileManager
import com.animeav1.data.local.AppDatabase
import com.animeav1.ui.backup.BackupActivity
import com.animeav1.ui.update.UpdateSettingsActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Ajustes del perfil activo. Se abre desde el avatar de la barra global.
 *
 * Concentra lo que antes estaba repartido: el nombre del perfil y el botón "Copia" vivían en la
 * cabecera de Mi Lista, donde eran dos acciones de la app metidas en una fila cuyo trabajo es elegir
 * sub-lista (y que en un mando había que cruzar para llegar a ellas).
 */
class ProfileSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_settings)

        val avatar = findViewById<TextView>(R.id.settings_avatar)
        val name   = findViewById<TextView>(R.id.settings_name)

        findViewById<TextView>(R.id.settings_profiles).setOnClickListener {
            startActivity(Intent(this, ProfilesActivity::class.java))
        }
        findViewById<TextView>(R.id.settings_backup).setOnClickListener {
            startActivity(Intent(this, BackupActivity::class.java))
        }
        findViewById<TextView>(R.id.settings_updates).setOnClickListener {
            startActivity(Intent(this, UpdateSettingsActivity::class.java))
        }

        // ⚠️ Resolver el perfil aquí también, como hacen MainActivity y ProfilesActivity:
        // `ProfileManager.init()` solo restaura el **id** de SharedPreferences, así que tras una
        // muerte de proceso el nombre arranca en "" y el color en 0. Esta pantalla puede ser la
        // primera en recrearse (es la de arriba de la tarea), y sin esto mostraba "—" con un círculo
        // del color del índice 0 — mintiendo sobre quién es el perfil activo.
        lifecycleScope.launch {
            if (ProfileManager.activeName.value.isBlank()) {
                ProfileManager.setActive(LocalRepository(AppDatabase.get(applicationContext)).ensureActiveProfile())
            }
        }

        // Se colecciona en vez de leerse una vez: al volver de gestionar perfiles el nombre, el color
        // o el perfil activo pueden ser otros, y esta pantalla sigue viva por debajo. Los dos juntos:
        // el color es tan observable como el nombre (ver ProfileManager.activeColorIndex).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ProfileManager.activeName
                    .combine(ProfileManager.activeColorIndex) { n, c -> n to c }
                    .collectLatest { (active, color) ->
                        name.text = active.ifBlank { "—" }
                        ProfileAvatars.paint(avatar, active, color)
                    }
            }
        }
    }

    /**
     * ⚠️ El foco inicial se pide desde aquí y no con `android:focusedByDefault`: los dos botones son
     * hijos directos y ya están medidos al llegar a `onStart`, así que basta un requestFocus — no hace
     * falta el baile del `OnGlobalLayoutListener` que sí necesitan las filas que se llenan desde un
     * Flow (ver `HomeFragment`).
     */
    override fun onStart() {
        super.onStart()
        if (currentFocus == null) findViewById<TextView>(R.id.settings_profiles).requestFocus()
    }
}
