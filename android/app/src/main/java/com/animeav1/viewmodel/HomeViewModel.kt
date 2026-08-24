package com.animeav1.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.animeav1.data.AnimeRepository
import com.animeav1.data.model.Anime
import com.animeav1.data.model.CatalogFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Filas de descubrimiento de Inicio, sacadas del catálogo del sitio.
 *
 * ⚠️ **No se reutiliza `BrowseViewModel`**: ese acumula páginas para el scroll infinito del catálogo,
 * y aquí cada fila es una única página de 20 que no crece. Colgarse de él haría que Inicio y Catálogo
 * se pisaran el estado de paginación.
 *
 * Cada fila carga y falla por separado: una fila que no responde no debe dejar Inicio en blanco.
 */
class HomeViewModel : ViewModel() {

    /** Una fila del carrusel: su estado de carga es independiente del de las demás. */
    data class Row(
        val items: List<Anime> = emptyList(),
        val loading: Boolean = true,
        val failed: Boolean = false
    )

    /** Filas que se piden al catálogo, en el orden en que aparecen en pantalla. */
    enum class Kind(val filter: CatalogFilter) {
        /** Lo que se está emitiendo ahora: es la razón por la que alguien abre una app de anime. */
        EN_EMISION(CatalogFilter(status = "emision")),

        /** `order=score` — verificado contra el sitio; sin esto el catálogo solo tenía un orden. */
        MEJOR_VALORADOS(CatalogFilter(order = "score")),

        PELICULAS(CatalogFilter(category = "pelicula"))
    }

    private val _rows = MutableStateFlow(Kind.values().associateWith { Row() })
    val rows: StateFlow<Map<Kind, Row>> = _rows.asStateFlow()

    /** Se carga una sola vez por sesión: el catálogo cambia de un día para otro, no de un minuto a otro. */
    private var started = false

    fun load() {
        if (started) return
        started = true
        Kind.values().forEach { kind ->
            viewModelScope.launch {
                runCatching { AnimeRepository.getCatalog(page = 1, filter = kind.filter) }
                    .onSuccess { page -> update(kind) { Row(page.results, loading = false) } }
                    .onFailure { update(kind) { Row(loading = false, failed = true) } }
            }
        }
    }

    /** Reintenta solo la fila que falló, sin tocar las que sí cargaron. */
    fun retry(kind: Kind) {
        update(kind) { Row(loading = true) }
        viewModelScope.launch {
            runCatching { AnimeRepository.getCatalog(page = 1, filter = kind.filter) }
                .onSuccess { page -> update(kind) { Row(page.results, loading = false) } }
                .onFailure { update(kind) { Row(loading = false, failed = true) } }
        }
    }

    private inline fun update(kind: Kind, block: (Row) -> Row) {
        _rows.value = _rows.value.toMutableMap().apply { put(kind, block(getValue(kind))) }
    }
}
