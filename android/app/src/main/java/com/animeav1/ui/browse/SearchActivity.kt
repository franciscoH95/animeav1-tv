package com.animeav1.ui.browse

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.animeav1.R
import com.animeav1.ui.series.SeriesActivity
import com.animeav1.viewmodel.BrowseViewModel
import com.animeav1.ui.GRID_COLUMNS
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Full-screen search: big input + on-screen results grid, debounced search-as-you-type. */
class SearchActivity : FragmentActivity() {

    private lateinit var vm: BrowseViewModel
    private lateinit var adapter: CatalogAnimeAdapter
    private lateinit var results: RecyclerView
    private lateinit var skeleton: RecyclerView
    private lateinit var hint: TextView
    private lateinit var count: TextView
    private lateinit var input: EditText
    private var searchJob: Job? = null
    private var skeletonAnim: android.animation.ObjectAnimator? = null

    // Infinite scroll over search results (the header promises page.total items).
    private var lastQuery   = ""
    private var currentPage = 1
    private var pendingPage = 1
    private var isLoading   = false
    private var totalItems  = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        vm = ViewModelProvider(this)[BrowseViewModel::class.java]

        results  = findViewById(R.id.search_results)
        skeleton = findViewById(R.id.search_skeleton)
        hint     = findViewById(R.id.search_hint)
        count    = findViewById(R.id.search_count)
        input    = findViewById(R.id.search_input)

        adapter = CatalogAnimeAdapter { anime ->
            startActivity(Intent(this, SeriesActivity::class.java).apply {
                putExtra("slug", anime.slug)
                putExtra("title", anime.title)
            })
        }
        results.layoutManager = GridLayoutManager(this, GRID_COLUMNS)
        results.setHasFixedSize(true)
        results.adapter = adapter
        val gap = (8 * resources.displayMetrics.density).toInt()
        results.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: android.graphics.Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                outRect.set(gap, gap, gap, gap)
            }
        })
        // Infinite scroll: the "N resultados" header counts every page, so load them on demand.
        results.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as GridLayoutManager
                if (!isLoading && lastQuery.length >= 2 &&
                    adapter.itemCount < totalItems &&
                    lm.findLastVisibleItemPosition() >= adapter.itemCount - 12
                ) {
                    isLoading = true
                    pendingPage = currentPage + 1
                    vm.search(lastQuery, pendingPage)
                }
            }
        })

        skeleton.layoutManager = GridLayoutManager(this, GRID_COLUMNS)
        skeleton.setHasFixedSize(true)
        skeleton.adapter = SkeletonAdapter()

        observe()

        // Show the keyboard only while the input has focus (not forced on every resume), so
        // navigating down to the results hides it and BACK leaves the screen cleanly.
        input.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) showIme(input) else hideIme() }

        input.doAfterTextChanged { text ->
            val q = text?.toString()?.trim().orEmpty()
            searchJob?.cancel()
            lastQuery = q
            currentPage = 1
            pendingPage = 1
            totalItems = 0
            isLoading = false
            if (q.length < 2) {
                // Also abort any in-flight request: a late page for the previous query must not
                // overwrite the hint state (the collector double-checks lastQuery anyway).
                vm.cancelSearch()
                adapter.submitList(emptyList())
                showState(Showing.HINT)
                hint.text = getString(R.string.search_prompt)
                return@doAfterTextChanged
            }
            showState(Showing.SKELETON)
            searchJob = lifecycleScope.launch {
                delay(350)
                vm.search(q, 1)
            }
        }
        input.requestFocus()
    }

    /** showSoftInput is ignored while the window isn't focused yet (it always is during
     *  onCreate), so re-issue the initial IME request once the window gains focus. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::input.isInitialized && input.hasFocus()) showIme(input)
    }

    private fun observe() {
        lifecycleScope.launch {
            vm.catalog.collectLatest { result ->
                val page = (result ?: return@collectLatest).page
                if (lastQuery.length < 2) return@collectLatest   // stale page for an erased query
                isLoading = false
                currentPage = pendingPage
                totalItems = page.total
                val list = if (currentPage > 1) adapter.currentList + page.results else page.results
                if (list.isEmpty()) {
                    // Vaciar el adaptador además de esconderlo: si no, "sin resultados" convive
                    // con las tarjetas de la búsqueda anterior guardadas en la lista.
                    adapter.submitList(emptyList())
                    showState(Showing.HINT)
                    hint.text = getString(R.string.no_results)
                } else {
                    adapter.submitList(list)
                    count.text = getString(R.string.search_results_count, page.total)
                    count.visibility = View.VISIBLE
                    showState(Showing.RESULTS)
                }
            }
        }
        lifecycleScope.launch {
            vm.error.collectLatest { err ->
                err ?: return@collectLatest
                isLoading = false
                if (pendingPage > 1 && adapter.itemCount > 0) {
                    // A failed pagination shouldn't wipe the results already on screen.
                    Toast.makeText(this@SearchActivity, R.string.load_more_failed, Toast.LENGTH_SHORT).show()
                } else {
                    // ⚠️ Lo que distingue paginar de buscar es la PÁGINA pedida, no que la lista
                    // tenga tarjetas: al empezar una búsqueda nueva se enseña el esqueleto pero el
                    // adaptador sigue guardando los resultados de la búsqueda anterior, así que un
                    // fallo de la primera página salía como "no se pudieron cargar más" —un aviso
                    // que se va solo— y dejaba el esqueleto pulsando para siempre, sin mensaje.
                    adapter.submitList(emptyList())
                    showState(Showing.HINT)
                    hint.text = err
                }
            }
        }
    }

    private fun showIme(v: View) {
        v.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideIme() {
        val token = currentFocus?.windowToken ?: window.decorView.windowToken
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(token, 0)
    }

    private enum class Showing { HINT, SKELETON, RESULTS }

    private fun showState(state: Showing) {
        hint.visibility    = if (state == Showing.HINT)    View.VISIBLE else View.GONE
        results.visibility = if (state == Showing.RESULTS) View.VISIBLE else View.GONE
        if (state != Showing.RESULTS) count.visibility = View.GONE
        if (state == Showing.SKELETON) showSkeleton() else hideSkeleton()
    }

    private fun showSkeleton() {
        skeleton.visibility = View.VISIBLE
        skeletonAnim?.cancel()
        skeletonAnim = android.animation.ObjectAnimator.ofFloat(skeleton, View.ALPHA, 0.4f, 0.9f).apply {
            duration = 750
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            start()
        }
    }

    private fun hideSkeleton() {
        skeletonAnim?.cancel(); skeletonAnim = null
        skeleton.alpha = 1f
        skeleton.visibility = View.GONE
    }
}
