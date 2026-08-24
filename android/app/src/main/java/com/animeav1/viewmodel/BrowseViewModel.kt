package com.animeav1.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.animeav1.data.AnimeRepository
import com.animeav1.data.model.CatalogFilter
import com.animeav1.data.model.CatalogPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BrowseViewModel : ViewModel() {

    /**
     * Una respuesta del catálogo **con su número de petición**.
     *
     * ⚠️ El `seq` no es decorativo. `CatalogPage` es un `data class` y `_catalog` un StateFlow,
     * que **conflata valores iguales**: dos consultas DISTINTAS devuelven muy a menudo
     * exactamente la misma página —comprobado contra el sitio: `digim`, `digimo` y `digimon`
     * dan los mismos 15 resultados y el mismo `total`—, así que la segunda no llegaba nunca al
     * colector. En el buscador eso se veía como la pantalla **atascada en el esqueleto para
     * siempre**, sin resultados y sin mensaje, justo al terminar de escribir el nombre. Con el
     * contador cada respuesta es un valor distinto y siempre se emite.
     */
    data class CatalogResult(val seq: Long, val page: CatalogPage)

    private val _catalog = MutableStateFlow<CatalogResult?>(null)
    val catalog = _catalog.asStateFlow()

    /** Nº de la última respuesta entregada; ver [CatalogResult]. */
    private var seq = 0L

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _filter = MutableStateFlow(CatalogFilter())
    val filter = _filter.asStateFlow()

    private var currentQuery = ""
    private var fetchJob: Job? = null

    fun loadPage(page: Int) {
        // Tracked in fetchJob like search()/applyFilter() do: an untracked pagination request
        // survives a filter change and then overwrites the catalog with the OLD filter's page.
        fetchJob?.cancel()
        // _error is a StateFlow: without clearing it first, a second failure carrying the SAME
        // message conflates and never emits, and the collector — the only thing that resets
        // isLoading — never runs, killing the catalog's infinite scroll for the whole session.
        _error.value = null
        fetchJob = viewModelScope.launch {
            runCatching { AnimeRepository.getCatalog(page, currentQuery, _filter.value) }
                .onSuccess { _catalog.value = CatalogResult(++seq, it) }
                .onFailure { e -> if (e !is CancellationException) _error.value = e.message ?: "Error de red" }
        }
    }

    fun search(query: String, page: Int) {
        fetchJob?.cancel()
        currentQuery = query
        _error.value = null
        fetchJob = viewModelScope.launch {
            runCatching { AnimeRepository.getCatalog(page, query, _filter.value) }
                .onSuccess { _catalog.value = CatalogResult(++seq, it) }
                .onFailure { e -> if (e !is CancellationException) _error.value = e.message ?: "Error de red" }
        }
    }

    /** Abort an in-flight search so a stale page can't overwrite newer UI state. */
    fun cancelSearch() {
        fetchJob?.cancel()
    }

    /** Apply new filters and reload from page 1 */
    fun applyFilter(newFilter: CatalogFilter) {
        fetchJob?.cancel()
        _filter.value  = newFilter
        _catalog.value = null
        _error.value   = null
        loadPage(1)
    }
}
