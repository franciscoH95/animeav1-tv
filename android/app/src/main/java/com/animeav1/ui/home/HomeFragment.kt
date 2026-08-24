package com.animeav1.ui.home

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.animeav1.R
import com.animeav1.data.local.ContinueItem
import com.animeav1.data.local.FavoriteSeries
import androidx.annotation.StringRes
import com.animeav1.data.local.ListType
import com.animeav1.data.model.Anime
import com.animeav1.viewmodel.HomeViewModel
import com.animeav1.ui.MainActivity
import com.animeav1.ui.RowLayoutManager
import com.animeav1.ui.SectionScrollView
import com.animeav1.ui.rescuingFocus
import com.animeav1.ui.browse.SkeletonAdapter
import com.animeav1.ui.player.PlayerActivity
import com.animeav1.ui.series.SeriesActivity
import com.animeav1.viewmodel.LocalViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private lateinit var localVm: LocalViewModel
    private lateinit var homeVm: HomeViewModel

    /** Vistas de cada fila de descubrimiento, para poder actualizarlas sin volver a inflar. */
    private val discoveryRows = mutableMapOf<HomeViewModel.Kind, DiscoveryRow>()

    /** Fila cuyo "Reintentar" tenía el foco cuando se escondió, para devolvérselo al reaparecer. */
    private var retryAwaitingFocus: HomeViewModel.Kind? = null

    /** Las vistas que componen una fila de descubrimiento, más el pulso de su skeleton. */
    private class DiscoveryRow(
        val recycler: RecyclerView,
        val skeleton: RecyclerView,
        val retry: View,
        val adapter: CatalogRowAdapter
    ) {
        var pulse: ObjectAnimator? = null
    }
    private lateinit var scroll: SectionScrollView
    private lateinit var continueSection: View
    private lateinit var viendoSection: View
    private lateinit var emptyView: View
    private lateinit var continueRecycler: RecyclerView
    private lateinit var viendoRecycler: RecyclerView
    private lateinit var porverRecycler: RecyclerView
    private lateinit var porverSection: View

    /** El foco inicial se pide UNA vez, cuando llega el primer contenido. Después no se toca: los
     *  Flow de Room re-emiten al volver del reproductor y robar el foco ahí sería peor que no darlo. */
    private var focusPlaced = false

    /** Coloca el foco aunque ya esté dentro del fragment; ver [rearmInitialFocus]. */
    private var forceFocusPlacement = false
    private lateinit var continueAdapter: ContinueAdapter
    private lateinit var viendoAdapter: HomePosterAdapter
    private lateinit var porverAdapter: HomePosterAdapter

    /** Listener único de layout mientras se busca dónde poner el foco inicial. */
    private var focusListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    /** Pulsaciones de tecla que llevaba MainActivity cuando se armó el listener. Ver [watchForFocusTarget]. */
    private var armedAtKeyTicks = 0

    private var continueEmpty = true
    private var viendoEmpty = true
    private var porverEmpty = true

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        localVm = ViewModelProvider(requireActivity())[LocalViewModel::class.java]
        homeVm = ViewModelProvider(requireActivity())[HomeViewModel::class.java]

        scroll          = view.findViewById(R.id.home_scroll)
        continueSection = view.findViewById(R.id.continue_section)
        viendoSection   = view.findViewById(R.id.viendo_section)
        porverSection   = view.findViewById(R.id.porver_section)
        emptyView       = view.findViewById(R.id.home_empty)

        // El scroll ancla arriba la fila que reciba el foco, y para eso necesita saber cuáles son:
        // las locales cuelgan del contenido y las de descubrimiento de otro contenedor, así que no
        // hay una regla estructural que valga para las dos (ver HomeRowsScrollView).
        listOf(continueSection, viendoSection, porverSection).forEach { scroll.registerRow(it) }

        view.findViewById<View>(R.id.btn_home_explore).setOnClickListener {
            // Lo único que puede hacer un usuario sin nada visto: llevarlo al catálogo.
            (activity as? MainActivity)?.openCatalogTab()
        }

        continueRecycler = view.findViewById(R.id.continue_recycler)
        continueAdapter = ContinueAdapter(
            onClick     = { item -> playContinue(item) },
            onLongClick = { item -> confirmRemoveContinue(item) }
        )
        continueRecycler.layoutManager = RowLayoutManager(requireContext())
        continueRecycler.adapter = continueAdapter
        continueRecycler.addItemDecoration(RowSpacing(10))

        viendoRecycler = view.findViewById(R.id.viendo_recycler)
        viendoAdapter = HomePosterAdapter { fav -> openSeries(fav) }
        viendoRecycler.layoutManager = RowLayoutManager(requireContext())
        viendoRecycler.adapter = viendoAdapter
        viendoRecycler.addItemDecoration(RowSpacing(10))

        porverRecycler = view.findViewById(R.id.porver_recycler)
        porverAdapter = HomePosterAdapter { fav -> openSeries(fav) }
        porverRecycler.layoutManager = RowLayoutManager(requireContext())
        porverRecycler.adapter = porverAdapter
        porverRecycler.addItemDecoration(RowSpacing(10))

        buildDiscoveryRows(view)
        homeVm.load()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                homeVm.rows.collectLatest { rows ->
                    rows.forEach { (kind, row) -> bindDiscoveryRow(kind, row) }
                    updateEmpty()
                }
            }
        }

        // STARTED-scoped: don't keep diffing Room updates (progress saves every 5s) while the
        // whole MainActivity is in background during playback; Room flows re-emit on restart.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    localVm.repo.getContinueWatching().collectLatest { list ->
                        val hadFocus = view.findFocus() != null
                        continueAdapter.submitList(list) { rescueAfterDiff(hadFocus) }
                        continueEmpty = list.isEmpty()
                        val rescued = rescuingFocus(view, { bestFocusTarget(homeIsEmpty()) }) {
                            continueSection.visibility = if (continueEmpty) View.GONE else View.VISIBLE
                        }
                        updateEmpty()
                        if (!rescued) { focusPlaced = false; watchForFocusTarget() }
                    }
                }
                launch {
                    localVm.repo.getByList(ListType.VIENDO).collectLatest { list ->
                        val hadFocus = view.findFocus() != null
                        viendoAdapter.submitList(list) { rescueAfterDiff(hadFocus) }
                        viendoEmpty = list.isEmpty()
                        val rescued = rescuingFocus(view, { bestFocusTarget(homeIsEmpty()) }) {
                            viendoSection.visibility = if (viendoEmpty) View.GONE else View.VISIBLE
                        }
                        updateEmpty()
                        if (!rescued) { focusPlaced = false; watchForFocusTarget() }
                    }
                }
                launch {
                    localVm.repo.getByList(ListType.POR_VER).collectLatest { list ->
                        val hadFocus = view.findFocus() != null
                        porverAdapter.submitList(list) { rescueAfterDiff(hadFocus) }
                        porverEmpty = list.isEmpty()
                        val rescued = rescuingFocus(view, { bestFocusTarget(homeIsEmpty()) }) {
                            porverSection.visibility = if (porverEmpty) View.GONE else View.VISIBLE
                        }
                        updateEmpty()
                        if (!rescued) { focusPlaced = false; watchForFocusTarget() }
                    }
                }
            }
        }
    }

    /** Los pulsos son infinitos: una fila que se quede cargando para siempre dejaría un animador
     *  corriendo contra una vista ya destruida. */
    override fun onDestroyView() {
        stopWatching()
        discoveryRows.values.forEach { it.pulse?.cancel(); it.pulse = null }
        discoveryRows.clear()
        super.onDestroyView()
    }

    /** Infla una fila por cada [HomeViewModel.Kind], en el orden del enum. */
    private fun buildDiscoveryRows(view: View) {
        val container = view.findViewById<ViewGroup>(R.id.discovery_rows)
        val inflater = LayoutInflater.from(requireContext())
        HomeViewModel.Kind.values().forEach { kind ->
            val rowView = inflater.inflate(R.layout.view_home_row, container, false)
            rowView.findViewById<TextView>(R.id.row_title).setText(titleOf(kind))
            val recycler = rowView.findViewById<RecyclerView>(R.id.row_recycler)
            val adapter = CatalogRowAdapter { anime -> openCatalogSeries(anime) }
            recycler.layoutManager = RowLayoutManager(requireContext())
            recycler.adapter = adapter
            recycler.addItemDecoration(RowSpacing(10))
            val skeleton = rowView.findViewById<RecyclerView>(R.id.row_skeleton)
            skeleton.layoutManager =
                LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            // El ancho es el de item_home_poster: los huecos tienen que caer donde caerán las tarjetas.
            skeleton.adapter = SkeletonAdapter(count = 7, widthRes = R.dimen.home_card_width)
            skeleton.addItemDecoration(RowSpacing(10))
            val retry = rowView.findViewById<View>(R.id.row_retry)
            retry.setOnClickListener { homeVm.retry(kind) }
            container.addView(rowView)
            scroll.registerRow(rowView)
            discoveryRows[kind] = DiscoveryRow(recycler, skeleton, retry, adapter)
        }
    }

    private fun bindDiscoveryRow(kind: HomeViewModel.Kind, row: HomeViewModel.Row) {
        val views = discoveryRows[kind] ?: return
        views.adapter.submitList(row.items)
        // Pulsar "Reintentar" esconde el propio botón enfocado. Se recuerda cuál era para devolverle
        // el foco cuando vuelva a estar visible: sin esto el usuario tenía que volver a bajar toda la
        // página en CADA intento, porque el rescate lo mandaba a la primera fila con contenido.
        if (views.retry.isFocused && !row.failed) retryAwaitingFocus = kind
        val rescued = rescuingFocus(view, { bestFocusTarget(homeIsEmpty()) }) {
            views.retry.visibility = if (row.failed) View.VISIBLE else View.GONE
            setSkeleton(views, row.loading)
            // ⚠️ Sin tarjetas, la fila NO debe ser focusable: con `afterDescendants` y ningún hijo
            // focusable el foco se para en el RecyclerView vacío y el D-pad se queda ahí sin nada que
            // hacer. (Las filas locales resuelven lo mismo escondiendo la sección entera.)
            views.recycler.isFocusable = row.items.isNotEmpty()
            // La fila entera se esconde si no hay nada que mostrar y no hay nada que reintentar: un
            // título solo, sin tarjetas debajo, parece un fallo de la app.
            val parent = views.recycler.parent as? View
            parent?.visibility =
                if (row.items.isEmpty() && !row.failed && !row.loading) View.GONE else View.VISIBLE
        }
        if (!rescued) { focusPlaced = false; watchForFocusTarget() }
        // El botón ha vuelto (el reintento también falló): el foco vuelve con él.
        if (row.failed && retryAwaitingFocus == kind && view?.findFocus() == null) {
            if (views.retry.requestFocus()) retryAwaitingFocus = null
        }
    }

    /** Pulso de alpha idéntico al del catálogo, para que cargar se vea igual en toda la app. */
    private fun setSkeleton(views: DiscoveryRow, loading: Boolean) {
        views.skeleton.visibility = if (loading) View.VISIBLE else View.GONE
        views.pulse?.cancel()
        views.pulse = null
        views.skeleton.alpha = 1f
        if (!loading) return
        views.pulse = ObjectAnimator.ofFloat(views.skeleton, View.ALPHA, 0.4f, 0.9f).apply {
            duration = 700
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    @StringRes
    private fun titleOf(kind: HomeViewModel.Kind) = when (kind) {
        HomeViewModel.Kind.EN_EMISION      -> R.string.home_row_airing
        HomeViewModel.Kind.MEJOR_VALORADOS -> R.string.home_row_top
        HomeViewModel.Kind.PELICULAS       -> R.string.home_row_movies
    }

    /**
     * Mantener pulsado sobre una tarjeta de *Continuar viendo*: quitar la serie de la fila.
     *
     * Se pregunta antes porque la acción **borra el punto de reanudación** —el episodio vuelve a
     * empezar desde el principio— y el gesto que la dispara es el mismo que abre la ficha con una
     * pulsación normal: un mantenido accidental no puede tirar por la borda media hora de
     * episodio sin avisar. El diálogo dice qué se borra y qué no.
     */
    private fun confirmRemoveContinue(item: ContinueItem) {
        val dialog = AlertDialog.Builder(requireContext(), android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.continue_remove_title)
            .setMessage(getString(R.string.continue_remove_msg, item.title, item.episodeNumber))
            .setPositiveButton(R.string.continue_remove) { _, _ ->
                localVm.removeFromContinue(item.slug)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        // ⚠️ El foco se pide a mano. Un AlertDialog con botones aterriza el foco en el `buttonPanel`
        // que los CONTIENE, no en un botón: en pantalla no se ve ningún indicador y CENTRO no hace
        // nada, así que con un mando el diálogo parece colgado (comprobado en el emulador). Va a
        // "Quitar" y no a "Cancelar" por el mismo criterio que la confirmación de salida: quien ha
        // mantenido pulsado quiere quitarla, y así la acción son dos pulsaciones deliberadas.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
        }
        dialog.show()
    }

    private fun openCatalogSeries(anime: Anime) {
        startActivity(Intent(requireContext(), SeriesActivity::class.java).apply {
            putExtra("slug", anime.slug)
            putExtra("title", anime.title)
        })
    }

    private fun updateEmpty() {
        val empty = homeIsEmpty()
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        placeInitialFocus(empty)
    }

    /** Con filas de descubrimiento, Inicio solo está de verdad vacío si tampoco cargó el catálogo
     *  (típicamente por falta de red en una instalación nueva). Mientras una fila carga NO se da por
     *  vacío: el estado vacío es accionable y aparecer y desaparecer sería peor que esperar. */
    private fun homeIsEmpty(): Boolean {
        val discoveryEmpty = homeVm.rows.value.values.all { it.items.isEmpty() && !it.loading }
        return continueEmpty && viendoEmpty && porverEmpty && discoveryEmpty
    }

    /**
     * Aterriza el foco en lo primero útil de Inicio la primera vez que hay algo que enfocar.
     *
     * Sin esto el foco se queda en la pestaña INICIO y reanudar cuesta ABAJO + CENTRO en cada
     * arranque, cuando reanudar es justo la razón de que Inicio sea la pantalla de aterrizaje.
     *
     * Solo actúa si el foco no está ya dentro de este fragment: si el usuario ya ha navegado, o si
     * `MainActivity` restauró su foco recordado, no se le mueve nada.
     */
    private fun placeInitialFocus(empty: Boolean) {
        if (focusPlaced || !isVisible) return
        // Si el usuario ya está navegando dentro de Inicio, no se le mueve nada.
        if (view?.findFocus() != null) { focusPlaced = true; return }
        if (tryFocus(bestFocusTarget(empty))) return
        watchForFocusTarget()
    }

    /**
     * Dónde debería estar el foco AHORA MISMO, por prioridad: reanudar antes que las listas, y las
     * listas antes que descubrir.
     *
     * ⚠️ Se recalcula en cada intento en vez de fijar un destino la primera vez. Las filas se llenan
     * en el orden en que responden Room y la red, no en el orden en que se ven: apuntando a la
     * primera fila que resultara no estar vacía, el foco aterrizaba en *Mi lista · Viendo* cuando su
     * consulta ganaba la carrera a la de *Continuar viendo* (medido en el emulador).
     *
     * `childCount > 0` es la comprobación buena y no "la lista no está vacía": una fila recién
     * poblada tarda un layout en tener hijos, y un `requestFocus()` sobre un RecyclerView sin hijos
     * falla en silencio.
     */
    private fun bestFocusTarget(empty: Boolean): View? {
        if (empty) return view?.findViewById<View>(R.id.btn_home_explore)?.takeIf { it.isShown }
        val locals = listOf(continueRecycler, viendoRecycler, porverRecycler)
        return locals.firstOrNull { it.canHostFocus() }
            ?: discoveryRows.values.firstOrNull { it.recycler.canHostFocus() }?.recycler
            // Último recurso: un "Reintentar" visible. Cuando las filas de descubrimiento fallan es
            // lo ÚNICO focusable de Inicio, y sin esto el rescate devolvía null y dejaba el foco en la
            // barra de pestañas con botones sin usar en pantalla.
            ?: discoveryRows.values.firstOrNull { it.retry.isShown }?.retry
    }

    /**
     * Puede recibir el foco de verdad: está mostrada **y** tiene algún hijo.
     *
     * ⚠️ `isShown` es la mitad que faltaba. Una sección que acaba de pasar a GONE conserva sus hijos
     * (el diff de `submitList` es asíncrono y un contenedor GONE ya no se dispone, así que el
     * RecyclerView no recicla nada), y `requestFocus()` NO mira la visibilidad de los ancestros: el
     * rescate metía el foco justo en la fila que lo había provocado, dejándolo invisible sobre una
     * tarjeta que ya no existe —y, al cambiar de perfil, sobre los pósters del perfil anterior—.
     */
    private fun View.canHostFocus(): Boolean =
        isShown && this is RecyclerView && childCount > 0

    /**
     * Reintenta en cada layout hasta que haya algo que enfocar.
     *
     * ⚠️ Un `post {}` NO basta con filas que se llenan desde un Flow: en ese momento todavía no
     * tienen hijos, así que `requestFocus()` falla en silencio y el foco se queda en la pestaña.
     * El listener va en la raíz del fragment (no en una fila concreta) y se registra UNA vez.
     */
    private fun watchForFocusTarget() {
        if (focusListener != null) return
        val root = view ?: return
        armedAtKeyTicks = keyTicks()
        focusListener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (focusPlaced) return
                // ⚠️ En cuanto el usuario pulsa una tecla, este listener deja de tener derecho a mover
                // el foco: la colocación inicial es para cuando NADIE lo ha puesto todavía. Sin esto,
                // el usuario que subía a la barra y caminaba hasta otra pestaña se encontraba con que
                // la fila que acababa de cargar le arrancaba el foco de la pestaña — y el CENTRO que
                // ya tenía en el dedo abría una ficha en vez de cambiar de sección.
                if (keyTicks() != armedAtKeyTicks) { stopWatching(); return }
                // ⚠️ Desregistrar, no solo salir: el ViewTreeObserver es el de la VENTANA, así que
                // este listener sigue disparando mientras el usuario está en Catálogo, y al volver a
                // Inicio se llevaba el foco de la pestaña a una fila sin que nadie lo pidiera.
                if (!isVisible) { stopWatching(); return }
                // Alguien se adelantó y cogió el foco: se deja donde está. (Salvo tras un cambio
                // de perfil, donde recolocarlo es justo lo que se ha pedido; ver rearmInitialFocus.)
                if (!forceFocusPlacement && root.findFocus() != null) { focusPlaced = true; stopWatching(); return }
                if (tryFocus(bestFocusTarget(homeIsEmpty()))) stopWatching()
            }
        }
        root.viewTreeObserver.addOnGlobalLayoutListener(focusListener)
    }

    /**
     * Rescate para lo que `rescuingFocus` **no puede ver**: el diff de `submitList` es asíncrono.
     *
     * ⚠️ `rescuingFocus` mira el foco justo alrededor del cambio de visibilidad, pero la tarjeta
     * enfocada no se retira ahí: se va cuando el diff se aplica, un momento después. Así que da el
     * rescate por innecesario —el foco seguía dentro cuando miró— y ya no vuelve nadie a mirar.
     * El caso que se escapa: la sección sigue VISIBLE y solo desaparece la tarjeta enfocada —tres
     * series en *Continuar viendo* y se termina una—. Escondiendo la sección entera sí lo caza
     * `rescuingFocus`, porque ese cambio de visibilidad sí es síncrono.
     *
     * Solo actúa si el foco estaba DENTRO de Inicio antes del cambio: si el usuario lo había subido
     * a la barra de pestañas, una reemisión de Room no tiene por qué bajárselo otra vez.
     */
    /**
     * Vuelve a colocar el foco **como si se acabara de entrar en Inicio**. Lo llama `MainActivity`
     * al cambiar de perfil.
     *
     * ⚠️ Fuerza la colocación aunque el foco esté ya dentro, que es justo lo que la colocación
     * normal se prohíbe. Al cambiar de perfil aparecen secciones nuevas ARRIBA (*Continuar viendo*,
     * *Mi lista*) y empujan hacia abajo la tarjeta que tenía el foco: sigue enfocada, pero **fuera
     * de la pantalla** —medido en el emulador: el volcado de `uiautomator` no traía ni una vista con
     * `focused="true"`, porque no lista lo que no se ve—. Para el usuario, Inicio aparecía sin
     * ningún aro y sin saber dónde estaba el mando.
     *
     * Sigue respetando `keyTicks`: si el usuario pulsa cualquier tecla antes de que las filas estén
     * listas, la colocación se cancela y manda él.
     */
    fun rearmInitialFocus() {
        forceFocusPlacement = true
        focusPlaced = false
        // stopWatching() primero: watchForFocusTarget() no re-arma si ya hay un listener puesto, y
        // hace falta volver a fotografiar `keyTicks` desde este momento.
        stopWatching()
        watchForFocusTarget()
    }

    private fun rescueAfterDiff(hadFocus: Boolean) {
        if (!hadFocus || !isVisible) return
        val root = view ?: return
        if (root.findFocus() != null) return   // el foco sigue dentro: nada que hacer
        focusPlaced = false
        watchForFocusTarget()
    }

    private fun keyTicks() = (activity as? MainActivity)?.keyTicks ?: 0

    private fun stopWatching() {
        val l = focusListener ?: return
        view?.viewTreeObserver?.removeOnGlobalLayoutListener(l)
        focusListener = null
    }

    private fun tryFocus(target: View?): Boolean {
        if (target == null || !target.isShown) return false
        if (!target.requestFocus()) return false
        focusPlaced = true
        forceFocusPlacement = false
        return true
    }

    private fun openSeries(fav: FavoriteSeries) {
        startActivity(Intent(requireContext(), SeriesActivity::class.java).apply {
            putExtra("slug", fav.slug)
            putExtra("title", fav.title)
        })
    }

    /** Resume the in-progress episode directly (the player reads episode_progress for the position). */
    // PlayerActivity is @UnstableApi (it uses unstable media3 APIs); opt in at the call site so
    // the marker stops here instead of propagating out of the player package.
    @OptIn(UnstableApi::class)
    private fun playContinue(item: ContinueItem) {
        startActivity(Intent(requireContext(), PlayerActivity::class.java).apply {
            putExtra("slug",          item.slug)
            putExtra("number",        item.episodeNumber)
            putExtra("title",         item.title)
            putExtra("coverUrl",      item.coverUrl)
            putExtra("backdropUrl",   item.coverUrl)
            putExtra("isWatched",     false)
            putExtra("totalEpisodes", item.totalEpisodes)
            putExtra("minEpisode",    1)
            // For an airing series the cached totalEpisodes lags behind the episode actually
            // being resumed (e.g. total=10, watching 12): never send a max below the current one.
            putExtra("maxEpisode",    maxOf(item.totalEpisodes, item.episodeNumber))
            putExtra("seriesStatus",  item.status)
            putExtra("startDate",     item.year)
            putExtra("category",      item.category)
            // Tapping this row already IS the resume choice — skip the "¿Continuar viendo?" prompt.
            putExtra("autoResume",    true)
        })
    }

    private class RowSpacing(private val dp: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            outRect.right = (dp * view.resources.displayMetrics.density).toInt()
        }
    }
}
