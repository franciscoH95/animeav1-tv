package com.animeav1.ui.browse

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.animeav1.R
import com.animeav1.data.model.Anime
import com.animeav1.data.model.CatalogFilter
import com.animeav1.ui.series.SeriesActivity
import com.animeav1.viewmodel.BrowseViewModel
import com.animeav1.ui.GRID_COLUMNS
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class BrowseFragment : Fragment() {

    private lateinit var vm: BrowseViewModel
    private lateinit var filterAdapter: FilterChipAdapter
    private lateinit var animeAdapter: CatalogAnimeAdapter
    private lateinit var filterRecycler: RecyclerView
    private lateinit var animeGrid: RecyclerView
    private lateinit var skeletonGrid: RecyclerView
    private lateinit var emptyText: TextView
    private var skeletonAnim: android.animation.ObjectAnimator? = null

    private var currentPage = 1
    private var pendingPage = 1
    private var isLoading   = false
    private var totalItems  = 0

    // Filter state
    private var activeCategory = "" to "Todos"
    private var activeGenre    = "" to "Todos"
    private var activeStatus   = "" to "Todos"

    // Options lists
    private val categoryOptions = listOf(
        "" to "Todos", "tv-anime" to "TV Anime",
        "pelicula" to "Película", "ova" to "OVA", "especial" to "Especial"
    )
    private val genreOptions = listOf(
        "" to "Todos", "accion" to "Acción", "aventura" to "Aventura",
        "ciencia-ficcion" to "Ciencia Ficción", "comedia" to "Comedia",
        "deportes" to "Deportes", "drama" to "Drama", "fantasia" to "Fantasía",
        "misterio" to "Misterio", "recuentos-de-la-vida" to "Recuentos de la Vida",
        "romance" to "Romance", "seinen" to "Seinen", "shoujo" to "Shoujo",
        "shounen" to "Shounen", "sobrenatural" to "Sobrenatural",
        "suspenso" to "Suspenso", "terror" to "Terror", "isekai" to "Isekai",
        "mecha" to "Mecha", "ecchi" to "Ecchi", "historico" to "Histórico",
        "militar" to "Militar", "psicologico" to "Psicológico", "harem" to "Harem",
        "escolares" to "Escolares", "superpoderes" to "Superpoderes",
        "infantil" to "Infantil", "samurai" to "Samurai", "musica" to "Música",
        "vampiros" to "Vampiros", "gore" to "Gore", "parodia" to "Parodia"
    )
    /**
     * Orden del catálogo. El sitio acepta `?order=` y sus `search_params` lo declaran junto a
     * `letter`/`minYear`; comprobado en vivo que `popular` devuelve otra cosa que el orden por
     * defecto. Sin esto las ~50 páginas del catálogo solo se podían recorrer en un orden.
     */
    private val orderOptions = listOf(
        "" to "Recientes", "popular" to "Populares", "score" to "Mejor valorados", "title" to "A–Z"
    )
    private var activeOrder = "" to "Recientes"

    private val statusOptions = listOf(
        "" to "Todos", "emision" to "En emisión",
        "finalizado" to "Finalizado", "proximamente" to "Próximamente"
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_browse, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        vm = ViewModelProvider(requireActivity())[BrowseViewModel::class.java]

        emptyText     = view.findViewById(R.id.empty_text)
        filterRecycler = view.findViewById(R.id.filter_recycler)
        animeGrid      = view.findViewById(R.id.anime_grid)
        skeletonGrid   = view.findViewById(R.id.skeleton_grid)

        setupFilterBar()
        setupAnimeGrid()
        setupSkeleton()
        observeViewModel()
        showSkeleton()
        vm.loadPage(1)
    }

    private fun setupSkeleton() {
        skeletonGrid.layoutManager = GridLayoutManager(requireContext(), GRID_COLUMNS)
        skeletonGrid.setHasFixedSize(true)
        skeletonGrid.adapter = SkeletonAdapter()
    }

    private fun showSkeleton() {
        skeletonGrid.visibility = View.VISIBLE
        skeletonAnim?.cancel()
        skeletonAnim = android.animation.ObjectAnimator.ofFloat(skeletonGrid, View.ALPHA, 0.4f, 0.9f).apply {
            duration = 750
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            start()
        }
    }

    private fun hideSkeleton() {
        skeletonAnim?.cancel(); skeletonAnim = null
        skeletonGrid.alpha = 1f
        skeletonGrid.visibility = View.GONE
    }

    private var lastClickedChip = 0

    /** Índice del chip "Quitar filtros": va DETRÁS de los cuatro fijos (Tipo, Género, Estado, Orden). */
    private val CHIP_CLEAR = 4

    private fun setupFilterBar() {
        val chips = buildChips()
        filterAdapter = FilterChipAdapter(chips) { index ->
            if (index == CHIP_CLEAR) {
                clearAllFilters()
            } else {
                lastClickedChip = index
                showFilterDialog(index)
            }
        }
        filterRecycler.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        filterRecycler.adapter = filterAdapter
    }

    private fun buildChips(): List<FilterChip> {
        val chips = mutableListOf(
            FilterChip("Tipo: ",   activeCategory.second, activeCategory.first.isNotBlank()),
            FilterChip("Género: ", activeGenre.second,    activeGenre.first.isNotBlank()),
            FilterChip("Estado: ", activeStatus.second,   activeStatus.first.isNotBlank()),
            // El orden NO es un filtro: se muestra siempre como activo y "Quitar filtros" no lo toca.
            FilterChip("Orden: ",  activeOrder.second,    activeOrder.first.isNotBlank())
        )
        if (activeCategory.first.isNotBlank() || activeGenre.first.isNotBlank() || activeStatus.first.isNotBlank()) {
            chips += FilterChip("✕ ", "Quitar filtros", isActive = true)
        }
        return chips
    }

    private fun refreshChips() {
        filterAdapter.updateChips(buildChips())
        // Return focus to the chip that was last interacted with
        filterRecycler.post {
            filterRecycler.layoutManager
                ?.findViewByPosition(lastClickedChip)
                ?.requestFocus()
        }
    }

    private fun showFilterDialog(chipIndex: Int) {
        val (title, options, current) = when (chipIndex) {
            0    -> Triple("Tipo",    categoryOptions, activeCategory)
            1    -> Triple("Género",  genreOptions,    activeGenre)
            2    -> Triple("Estado",  statusOptions,   activeStatus)
            else -> Triple("Orden",   orderOptions,    activeOrder)
        }
        val labels  = options.map { it.second }.toTypedArray()
        val checked = options.indexOfFirst { it.first == current.first }.coerceAtLeast(0)
        AlertDialog.Builder(requireContext(), android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(if (chipIndex == 3) "Ordenar por" else "Filtrar por $title")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val selected = options[which]
                when (chipIndex) {
                    0 -> activeCategory = selected
                    1 -> activeGenre    = selected
                    2 -> activeStatus   = selected
                    3 -> activeOrder    = selected
                }
                dialog.dismiss()
                applyFilters()
            }
            .setOnDismissListener {
                // Always return focus to the chip (whether filter applied or cancelled)
                filterRecycler.post {
                    filterRecycler.layoutManager
                        ?.findViewByPosition(chipIndex)
                        ?.requestFocus()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun applyFilters() {
        refreshChips()
        currentPage = 1
        pendingPage = 1
        isLoading   = false
        totalItems  = 0
        animeAdapter.submitList(emptyList())
        emptyText.visibility = View.GONE
        showSkeleton()
        val f = CatalogFilter(
            activeCategory.first, activeGenre.first, activeStatus.first, order = activeOrder.first
        )
        vm.applyFilter(f)
    }

    private fun setupAnimeGrid() {
        val cols = GRID_COLUMNS
        animeAdapter = CatalogAnimeAdapter { anime ->
            startActivity(Intent(requireContext(), SeriesActivity::class.java).apply {
                putExtra("slug",  anime.slug)
                putExtra("title", anime.title)
            })
        }
        animeGrid.layoutManager = GridLayoutManager(requireContext(), cols)
        animeGrid.setHasFixedSize(true)   // grid bounds are screen-driven, not content-driven
        animeGrid.adapter = animeAdapter
        animeGrid.addItemDecoration(GridSpacing(8))

        // Infinite scroll
        animeGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm   = rv.layoutManager as GridLayoutManager
                val last = lm.findLastVisibleItemPosition()
                if (!isLoading && last >= animeAdapter.itemCount - 12) loadNextPage()
            }
        })
    }

    private fun clearAllFilters() {
        activeCategory = "" to "Todos"
        activeGenre    = "" to "Todos"
        activeStatus   = "" to "Todos"
        applyFilters()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            vm.catalog.collectLatest { result ->
                val page = (result ?: return@collectLatest).page
                isLoading = false
                currentPage = pendingPage   // only advance once the page actually arrived
                hideSkeleton()
                val prev  = animeAdapter.currentList.toMutableList()
                prev.addAll(page.results)
                animeAdapter.submitList(prev)
                totalItems = page.total
                emptyText.visibility = if (prev.isEmpty()) View.VISIBLE else View.GONE
                // ⚠️ Sin tarjetas la rejilla NO debe poder recibir el foco: con `afterDescendants` y
                // ningún hijo focusable, el propio RecyclerView lo acepta y no pinta nada — el foco
                // "desaparece" y solo ARRIBA lo devuelve. Ahora que el menú del Catálogo es la fila de
                // chips, ABAJO desde un chip es justo la pulsación que cae en el hueco.
                animeGrid.isFocusable = prev.isNotEmpty()
            }
        }
        lifecycleScope.launch {
            vm.error.collectLatest { err ->
                err ?: return@collectLatest
                isLoading = false
                hideSkeleton()
                if (animeAdapter.itemCount == 0) {
                    emptyText.apply { visibility = View.VISIBLE; text = err }
                    animeGrid.isFocusable = false
                }
                else
                    // Failed pagination: keep currentPage so the next scroll retries the same page.
                    android.widget.Toast.makeText(requireContext(), R.string.load_more_failed, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadNextPage() {
        if (animeAdapter.itemCount >= totalItems && totalItems > 0) return
        isLoading = true
        pendingPage = currentPage + 1
        vm.loadPage(pendingPage)
    }

    /** Spacing in dp (converted here) so the gap matches the other grids on any density. */
    inner class GridSpacing(dp: Int) : RecyclerView.ItemDecoration() {
        private val space = (dp * resources.displayMetrics.density).toInt()
        override fun getItemOffsets(outRect: android.graphics.Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            outRect.set(space, space, space, space)
        }
    }
}
