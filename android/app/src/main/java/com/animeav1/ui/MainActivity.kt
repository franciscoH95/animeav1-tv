package com.animeav1.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.animeav1.R
import com.animeav1.data.LocalRepository
import com.animeav1.data.ProfileManager
import com.animeav1.data.UpdateChecker
import com.animeav1.data.local.AppDatabase
import com.animeav1.ui.browse.BrowseFragment
import com.animeav1.ui.browse.SearchActivity
import com.animeav1.ui.home.HomeFragment
import com.animeav1.ui.mylist.MyListFragment
import com.animeav1.ui.profile.ProfileAvatars
import com.animeav1.ui.profile.ProfileSettingsActivity
import com.animeav1.ui.profile.ProfilesActivity
import com.animeav1.ui.schedule.ScheduleFragment
import com.animeav1.ui.update.UpdateActivity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private lateinit var tabHome:     Button
    private lateinit var tabCatalog:  Button
    private lateinit var tabSchedule: Button
    private lateinit var tabMyList:   Button
    private var activeTab = 0

    private val tabTags = arrayOf("home", "catalog", "schedule", "mylist")

    /** Focus memory per tab. For RecyclerView items the adapter position is what's stable —
     *  the View itself can be re-bound to a different item while the tab is hidden. */
    private data class FocusMemory(val view: View, val recycler: RecyclerView?, val position: Int)

    // Last focused view inside each section, so returning to a tab restores where you were.
    private val lastFocus = arrayOfNulls<FocusMemory>(4)
    private var focusListener: ViewTreeObserver.OnGlobalFocusChangeListener? = null

    /** Acciones globales de la barra. NO son pestañas: no entran en `tabTags` ni en `lastFocus[]`. */
    private lateinit var actionSearch: Button
    /** El contenedor del avatar: es lo focusable y lo que lleva el aro de foco. */
    private lateinit var actionProfile: View
    /** El círculo con la inicial, dentro del contenedor. Lo tiñe `ProfileAvatars`. */
    private lateinit var actionProfileAvatar: TextView

    /** "¿Cerrar la app?" — BACK en esta pantalla cierra, así que se pregunta antes. */
    private lateinit var exitConfirm: ExitConfirm

    /** Perfil con el que está pintado el contenido de las pestañas; ver [onProfileSwitched]. */
    private var shownProfileId = ProfileManager.activeId.value

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tabHome     = findViewById(R.id.tab_home)
        tabCatalog  = findViewById(R.id.tab_catalog)
        tabSchedule = findViewById(R.id.tab_schedule)
        tabMyList   = findViewById(R.id.tab_mylist)

        actionSearch        = findViewById(R.id.action_search)
        actionProfile       = findViewById(R.id.action_profile)
        actionProfileAvatar = findViewById(R.id.action_profile_avatar)

        // Buscar desde cualquier pestaña, no solo desde el Catálogo.
        actionSearch.setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        // El avatar abre los AJUSTES del perfil (perfiles + copia), no directamente la gestión: es la
        // única entrada a la copia de seguridad desde que salió de la cabecera de Mi Lista.
        actionProfile.setOnClickListener {
            startActivity(Intent(this, ProfileSettingsActivity::class.java))
        }
        // El avatar se pinta con el perfil activo: es lo que dice "estás en Ana", sin gastar una
        // pestaña ni depender de entrar en Mi Lista.
        // ⚠️ Nombre y color se COMBINAN: leer el color aparte dejaba el avatar con el color anterior
        // al editar el perfil activo (y sin repintar nada si solo cambiaba el color, porque el
        // StateFlow del nombre conflata el valor igual).
        lifecycleScope.launch {
            ProfileManager.activeName
                .combine(ProfileManager.activeColorIndex) { name, color -> name to color }
                .collect { (name, color) -> ProfileAvatars.paint(actionProfileAvatar, name, color) }
        }

        // Cambiar de perfil devuelve a Inicio: las otras pestañas siguen enseñando el contenido de
        // la persona anterior hasta que Room reemite, y Mi Lista es literalmente "la lista de otro".
        // ⚠️ Va con `repeatOnLifecycle(STARTED)` y no con un `collect` pelado: el cambio ocurre
        // mientras esta Activity está PARADA (el usuario está en la pantalla de perfiles), y hacer
        // ahí el `commit()` de la transacción de fragments revienta con IllegalStateException por
        // llegar después de `onSaveInstanceState`. Con esto se aplica al volver, que es cuando se ve.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ProfileManager.activeId.collect { id ->
                    if (id == shownProfileId) return@collect
                    shownProfileId = id
                    onProfileSwitched()
                }
            }
        }

        tabHome.setOnClickListener     { selectTab(0) }
        tabCatalog.setOnClickListener  { selectTab(1) }
        tabSchedule.setOnClickListener { selectTab(2) }
        tabMyList.setOnClickListener   { selectTab(3) }

        // Continuously remember the last focused view within the visible section.
        val container = findViewById<View>(R.id.fragment_container)
        focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus != null && isDescendantOf(newFocus, container)) {
                val item = recyclerItemOf(newFocus, container)
                val rv = item?.parent as? RecyclerView
                lastFocus[activeTab] = FocusMemory(
                    newFocus, rv, if (item != null && rv != null) rv.getChildAdapterPosition(item) else -1
                )
            }
        }
        container.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)

        // Actualizaciones OTA. ⚠️ En RESUMED y no en onCreate: al arrancar, el selector de perfiles
        // está ENCIMA de esta Activity, y lanzar ahí el aviso lo apilaría sobre él (o peor, lo
        // taparía antes de que el usuario haya elegido con quién entra). Resumido = el usuario está
        // de verdad en la app. El intervalo de un día lo pone `UpdateChecker`, así que volver del
        // reproductor no vuelve a preguntar.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                UpdateChecker.pending(this@MainActivity)?.let { info ->
                    startActivity(UpdateActivity.intent(this@MainActivity, info))
                }
            }
        }

        exitConfirm = ExitConfirm(
            overlay = findViewById(R.id.exit_overlay),
            content = findViewById(R.id.main_content),
            onExit  = { finish() }
        )

        activeTab = savedInstanceState?.getInt("activeTab", 0) ?: 0
        selectTab(activeTab)

        // El selector es la puerta de entrada de la app: se pregunta SIEMPRE al abrirla, también
        // cuando hay un solo perfil.
        //
        // Se lanza aquí, de forma síncrona, y NO dentro de resolveProfile(): esa función tiene que
        // esperar a Room, y con el selector saliendo en cada arranque ese hueco se veía como un
        // parpadeo de Inicio detrás antes de que apareciera. ProfilesActivity se encarga ella misma
        // de que exista al menos un perfil.
        //
        // ⚠️ `savedInstanceState == null` = arranque de verdad. Con estado guardado venimos de una
        // rotación o de que el sistema mató el proceso y restauró la sesión donde estaba; preguntar
        // ahí no es "entrar en la app", es interrumpir. Volver del reproductor o de la ficha tampoco
        // pasa por aquí: MainActivity no se recrea, solo se reanuda.
        if (savedInstanceState == null) {
            startActivity(Intent(this, ProfilesActivity::class.java).apply {
                putExtra(ProfilesActivity.EXTRA_PICKER, true)
            })
        }
        resolveProfile()
    }

    /**
     * Asegura que el perfil activo apunta a uno que existe de verdad y refresca su nombre.
     *
     * Corre siempre, incluso cuando el selector va a preguntar por encima: `ProfileManager` guarda
     * el id en preferencias pero el **nombre** solo lo sabe tras un `setActive`, y sin esto el botón
     * de perfil de Mi Lista aparecía vacío tras una recreación de la Activity.
     */
    private fun resolveProfile() {
        lifecycleScope.launch {
            val local = LocalRepository(AppDatabase.get(applicationContext))
            // Repara el caso de una instalación limpia (nadie creó el perfil por defecto) o un id
            // guardado que apunta a un perfil ya borrado.
            ProfileManager.setActive(local.ensureActiveProfile())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("activeTab", activeTab)
    }

    override fun onDestroy() {
        focusListener?.let {
            findViewById<View>(R.id.fragment_container)?.viewTreeObserver?.removeOnGlobalFocusChangeListener(it)
        }
        super.onDestroy()
    }

    /**
     * Pulsaciones de tecla atendidas. Solo sirve para una cosa: que la colocación del foco inicial de
     * una sección (ver `HomeFragment.watchForFocusTarget`) sepa que el usuario **ya ha empezado a
     * navegar** y deje de tener derecho a mover el foco. Sin esto, una fila que termina de cargar le
     * arrancaba el foco de la pestaña a la que el usuario acababa de llegar.
     */
    var keyTicks = 0
        private set

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) keyTicks++
        val container = findViewById<View>(R.id.fragment_container)
        if (event.action == KeyEvent.ACTION_DOWN && container != null) {
            val focused = currentFocus
            when (event.keyCode) {
                // BACK **sube un nivel** por la misma escalera que ARRIBA (rejilla → menú de la
                // sección → pestañas) y solo pregunta si cerrar cuando ya no queda nivel al que
                // subir. Antes preguntaba desde cualquier punto: metido en la fila 15 del catálogo,
                // BACK sacaba el overlay de cerrar y la única forma de llegar a las pestañas era
                // pulsar ARRIBA una vez por fila.
                //
                // A diferencia de ARRIBA, aquí se salta de golpe: no se recorre el scroll. El scroll
                // se deja donde estaba y `lastFocus[]` devuelve al usuario a la misma tarjeta cuando
                // vuelve a bajar.
                KeyEvent.KEYCODE_BACK -> {
                    if (exitConfirm.isVisible) return exitConfirm.onBack()
                    if (focused != null && isDescendantOf(focused, container)) {
                        // Estando ya EN el menú, el siguiente nivel son las pestañas. ⚠️ Antes la
                        // condición era "el foco está dentro de un RecyclerView", que valía cuando el
                        // menú del Catálogo era un EditText: al pasar a ser la fila de chips —que es un
                        // RecyclerView— el menú se re-enfocaba a sí mismo y BACK dejaba de subir.
                        val menu = sectionMenu(container)
                        val inMenu = hasAncestorId(focused, R.id.section_menu)
                        if (!inMenu && menu != null && menu.requestFocus()) return true
                        activeTabButton().requestFocus()
                        return true
                    }
                    // Ya en la barra de pestañas (o sin foco): el siguiente nivel es salir.
                    return exitConfirm.onBack()
                }
                // Down from a tab restores where you last were in this section (focus memory),
                // falling back to the first menu item on a section you haven't entered yet.
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (focused != null && isTabButton(focused)) {
                        if (restoreRememberedFocus()) return true
                        if (firstFocusable(container)?.requestFocus() == true) return true
                        // Nothing focusable below (empty Inicio, section still loading): keep the
                        // focus on the tab instead of dropping it on an invisible container.
                        return true
                    }
                }
                // Up moves grid → section menu → tabs (symmetric with going down).
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (focused != null && isDescendantOf(focused, container)) {
                        if (!ancestorCanScrollUp(focused, container)) {
                            val up = focused.focusSearch(View.FOCUS_UP)
                            if (up == null || !isDescendantOf(up, container)) {
                                activeTabButton().requestFocus()
                                return true
                            }
                        }
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Salto a Catálogo desde fuera (el estado vacío de Inicio). Pasa por `selectTab` para que la
     *  pestaña quede marcada y el foco recordado siga funcionando. */
    fun openCatalogTab() {
        selectTab(1)
        tabCatalog.requestFocus()
    }

    private fun isTabButton(v: View) =
        v === tabHome || v === tabCatalog || v === tabSchedule || v === tabMyList

    /** Walks up from a focused view to the direct child of the RecyclerView containing it. */
    private fun recyclerItemOf(view: View, root: View): View? {
        var v: View? = view
        while (v != null && v !== root) {
            val parent = v.parent as? View ?: return null
            if (parent is RecyclerView) return v
            v = parent
        }
        return null
    }

    /** Restores the remembered focus, preferring the adapter position over the raw View (the
     *  View may have been re-bound to another item while the tab was hidden). */
    private fun restoreRememberedFocus(): Boolean {
        val mem = lastFocus[activeTab] ?: return false
        val rv = mem.recycler
        if (rv != null && rv.isAttachedToWindow && rv.isShown && mem.position >= 0) {
            val held = rv.findViewHolderForAdapterPosition(mem.position)
            if (held?.itemView?.requestFocus() == true) return true
        }
        return mem.view.isAttachedToWindow && mem.view.isShown && mem.view.requestFocus()
    }

    /** First focusable view (in traversal order) inside the section — i.e. its first menu item.
     *  Bare scroll containers don't count: focusing them shows no visible focus state. */
    /**
     * El "menú" de la sección: la fila de controles de su cabecera (los filtros del Catálogo, los
     * días del Horario, las sub-listas de Mi Lista), marcada en cada layout con `@id/section_menu`.
     * Inicio no la tiene, así que ahí BACK va derecho a las pestañas.
     *
     * ⚠️ Marcado explícito y no heurística. Antes el menú era "el primer focusable que no vive dentro
     * de un RecyclerView", que funcionaba solo porque el Catálogo tenía una barra de búsqueda (un
     * EditText): al quitarla, su primer focusable pasó a ser un chip de filtro —que sí vive dentro de
     * un RecyclerView— y BACK se saltaba el nivel del menú de golpe hasta las pestañas.
     *
     * ⚠️ Se parte de los focusables y NO de `container.findViewById(R.id.section_menu)`: los fragments
     * de los tabs se ocultan con `hide()`, no se destruyen, así que un findViewById encontraría el
     * menú de otra sección. `addFocusables` solo recorre lo VISIBLE.
     */
    private fun sectionMenu(container: View): View? {
        val focusables = ArrayList<View>()
        container.addFocusables(focusables, View.FOCUS_DOWN)
        // ⚠️ Mismo filtro que `firstFocusable`: un `HorizontalScrollView` es focusable por su
        // constructor y un RecyclerView vacío también acepta el foco. En el Horario sin red los
        // botones de día no existen todavía, así que el único focusable del menú era el propio scroll:
        // BACK dejaba el foco ahí, sin ningún indicador en pantalla y sin nada que hacer con CENTRO.
        return focusables.firstOrNull { canHostFocus(it) && hasAncestorId(it, R.id.section_menu) }
    }

    /** Puede recibir el foco y hacer algo con él: ni un contenedor de scroll pelado ni una lista vacía. */
    private fun canHostFocus(v: View): Boolean =
        v !is NestedScrollView && v !is ScrollView && v !is HorizontalScrollView &&
            !(v is RecyclerView && v.childCount == 0)

    private fun hasAncestorId(view: View, id: Int): Boolean {
        var v: View? = view
        while (v != null) {
            if (v.id == id) return true
            v = v.parent as? View
        }
        return false
    }

    private fun firstFocusable(container: View): View? {
        val focusables = ArrayList<View>()
        container.addFocusables(focusables, View.FOCUS_DOWN)
        return focusables.firstOrNull { it !== container && canHostFocus(it) }
    }

    private fun ancestorCanScrollUp(view: View, root: View): Boolean {
        var v: View? = view
        while (v != null && v !== root) {
            if (v.canScrollVertically(-1)) return true
            v = v.parent as? View
        }
        return false
    }

    private fun isDescendantOf(child: View, ancestor: View): Boolean {
        var v: View? = child
        while (v != null) {
            if (v === ancestor) return true
            v = v.parent as? View
        }
        return false
    }

    private fun activeTabButton() = when (activeTab) {
        0    -> tabHome
        2    -> tabSchedule
        3    -> tabMyList
        else -> tabCatalog
    }

    /**
     * Show the chosen section, keeping the others alive (hidden) so their state — filters,
     * search, selected sub-list, loaded pages and scroll position — survives tab switches.
     */
    private fun selectTab(index: Int) {
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        tabTags.forEachIndexed { i, tag ->
            fm.findFragmentByTag(tag)?.let { if (i != index) tx.hide(it) }
        }
        val target = fm.findFragmentByTag(tabTags[index])
        if (target == null) {
            tx.add(R.id.fragment_container, newFragment(index), tabTags[index])
        } else {
            tx.show(target)
        }
        tx.commit()
        activeTab = index
        updateTabStyle()
    }

    /**
     * Vuelve a Inicio tras un cambio de perfil.
     *
     * ⚠️ El foco recordado de cada pestaña (`lastFocus[]`) se tira: apunta a tarjetas del perfil
     * ANTERIOR, que ya no están en la lista.
     *
     * El foco solo se coloca a mano cuando había que cambiar de pestaña —esconder el fragment que
     * lo tenía lo expulsa, y Android lo reasigna al primer focusable de la ventana—. Estando ya en
     * Inicio se deja en paz a propósito: es el caso del arranque (el selector de perfiles activa el
     * perfil justo después de crearse esta Activity) y ahí el foco inicial lo coloca
     * `HomeFragment`, que sabe esperar a que su primera fila tenga tarjetas.
     */
    private fun onProfileSwitched() {
        lastFocus.fill(null)
        if (activeTab != 0) {
            selectTab(0)
            tabHome.requestFocus()
        }
        // Y que Inicio recoloque el foco como en una entrada nueva: las secciones locales de la otra
        // persona aparecen ARRIBA y empujan fuera de pantalla lo que estuviera enfocado.
        (supportFragmentManager.findFragmentByTag(tabTags[0]) as? HomeFragment)?.rearmInitialFocus()
    }

    private fun newFragment(index: Int): Fragment = when (index) {
        0    -> HomeFragment()
        2    -> ScheduleFragment()
        3    -> MyListFragment()
        else -> BrowseFragment()
    }

    private fun updateTabStyle() {
        tabHome.isSelected     = (activeTab == 0)
        tabCatalog.isSelected  = (activeTab == 1)
        tabSchedule.isSelected = (activeTab == 2)
        tabMyList.isSelected   = (activeTab == 3)
    }
}
