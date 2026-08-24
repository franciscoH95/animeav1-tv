package com.animeav1.ui.profile

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.animeav1.R
import com.animeav1.data.LocalRepository
import com.animeav1.data.ProfileManager
import com.animeav1.data.local.AppDatabase
import com.animeav1.data.local.Profile
import com.animeav1.ui.ExitConfirm
import com.animeav1.ui.MainActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Elegir, crear, renombrar y borrar perfiles.
 *
 * Dos modos, según cómo se abra (extra [EXTRA_PICKER]):
 * - **Selector de arranque** (`true`): lo lanza `MainActivity` cuando hay más de un perfil. Elegir
 *   devuelve el control a la app. BACK no puede salirse sin elegir — se quedaría sin perfil activo.
 * - **Gestión** (`false`): se abre desde Mi Lista con un perfil ya activo. BACK simplemente cierra.
 */
class ProfilesActivity : FragmentActivity() {

    private val local by lazy { LocalRepository(AppDatabase.get(applicationContext)) }

    private lateinit var list: RecyclerView
    private lateinit var adapter: ProfileAdapter
    private lateinit var title: TextView

    private lateinit var editorOverlay: View
    private lateinit var editorTitle: TextView
    private lateinit var editorName: EditText
    private lateinit var colorList: RecyclerView
    private lateinit var colorAdapter: ColorAdapter
    private lateinit var btnSave: Button
    private lateinit var btnDelete: Button
    private lateinit var btnCancel: Button

    /** Perfil que se está editando; null = se está creando uno nuevo. */
    private var editing: Profile? = null
    private var editorColor = 0

    /** Evita que dos pulsaciones seguidas en Guardar creen dos perfiles: el overlay solo se cierra
     *  dentro de la corrutina, así que sin esto el segundo clic entra con `editing` aún null. */
    private var saving = false

    /** El botón Borrar es de dos toques: el primero avisa y se reetiqueta, el segundo borra. */
    private var confirmingDelete = false

    /** "¿Cerrar la app?" — solo se usa en modo selector, que es donde BACK cierra. */
    private lateinit var exitConfirm: ExitConfirm

    /** Última lista conocida. Se guarda para elegir un color libre y para repintar el "ACTIVO" sin
     *  esperar a que Room re-emita (cambiar de perfil no modifica la tabla `profiles`). */
    private var profiles: List<Profile> = emptyList()

    private val isPicker: Boolean by lazy { intent.getBooleanExtra(EXTRA_PICKER, false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profiles)

        title         = findViewById(R.id.profiles_title)
        list          = findViewById(R.id.profiles_list)
        editorOverlay = findViewById(R.id.editor_overlay)
        editorTitle   = findViewById(R.id.editor_title)
        editorName    = findViewById(R.id.editor_name)
        colorList     = findViewById(R.id.color_list)
        btnSave       = findViewById(R.id.btn_editor_save)
        btnDelete     = findViewById(R.id.btn_editor_delete)
        btnCancel     = findViewById(R.id.btn_editor_cancel)

        title.setText(if (isPicker) R.string.profiles_title_pick else R.string.profiles_title)

        adapter = ProfileAdapter(
            onPick = { pick(it) },
            onEdit = { openEditor(it) },
            onAdd  = { openEditor(null) }
        )
        list.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        list.adapter = adapter

        colorAdapter = ColorAdapter { editorColor = it }
        colorList.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        colorList.adapter = colorAdapter

        exitConfirm = ExitConfirm(
            overlay = findViewById(R.id.exit_overlay),
            content = findViewById(R.id.profiles_content),
            // Cerrar solo esta Activity devolvería a MainActivity sin perfil elegido: se cierra la
            // tarea entera, que es lo que el usuario ha pedido.
            onExit  = { finishAffinity() }
        )

        // El "OK" del teclado lo cierra. En una TV el teclado ocupa media pantalla y **se come las
        // pulsaciones del D-pad**: mientras está puesto, ABAJO recorre sus teclas y no baja ni a los
        // colores ni a Guardar, así que la única salida era BACK (que no se ve por ninguna parte).
        // El foco se queda en el campo a propósito: lo siguiente puede ser elegir color, que está
        // entre medias, y llevarlo a Guardar de un salto se lo saltaría.
        editorName.setOnEditorActionListener { _, actionId, event ->
            val done = actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_NEXT ||
                // Con `imeOptions` sin declarar algunos teclados mandan IME_NULL + ENTER.
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (done) hideIme(editorName)
            done
        }

        btnSave.setOnClickListener { saveEditor() }
        btnDelete.setOnClickListener { deleteEditing() }
        btnCancel.setOnClickListener { closeEditor() }

        lifecycleScope.launch {
            // Antes de observar: una instalación limpia no pasa por la migración 7→8, así que puede
            // no haber ningún perfil todavía. Ahora que esta pantalla es la puerta de entrada, sin
            // esto la primera vez saldría un selector con solo "Añadir perfil".
            ProfileManager.setActive(local.ensureActiveProfile())
            observeProfiles()
        }
    }

    private fun observeProfiles() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // combine con `activeId`: la tabla `profiles` NO cambia al cambiar de perfil, así
                // que sin esto el rótulo "ACTIVO" solo se movía cuando alguien llamaba a submit() a
                // mano — y se quedaba en el perfil viejo al borrar el activo.
                combine(local.getProfiles(), ProfileManager.activeId) { rows, active -> rows to active }
                    .collectLatest { (rows, active) ->
                    profiles = rows
                    adapter.submit(rows, active)
                    // Aterrizar el foco en la tarjeta del perfil ACTIVO la primera vez; después no
                    // se toca, para no robárselo al editor ni resetearlo en cada re-emisión de Room.
                    if (list.findFocus() == null && editorOverlay.visibility != View.VISIBLE) {
                        focusActiveProfile(active)
                    }
                }
            }
        }
    }

    private fun pick(profile: Profile) {
        val changed = profile.id != ProfileManager.activeId.value
        ProfileManager.setActive(profile)
        if (isPicker) {
            setResult(Activity.RESULT_OK)
            finish()
            return
        }
        Toast.makeText(this, getString(R.string.profile_switched, profile.name), Toast.LENGTH_SHORT).show()
        if (!changed) return   // ya se estaba en ese perfil: no hay a dónde llevar a nadie
        goToHome()
    }

    /**
     * Sale de los ajustes y entra en la app con el perfil que se acaba de activar.
     *
     * Lo usan las DOS formas de cambiar de perfil desde esta pantalla —elegir uno y **crear uno
     * nuevo**, que también se activa—: en las dos, lo que el usuario acaba de hacer es *entrar* como
     * otra persona, no seguir administrando.
     *
     * ⚠️ `CLEAR_TOP | SINGLE_TOP` y no un `finish()`: entre `MainActivity` y esta pantalla está
     * `ProfileSettingsActivity`, así que cerrar solo esta dejaría al usuario en los ajustes del
     * perfil que acaba de abandonar. Con estos flags se cierra todo lo que hay por encima y
     * `MainActivity` se reutiliza **sin recrearse** (mismo patrón que la vuelta a la ficha desde el
     * reproductor); la pestaña la pone ella al ver que el perfil activo ha cambiado.
     */
    private fun goToHome() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    }

    /**
     * Deja el foco en el perfil ACTIVO. Antes se pedía `list.requestFocus()` a secas, que aterriza en
     * el primer hijo adjunto: el selector abría con el aro en la tarjeta 0 mientras el rótulo ACTIVO
     * estaba en otra, así que la vía rápida (abrir + CENTRO) cambiaba de perfil sin querer.
     *
     * Se delega en el adapter porque el foco solo se puede pedir cuando la tarjeta existe; ver
     * `ProfileAdapter.pendingFocusId`.
     */
    private fun focusActiveProfile(activeId: Long) {
        val pos = adapter.positionOf(activeId)
        if (pos >= 0) {
            (list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(pos, 0)
            adapter.requestFocusOn(activeId)
            // Si la tarjeta ya estaba bindeada, `onBindViewHolder` no volverá a correr: se pide aquí.
            list.post { list.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
        } else {
            list.post { list.requestFocus() }
        }
    }

    // ── Editor ─────────────────────────────────────────────────────────────────────────────────

    private fun openEditor(profile: Profile?) {
        editing = profile
        editorColor = profile?.colorIndex ?: nextFreeColor()
        editorTitle.setText(if (profile == null) R.string.profile_new else R.string.profile_edit)
        editorName.setText(profile?.name ?: "")
        editorName.setSelection(editorName.text.length)
        colorAdapter.setSelected(editorColor)
        // No se puede borrar mientras se crea, ni el único perfil que queda.
        confirmingDelete = false
        btnDelete.setText(R.string.profile_delete)
        btnDelete.visibility = if (profile == null) View.GONE else View.VISIBLE
        editorOverlay.visibility = View.VISIBLE
        // ⚠️ Bloquear la rejilla mientras el editor está abierto. Un overlay VISIBLE encima no aísla
        // nada: FocusFinder no sabe de oclusión, así que las tarjetas tapadas seguían entrando en la
        // búsqueda de foco y una pulsación IZQUIERDA desde el campo de texto saltaba a una tarjeta
        // invisible bajo el scrim — y CENTRO ahí cambiaba de perfil sin que se viera nada.
        setGridEnabled(false)
        editorName.post {
            editorName.requestFocus()
            showIme(editorName)
        }
    }

    /**
     * Primer color de la paleta que no esté ya en uso, para que dos perfiles nuevos no salgan
     * iguales. Si están todos cogidos se reparte por número de perfiles en vez de repetir siempre
     * el primero.
     */
    private fun nextFreeColor(): Int {
        val used = profiles.map { ProfileAvatars.normalize(it.colorIndex) }.toSet()
        return (0 until Profile.COLOR_COUNT).firstOrNull { it !in used }
            ?: (profiles.size % Profile.COLOR_COUNT)
    }

    private fun closeEditor() {
        hideIme(editorName)
        editorOverlay.visibility = View.GONE
        editing = null
        setGridEnabled(true)
        list.post { list.requestFocus() }
    }

    /** Saca (o devuelve) la rejilla de perfiles de la búsqueda de foco del D-pad. */
    private fun setGridEnabled(enabled: Boolean) {
        list.descendantFocusability =
            if (enabled) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        list.isFocusable = enabled
    }

    private fun saveEditor() {
        if (saving) return
        val name = editorName.text.toString().trim()
        if (name.isBlank()) {
            Toast.makeText(this, R.string.profile_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        val target = editing
        saving = true
        lifecycleScope.launch {
            if (target == null) {
                val created = local.createProfile(name, editorColor)
                // Un perfil recién creado se activa: crearlo y quedarse en el anterior es lo
                // contrario de lo que se acaba de pedir.
                ProfileManager.setActive(created)
                closeEditor()
                // Un perfil recién creado se activa, así que esto ya es un cambio de perfil: se
                // entra en la app como esa persona en vez de dejar al usuario en la pantalla de
                // administrar. En modo selector el destino es el mismo, solo que ahí basta con
                // cerrar: `MainActivity` está justo debajo.
                if (isPicker) { setResult(Activity.RESULT_OK); finish() } else goToHome()
            } else {
                local.renameProfile(target.id, name, editorColor)
                if (target.id == ProfileManager.requireActiveId()) {
                    ProfileManager.updateActive(name, editorColor)
                }
                closeEditor()
            }
            saving = false
        }
    }

    /** Pide confirmación antes de borrar: se van las listas, los vistos y los puntos de reanudación
     *  de esa persona, y no hay vuelta atrás. Antes bastaba un CENTRO. */
    private fun deleteEditing() {
        val target = editing ?: return
        if (saving) return
        if (!confirmingDelete) {
            confirmingDelete = true
            btnDelete.setText(R.string.profile_delete_confirm)
            Toast.makeText(
                this, getString(R.string.profile_delete_warning, target.name), Toast.LENGTH_LONG
            ).show()
            return
        }
        confirmingDelete = false
        saving = true
        lifecycleScope.launch {
            // ⚠️ Si el que se borra es el ACTIVO, se reapunta ANTES de borrarlo. Hacerlo después
            // deja una ventana en la que `requireActiveId()` devuelve un perfil que ya no existe, y
            // si la corrutina muere ahí (salir de la pantalla cancela `lifecycleScope`) la app pasa
            // el resto de la sesión escribiendo progreso y listas en un perfil fantasma.
            // `fallback` es null solo cuando no hay otro perfil, que es justo el caso en el que
            // `deleteProfile` se niega a borrar.
            val wasActive = target.id == ProfileManager.requireActiveId()
            val fallback = profiles.firstOrNull { it.id != target.id }
            if (wasActive && fallback != null) ProfileManager.setActive(fallback)

            val ok = local.deleteProfile(target.id)
            if (!ok) {
                // No se borró nada: deshacer el cambio de activo para no dejar al usuario en otro
                // perfil por un botón que no hizo nada.
                if (wasActive) ProfileManager.setActive(target)
                saving = false
                Toast.makeText(this@ProfilesActivity, R.string.profile_delete_last, Toast.LENGTH_LONG).show()
                return@launch
            }
            closeEditor()
            saving = false
            Toast.makeText(this@ProfilesActivity, R.string.profile_deleted, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showIme(view: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideIme(view: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(view.windowToken, 0)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (editorOverlay.visibility == View.VISIBLE) { closeEditor(); return true }
            // Como selector de arranque, BACK **sale de la app**, pero preguntando primero.
            //
            // Antes se tragaba el BACK para que nadie llegara a MainActivity sin haber elegido. Pero
            // el selector aparece en CADA arranque, así que tragárselo dejaba al usuario atrapado en
            // la puerta de entrada, sin más salida que HOME. Y dejarlo pasar tampoco vale: caería en
            // la app con un perfil que no ha elegido, justo lo que se quiere evitar.
            //
            // Al confirmar, `finishAffinity()` cierra también MainActivity, que está debajo en la
            // misma tarea: cerrar solo el selector devolvería al usuario a la app sin haber elegido.
            if (isPicker) return exitConfirm.onBack()
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val EXTRA_PICKER = "picker"
    }
}
