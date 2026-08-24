package com.animeav1.ui.series

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.animeav1.R
import com.animeav1.data.AiringSchedule
import com.animeav1.data.local.ListType
import com.animeav1.data.model.EpisodeRef
import com.animeav1.data.model.Series
import com.animeav1.ui.player.PlayerActivity
import com.animeav1.viewmodel.LocalViewModel
import com.animeav1.viewmodel.SeriesViewModel
import com.animeav1.ui.RowLayoutManager
import com.animeav1.ui.SectionScrollView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SeriesFragment : Fragment() {

    private val vm: SeriesViewModel by viewModels()
    private val localVm: LocalViewModel by activityViewModels()
    private lateinit var slug: String
    private lateinit var seriesTitle: String
    private var seriesCoverUrl: String = ""
    private var seriesYear: String = ""
    private var seriesStatus: Int = -1
    private var seriesCategory: String = ""
    private var episodeAdapter: EpisodeGridAdapter? = null

    /**
     * Último progreso conocido de esta serie, y total de episodios.
     *
     * ⚠️ Viven en el FRAGMENT y no solo dentro del adapter: cambiar de bloque crea un adapter nuevo,
     * y el Flow de Room no vuelve a emitir mientras nadie escriba en `episode_progress`, así que las
     * barras de progreso desaparecían para el resto de la sesión en cuanto se tocaba un chip.
     */
    private var progressMap: Map<Int, Float> = emptyMap()
    private var episodesTotal = 0

    /** Alto máximo del panel de sinopsis. Por encima, el texto se recorre con el D-pad. */
    private val SYNOPSIS_MAX_HEIGHT_DP = 420

    /** `status` de "En emisión" tal y como lo publica el sitio. */
    private val STATUS_AIRING = 2

    /**
     * "viernes 28". Locale español fijo y no el del aparato: el resto de la UI de la app está en
     * español a pelo (no hay más `strings.xml`), así que un "Friday 28" dentro de "Nuevo episodio
     * el…" quedaría a medias. UTC porque la fecha viene ya normalizada a medianoche UTC.
     */
    private val DAY_FORMAT = SimpleDateFormat("EEEE d", Locale("es", "ES"))
        .apply { timeZone = TimeZone.getTimeZone("UTC") }

    /** Ancho al que se aspira para cada tile de episodio. De aquí salen las columnas. */
    private val EPISODE_TILE_TARGET_DP = 104

    /** Columnas si aún no se conoce el ancho (red de seguridad). */
    private val EPISODE_COLUMNS_FALLBACK = 6

    /** Tamaño de bloque de la rejilla de episodios. 100 = 20 filas de 5, unas 4 pantallas de D-pad. */
    private val EPISODES_PER_BLOCK = 100
    private var currentSeries: Series? = null

    /** Last Series instance fully bound: the sticky StateFlow re-emits it on every return to
     *  STARTED (e.g. coming back from the player) and a full re-bind would steal focus to
     *  "Continuar" and reset the episode grid's scroll. */
    private var lastBound: Series? = null

    companion object {
        fun newInstance(slug: String, title: String) = SeriesFragment().apply {
            arguments = Bundle().also {
                it.putString("slug", slug)
                it.putString("title", title)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        slug        = arguments?.getString("slug")  ?: ""
        seriesTitle = arguments?.getString("title") ?: ""
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_series, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.btn_back).setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        localVm.checkListType(slug)
        localVm.observeWatched(slug)
        observeViewModel(view)
        vm.loadSeries(slug)
    }

    /** The Fragment outlives its view in the back stack (opening a related series pushes another
     *  SeriesFragment). Clearing lastBound is what lets bindSeries run again on the way back —
     *  otherwise the sticky StateFlow re-emits the same instance, the identity guard skips the
     *  bind and the recreated view stays blank. Dropping the view-derived refs also releases the
     *  destroyed view tree while related series stack up. */
    override fun onDestroyView() {
        lastBound = null
        episodeAdapter = null
        progressMap = emptyMap()
        currentSeries = null
        super.onDestroyView()
    }

    private fun observeViewModel(view: View) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.series.collectLatest { series ->
                        series ?: return@collectLatest
                        view.findViewById<View>(R.id.loading_overlay).visibility = View.GONE
                        if (series === lastBound) return@collectLatest
                        lastBound = series
                        bindSeries(view, series)
                    }
                }
                launch {
                    vm.error.collectLatest { err ->
                        err ?: return@collectLatest
                        view.findViewById<View>(R.id.loading_overlay).visibility = View.GONE
                        view.findViewById<TextView>(R.id.episodes_header).text = "Error: $err"
                        // Sin datos no hay tiles: que la rejilla no se coma el foco (ver showEpisodeBlock).
                        view.findViewById<RecyclerView>(R.id.episodes_recycler).isFocusable = false
                    }
                }
                launch {
                    localVm.watchedEpisodes.collectLatest { watched ->
                        episodeAdapter?.updateWatched(watched)
                        updateWatchedCounter(view, watched)
                        currentSeries?.let { updateContinueButton(view, it, watched) }
                    }
                }
                launch {
                    // Progreso parcial de esta serie: pinta "a medias" en los tiles que el usuario
                    // dejó empezados, para no tener que abrirlos para saberlo.
                    localVm.repo.progressForSeries(slug).collectLatest { rows ->
                        progressMap = rows.filter { it.durationMs > 0L }
                            .associate { it.episodeNumber to (it.positionMs.toFloat() / it.durationMs) }
                        episodeAdapter?.updateProgress(progressMap)
                    }
                }
                launch {
                    localVm.currentListType.collectLatest { listType ->
                        val btn = view.findViewById<Button>(R.id.btn_favorite) ?: return@collectLatest
                        if (listType != null) {
                            btn.text = "☰  ${listType.displayName}  ▾"
                            btn.alpha = 1f
                        } else {
                            btn.text = "☰  Agregar a lista  ▾"
                            btn.alpha = 0.7f
                        }
                    }
                }
                launch {
                    localVm.currentIsFavorite.collectLatest { isFav ->
                        val btn = view.findViewById<ImageButton>(R.id.btn_heart) ?: return@collectLatest
                        btn.setImageResource(if (isFav) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
                        btn.alpha = if (isFav) 1f else 0.7f
                    }
                }
            }
        }
    }

    private fun bindSeries(view: View, series: Series) {
        seriesCoverUrl = series.coverUrl
        seriesYear = series.startDate.take(4)
        seriesStatus = series.status
        seriesCategory = series.category
        // Backfill meta + refresh the available-episode count so the list's "vistos/total" badge
        // tracks airing series (e.g. 8 -> 9) the same way this detail view does.
        localVm.updateSeriesMeta(slug, seriesYear, series.status, series.category, series.episodes.size)
        // ⚠️ Si la serie no tiene backdrop, el banner cae a la PORTADA recortada antes que a un
        // dibujo de reserva: en la cabecera nueva, el placeholder con su "play" gigante se leía
        // como un fallo de carga. El degradado neutro queda solo para cuando tampoco hay portada.
        val backdrop = view.findViewById<ImageView>(R.id.series_backdrop)
        backdrop.load(series.backdropUrl) {
            crossfade(true)
            listener(onError = { _, _ ->
                backdrop.load(series.coverUrl) {
                    crossfade(true)
                    error(R.drawable.banner_fallback)
                }
            })
        }
        view.findViewById<ImageView>(R.id.series_poster).load(series.coverUrl) {
            crossfade(true)
            placeholder(R.drawable.placeholder_poster)
            error(R.drawable.placeholder_poster)
        }

        // Anclar la sección enfocada arriba, como en Inicio: con tres bloques altos, el
        // desplazamiento mínimo de un scroll normal deja la sección recién enfocada pegada al borde
        // inferior y sin pista de que haya algo más.
        view.findViewById<SectionScrollView>(R.id.series_scroll)?.let { scroll ->
            listOf(R.id.hero_section, R.id.episodes_section, R.id.relations_section)
                .mapNotNull { view.findViewById<View>(it) }
                .forEach { scroll.registerRow(it) }
        }

        view.findViewById<TextView>(R.id.series_title).text    = series.title
        view.findViewById<TextView>(R.id.series_synopsis).text = series.synopsis
        bindSynopsisMore(view, series)

        val score = if (series.score > 0)
            "★ %.1f  (${formatVotes(series.votes)} votos)".format(series.score)
        else "Sin calificación"
        view.findViewById<TextView>(R.id.series_score).text  = score
        // Status badge also carries year and type — the detail page is the only place with both.
        view.findViewById<TextView>(R.id.series_status).text =
            listOf(series.statusName, seriesYear, series.category)
                .filter { it.isNotBlank() }
                .joinToString("  ·  ")
        view.findViewById<TextView>(R.id.series_genres).text =
            series.genres.take(5).joinToString(" · ")
        bindNextEpisode(view, series)

        view.findViewById<Button>(R.id.btn_favorite).setOnClickListener {
            showListPicker(series)
        }
        view.findViewById<ImageButton>(R.id.btn_heart).setOnClickListener {
            localVm.toggleFavorite(slug, series.title, series.coverUrl, series.episodes.size, seriesYear, series.status, series.category)
        }

        val header = "Episodios  ${series.episodes.size}" +
            if (series.episodesCount > series.episodes.size) " / ${series.episodesCount}" else ""
        view.findViewById<TextView>(R.id.episodes_header).text = header

        episodesTotal = series.episodes.size

        val recycler = view.findViewById<RecyclerView>(R.id.episodes_recycler)
        // ⚠️ SIN `setHasFixedSize(true)`, al contrario que antes: ahora el alto de la rejilla SÍ
        // depende de cuántos episodios haya (`fitGridHeight`), que es justo lo que esa bandera
        // promete que no pasa. El lint `InvalidSetHasFixedSize` lo caza y rompe la build
        // (`abortOnError=true`), y hace bien.
        recycler.addItemDecorationOnce(GridSpacingDecoration(8))

        // Bloques solo en series largas: por debajo del umbral la rejilla completa se recorre bien y
        // una fila de chips sería ruido.
        val blocks = blocksFor(series.episodes.map { it.number })
        val blocksRow = view.findViewById<RecyclerView>(R.id.episode_blocks)
        if (blocks.size > 1) {
            blocksRow.visibility = View.VISIBLE
            // ⚠️ `RowLayoutManager` y no un LinearLayoutManager pelado: IZQ/DER en los extremos se
            // quedan en la fila. Sin esto, DERECHA en el último chip se iba a una tarjeta de
            // *Series relacionadas*, al otro extremo de la página (comprobado con One Piece), y
            // desde allí ya no había vuelta.
            blocksRow.layoutManager = RowLayoutManager(requireContext())
            val blockAdapter = EpisodeBlockAdapter(blocks, upFocusId = R.id.btn_continue) { index ->
                // Elegido a mano: se muestra el bloque desde su principio, no se salta a mitad.
                showEpisodeBlock(view, series, blocks[index])
            }
            blocksRow.adapter = blockAdapter
            // Arrancar en el bloque del episodio que toca ver, no en el primero: en una serie de 1000
            // episodios el usuario está por el 700 y no quiere empezar por el 1.
            val next = series.episodes.firstOrNull { it.number !in localVm.watchedEpisodes.value }?.number
            val startIndex = blocks.indexOfFirst { next != null && next in it }.coerceAtLeast(0)
            blockAdapter.select(startIndex)
            // ⚠️ Seleccionar el chip no lo trae a la vista: con 12 bloques la fila se quedaba en el
            // chip 0 y el único resaltado estaba fuera de pantalla, así que parecía que no había
            // ninguno elegido.
            blocksRow.scrollToPosition(startIndex)
            showEpisodeBlock(view, series, blocks[startIndex])
        } else {
            blocksRow.visibility = View.GONE
            showEpisodeBlock(view, series, null)
        }
        // Counter: update now that the adapter (and its itemCount) is ready
        updateWatchedCounter(view, localVm.watchedEpisodes.value)

        // Continue button (default focus) + jump the grid to where you left off.
        currentSeries = series
        val watchedNow = localVm.watchedEpisodes.value
        updateContinueButton(view, series, watchedNow)
        view.findViewById<Button>(R.id.btn_continue).post {
            view.findViewById<Button>(R.id.btn_continue)?.requestFocus()
        }
        if (series.relations.isNotEmpty()) {
            // La sección entera, no sus piezas: con tres bloques apilados, dejar el rótulo o el
            // divisor sueltos cuando no hay relacionadas deja un hueco al final de la página.
            view.findViewById<View>(R.id.relations_section).visibility = View.VISIBLE

            val relRecycler = view.findViewById<RecyclerView>(R.id.relations_recycler)
            // ⚠️ `blockStartEdge = false`: DERECHA en la última tarjeta se queda en la fila —si no,
            // saltaba hacia ARRIBA, a un tile de la rejilla de episodios— pero IZQUIERDA sí sale,
            // porque a la izquierda está el panel de la serie y ese paso es legítimo.
            relRecycler.layoutManager = RowLayoutManager(requireContext(), blockStartEdge = false)
            relRecycler.addItemDecorationOnce(HorizontalSpacingDecoration(8))
            relRecycler.adapter = RelationAdapter(series.relations) { rel ->
                requireActivity().supportFragmentManager.beginTransaction()
                    .replace(R.id.series_container, newInstance(rel.slug, rel.title))
                    .addToBackStack(null)
                    .commit()
            }
        }
    }

    // PlayerActivity is @UnstableApi (it uses unstable media3 APIs); opt in at the call site so
    // the marker stops here instead of propagating out of the player package.
    @OptIn(UnstableApi::class)
    private fun launchPlayer(series: Series, epNumber: Int) {
        startActivity(Intent(requireContext(), PlayerActivity::class.java).apply {
            putExtra("slug",           slug)
            putExtra("number",         epNumber)
            putExtra("title",          series.title)
            putExtra("coverUrl",       series.coverUrl)
            putExtra("backdropUrl",    series.backdropUrl)
            putExtra("isWatched",      epNumber in localVm.watchedEpisodes.value)
            putExtra("totalEpisodes",  series.episodes.size)
            putExtra("minEpisode",     series.episodes.minOfOrNull { it.number } ?: 1)
            putExtra("maxEpisode",     series.episodes.maxOfOrNull { it.number } ?: series.episodes.size)
            putExtra("seriesStatus",   series.status)
            putExtra("startDate",      series.startDate)
            putExtra("category",       series.category)
        })
    }

    /**
     * Deja leer la sinopsis entera cuando no cabe.
     *
     * La sinopsis del panel está limitada a 7 líneas: sin límite, una sinopsis larga empujaría el
     * botón "Continuar" fuera de la pantalla, y como el texto no es enfocable tampoco se puede
     * recorrer con el mando. Así que se recorta y se ofrece abrirla en un panel aparte.
     *
     * ⚠️ El botón aparece **solo si el texto se ha cortado de verdad**, y eso se pregunta al
     * `Layout` del propio TextView (`getEllipsisCount` en la última línea), no contando caracteres:
     * lo que cabe depende del ancho, del tamaño de letra y de dónde parte cada palabra. Se hace en
     * `post` porque antes de medir no hay `layout` ninguno.
     *
     * ⚠️ La cadena de foco se recablea aquí. `btn_back` bajaba siempre a "Continuar" y "Continuar"
     * subía siempre a `btn_back`; ahora, cuando el botón está, se intercala. Tiene que ser en
     * código: dejarlo fijo en el XML apuntando a una vista `gone` deja la tecla muerta
     * (`requestFocus` falla en lo invisible), que es el mismo error que ya costó caro en la rejilla.
     */
    private fun bindSynopsisMore(view: View, series: Series) {
        val synopsis = view.findViewById<TextView>(R.id.series_synopsis) ?: return
        val more     = view.findViewById<View>(R.id.btn_synopsis_more) ?: return
        val cont     = view.findViewById<View>(R.id.btn_continue)

        more.setOnClickListener { showFullSynopsis(view, series) }

        synopsis.post {
            val layout = synopsis.layout
            val truncated = layout != null && layout.lineCount > 0 &&
                layout.getEllipsisCount(layout.lineCount - 1) > 0
            more.visibility = if (truncated) View.VISIBLE else View.GONE
            // Orden en pantalla: acciones → sinopsis → "Leer más" → rejilla. Así que lo que cambia
            // según haya botón o no es ABAJO desde la fila de acciones; ARRIBA desde ellas siempre
            // va a "Volver". (Con el diseño anterior, en dos columnas, era justo al revés.)
            // ⚠️ Lo primero que hay debajo de la portada NO siempre es la rejilla: en las series
            // largas hay antes una fila de chips de bloque, y apuntando directo a la rejilla los
            // chips quedaban **inalcanzables** — no había forma de llegar al episodio 101 en
            // adelante (reproducido con One Piece). Se resuelve en tiempo de ejecución porque los
            // chips solo existen por encima de EPISODES_PER_BLOCK.
            val firstBelow = if (view.findViewById<View>(R.id.episode_blocks)?.isVisible == true)
                R.id.episode_blocks else R.id.episodes_recycler
            val below = if (truncated) R.id.btn_synopsis_more else firstBelow
            cont?.nextFocusDownId = below
            view.findViewById<View>(R.id.btn_favorite)?.nextFocusDownId = below
            view.findViewById<View>(R.id.btn_heart)?.nextFocusDownId = below
            more.nextFocusDownId = firstBelow
        }
    }

    /** Panel con la sinopsis completa. BACK y "Cerrar" lo cierran; el foco vuelve de donde vino. */
    private fun showFullSynopsis(view: View, series: Series) {
        val overlay = view.findViewById<View>(R.id.synopsis_overlay) ?: return
        view.findViewById<TextView>(R.id.synopsis_full_title).text = series.title
        view.findViewById<TextView>(R.id.synopsis_full_text).text  = series.synopsis
        val scroll = view.findViewById<NestedScrollView>(R.id.synopsis_scroll)
        val close  = view.findViewById<View>(R.id.btn_synopsis_close)
        overlay.visibility = View.VISIBLE
        // ⚠️ Sacar la ficha de la búsqueda de foco mientras el panel está abierto. Un overlay
        // VISIBLE no aísla nada —`FocusFinder` no sabe de oclusión—, así que ABAJO desde el texto se
        // iba a "Reproducir", **detrás del panel** (comprobado en el emulador). Mismo remedio que
        // `ExitConfirm` y que el editor de perfil.
        view.findViewById<ViewGroup>(R.id.series_content)?.descendantFocusability =
            ViewGroup.FOCUS_BLOCK_DESCENDANTS
        close?.setOnClickListener { hideFullSynopsis(view) }
        scroll?.post {
            val contentH = scroll.getChildAt(0)?.height ?: 0
            val maxH = (SYNOPSIS_MAX_HEIGHT_DP * resources.displayMetrics.density).toInt()
            val fits = contentH <= maxH
            if (!fits) {
                // Tope de altura en código porque el XML no puede (ver el comentario del layout):
                // sin él, una sinopsis muy larga estiraba el panel fuera de la pantalla y el final
                // quedaba igual de inalcanzable que en la ficha.
                scroll.layoutParams = scroll.layoutParams.apply { height = maxH }
            }
            // Si el texto cabe entero no hay nada que recorrer, y un ScrollView enfocado no dibuja
            // ningún indicador: el panel se vería sin foco en ninguna parte. En ese caso manda el
            // botón de cerrar, que sí se ve. Si no cabe, manda el texto, que es lo que hay que
            // poder recorrer con ARRIBA/ABAJO.
            if (fits) close?.requestFocus() else scroll.post { scroll.requestFocus() }
        }
    }

    private fun hideFullSynopsis(view: View) {
        val overlay = view.findViewById<View>(R.id.synopsis_overlay) ?: return
        if (overlay.visibility != View.VISIBLE) return
        overlay.visibility = View.GONE
        view.findViewById<ViewGroup>(R.id.series_content)?.descendantFocusability =
            ViewGroup.FOCUS_BEFORE_DESCENDANTS
        view.findViewById<View>(R.id.btn_synopsis_more)?.requestFocus()
    }

    /** true si el panel de sinopsis estaba abierto (y se ha cerrado). Lo usa BACK. */
    fun consumeBack(): Boolean {
        val v = view ?: return false
        val overlay = v.findViewById<View>(R.id.synopsis_overlay) ?: return false
        if (overlay.visibility != View.VISIBLE) return false
        hideFullSynopsis(v)
        return true
    }

    /**
     * "Nuevo episodio el viernes 28" en las series **en emisión**.
     *
     * Antes la ficha de una serie en emisión no decía nada de cuándo llega el siguiente: acababas el
     * último disponible y te quedabas sin saber si tocaba mañana o dentro de una semana.
     *
     * ⚠️ La fecha se **calcula** con [AiringSchedule], no se lee de un campo: el `nextDate` del
     * sitio no es lo que su nombre dice (ver esa clase). Y si no se puede prometer una fecha —serie
     * parada, sin cadencia conocida— la línea **se esconde**, que es mejor que mentir.
     *
     * ⚠️ Solo para `status == 2` (en emisión). En una serie terminada la cadencia seguiría dando
     * fechas futuras alegremente.
     */
    private fun bindNextEpisode(view: View, series: Series) {
        val label = view.findViewById<TextView>(R.id.series_next_ep) ?: return
        val next = if (series.status == STATUS_AIRING) {
            AiringSchedule.nextAirDate(
                anchorDate       = series.airingAnchor,
                waitDays         = series.waitDays,
                publishedEpisodes = series.episodes.size,
                todayMillis      = System.currentTimeMillis()
            )
        } else null
        if (next == null) {
            label.visibility = View.GONE
            return
        }
        label.visibility = View.VISIBLE
        label.text = when (AiringSchedule.daysBetween(System.currentTimeMillis(), next)) {
            0    -> getString(R.string.next_ep_today)
            1    -> getString(R.string.next_ep_tomorrow)
            // Con cadencia semanal la fecha cae siempre dentro de los próximos 7 días, así que el
            // día de la semana orienta mejor que un "28/08" y ocupa menos.
            else -> getString(R.string.next_ep_on, DAY_FORMAT.format(Date(next)))
        }
    }

    /** Points the "Continuar" button at the first unwatched episode (or rewatch from the start). */
    private fun updateContinueButton(view: View, series: Series, watched: Set<Int>) {
        val btn = view.findViewById<Button>(R.id.btn_continue) ?: return
        if (series.episodes.isEmpty()) { btn.visibility = View.GONE; return }
        val firstUnwatched = series.episodes.firstOrNull { it.number !in watched }
        val nextEp = firstUnwatched?.number ?: series.episodes.first().number
        btn.visibility = View.VISIBLE
        btn.text = when {
            watched.isEmpty()      -> "Reproducir"
            firstUnwatched == null -> "Volver a ver"
            else                   -> "Continuar · Ep $nextEp"
        }
        btn.setOnClickListener { launchPlayer(series, nextEp) }
    }

    private fun showWatchedDialog(ep: EpisodeRef) {
        val isWatched = ep.number in localVm.watchedEpisodes.value
        val episodes = currentSeries?.episodes.orEmpty()
        // The repository needs both to apply the same promotion the player does: the real
        // episode count (for Mi Lista's "vistos/total") and whether this is the final episode.
        val isLast = episodes.isNotEmpty() && ep.number == episodes.maxOf { it.number }
        // Marcar visto arrastra los anteriores que sigan sin marcar (misma regla que el reproductor,
        // `LocalRepository.markWatchedThrough`, así que no hay una segunda copia): es lo que hace
        // usable ponerse al día —marcar de uno en uno una serie de 100 episodios son 100 diálogos— y
        // evita que la serie quede diciendo "1 visto de 100" cuando en realidad se va por el 40.
        // La segunda opción es el escape para quien SÍ quiere dejar huecos (un especial suelto).
        val options = if (isWatched) {
            arrayOf(getString(R.string.ep_unwatch))
        } else {
            arrayOf(getString(R.string.ep_watch), getString(R.string.ep_watch_only))
        }
        AlertDialog.Builder(requireContext(), android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(getString(R.string.ep_dialog_title, ep.number))
            .setItems(options) { _, which ->
                if (!isWatched && which == 0) {
                    localVm.markWatchedThrough(
                        slug, ep.number, episodes.minOf { it.number },
                        seriesTitle, seriesCoverUrl, episodes.size, isLast,
                        seriesYear, seriesStatus, seriesCategory
                    )
                } else {
                    localVm.toggleWatched(
                        slug, ep.number, seriesTitle, seriesCoverUrl, episodes.size, isLast,
                        seriesYear, seriesStatus, seriesCategory
                    )
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /**
     * Parte los episodios en bloques de [EPISODES_PER_BLOCK]. Devuelve una sola entrada (y por tanto
     * no se muestran chips) si la serie cabe entera en un bloque.
     */
    private fun blocksFor(numbers: List<Int>): List<IntRange> {
        if (numbers.size <= EPISODES_PER_BLOCK) return listOf(IntRange(0, 0))
        return numbers.chunked(EPISODES_PER_BLOCK).map { it.first()..it.last() }
    }

    /** Rellena la rejilla con un bloque, o con todo si [block] es null. */
    private fun showEpisodeBlock(view: View, series: Series, block: IntRange?) {
        val shown = if (block == null) series.episodes else series.episodes.filter { it.number in block }
        val watched = localVm.watchedEpisodes.value
        val grid = view.findViewById<RecyclerView>(R.id.episodes_recycler)
        // El ancho útil se mide del propio RecyclerView; antes del primer layout vale el de la
        // pantalla menos los márgenes de seguridad, que es lo que va a acabar teniendo.
        val usable = if (grid.width > 0) grid.width
                     else resources.displayMetrics.widthPixels -
                          2 * resources.getDimensionPixelSize(R.dimen.tv_safe_h)
        val columns = columnsFor(usable)
        episodeAdapter = EpisodeGridAdapter(
            episodes         = shown,
            seriesId         = series.id,
            watchedSet       = watched,
            allNumbers       = series.episodes.map { it.number },
            onClick          = { ep -> launchPlayer(series, ep.number) },
            onWatchedToggle  = { ep -> showWatchedDialog(ep) },
            spanCount        = columns,
            // ⚠️ Si hay relacionadas, ABAJO baja a esa fila. Si NO las hay, el tile se apunta a sí
            // mismo y la tecla se queda en nada: sin eso, `FocusFinder` se llevaba el foco al panel
            // IZQUIERDO —cruzando la pantalla hasta "Agregar a lista"— porque ahí abajo no hay nada
            // en esta columna y decide por distancia (comprobado en una serie sin relacionadas).
            // Nunca `View.NO_ID` apuntando a un RecyclerView escondido: `requestFocus` falla en una
            // vista GONE y la tecla se quedaría muerta de otra manera.
            downFocusId      = if (series.relations.isNotEmpty()) R.id.relations_recycler
                               else R.id.episode_tile,
            // Subir desde la primera fila de tiles lleva a los chips si los hay; si no, a la
            // portada. Sin esto, los chips también eran inalcanzables desde abajo.
            upFocusId        = if (view.findViewById<View>(R.id.episode_blocks)?.isVisible == true)
                                   R.id.episode_blocks else R.id.btn_continue
        )
        val recycler = view.findViewById<RecyclerView>(R.id.episodes_recycler)
        recycler.layoutManager = GridLayoutManager(requireContext(), columns)
        recycler.adapter = episodeAdapter
        // El adapter es NUEVO: hay que reponerle todo lo que ya se sabe. El progreso NO puede
        // esperar al colector — Room no reemite si nadie escribe, así que sin esto las barras
        // desaparecían al cambiar de bloque y no volvían en toda la sesión.
        episodeAdapter?.updateWatched(watched)
        episodeAdapter?.updateProgress(progressMap)
        updateWatchedCounter(view, watched)
        // El índice es del BLOQUE mostrado, no de la serie: con el índice global el scroll caía
        // fuera de rango en los bloques 2..N y `LinearLayoutManager` lo descartaba en silencio.
        // ⚠️ Sin tiles la rejilla NO debe poder recibir el foco: con `afterDescendants` y ningún hijo
        // focusable el propio RecyclerView lo acepta y no dibuja nada, y todos los botones del panel
        // izquierdo apuntan aquí con `nextFocusRight`/`nextFocusDown` — una serie anunciada sin
        // episodios se comía el foco en la primera pulsación. Mismo criterio que las filas de Inicio.
        recycler.isFocusable = shown.isNotEmpty()
        setupEpisodeGridScrolling(recycler)
        // ⚠️ Ya NO se hace `scrollToPosition` al primer episodio sin ver: la rejilla no scrollea por
        // su cuenta (lo hace la página) y la ficha debe abrirse por arriba, en la portada. El
        // episodio que toca sigue señalado con su marca, y "Continuar" lleva a él directamente.
    }

    /**
     * Columnas de la rejilla **según el ancho que haya**, no un número fijo.
     *
     * Antes eran 5 clavadas, calculadas para la media columna derecha del diseño anterior. Ahora la
     * rejilla ocupa el ancho entero: con 5 columnas los tiles saldrían enormes, y cualquier número
     * fijo se rompe en cuanto cambia el ancho útil (otra resolución, otro margen de seguridad). Se
     * calcula desde un ancho objetivo por tile, acotado para no acabar ni con 2 columnas ni con 20.
     */
    private fun columnsFor(widthPx: Int): Int {
        if (widthPx <= 0) return EPISODE_COLUMNS_FALLBACK
        val target = EPISODE_TILE_TARGET_DP * resources.displayMetrics.density
        return (widthPx / target).toInt().coerceIn(4, 12)
    }

    /**
     * ⚠️ La rejilla **no tiene tope de filas**: se ven TODOS los episodios y quien hace scroll es la
     * página, no la rejilla.
     *
     * Hubo una versión con tope de 4 filas para que *Series relacionadas* quedara cerca, pero con
     * columnas responsivas eso son 32 episodios y una serie de 64 parecía cortada. El coste real
     * está acotado por los **bloques**: por encima de `EPISODES_PER_BLOCK` (100) la rejilla enseña un
     * bloque cada vez, así que el máximo son ~13 filas aunque la serie tenga 1172 episodios.
     *
     * `wrap_content` basta —dentro de un scroll, un `RecyclerView` se mide con su contenido entero—,
     * así que aquí solo queda apagar el scroll anidado: si no, la rejilla se pelea con la página por
     * quién consume el desplazamiento.
     */
    private fun setupEpisodeGridScrolling(recycler: RecyclerView) {
        recycler.isNestedScrollingEnabled = false
    }

    private fun updateWatchedCounter(view: View, watched: Set<Int>) {
        val counter = view.findViewById<TextView>(R.id.watched_counter) ?: return
        // ⚠️ El total es el de la SERIE, no `episodeAdapter.itemCount`: con bloques ese es el del
        // bloque visible, así que One Piece con 700 vistos mostraba "✓ 700 / 100".
        val total   = episodesTotal
        if (total > 0) {
            counter.text = "✓ ${watched.size} / $total"
            counter.visibility = View.VISIBLE
        } else {
            counter.visibility = View.GONE
        }
    }

    private fun showListPicker(series: Series) {
        val watchLists = listOf(ListType.POR_VER, ListType.VIENDO, ListType.COMPLETADAS)
        val listNames  = watchLists.map { it.displayName }.toTypedArray()
        val currentIdx = localVm.currentListType.value?.let { watchLists.indexOf(it) } ?: -1

        AlertDialog.Builder(requireContext(), android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Agregar a lista")
            .setSingleChoiceItems(listNames, currentIdx) { dialog, which ->
                val chosen = watchLists[which]
                localVm.addToList(slug, series.title, series.coverUrl, chosen, series.episodes.size, seriesYear, series.status, series.category)
                dialog.dismiss()
            }
            .setNeutralButton("Quitar de listas") { _, _ ->
                localVm.removeFromList(slug)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun formatVotes(v: Int) = when {
        v >= 1_000_000 -> "%.1fM".format(v / 1_000_000f)
        v >= 1_000     -> "%.1fK".format(v / 1_000f)
        else           -> v.toString()
    }

    private fun RecyclerView.addItemDecorationOnce(decoration: RecyclerView.ItemDecoration) {
        if (itemDecorationCount == 0) addItemDecoration(decoration)
    }
}

class GridSpacingDecoration(private val spacingDp: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val px = (spacingDp * view.resources.displayMetrics.density).toInt()
        outRect.set(px, px, px, px)
    }
}

class HorizontalSpacingDecoration(private val spacingDp: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val px = (spacingDp * view.resources.displayMetrics.density).toInt()
        outRect.right = px * 2
    }
}
