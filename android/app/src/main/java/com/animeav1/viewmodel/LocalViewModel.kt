package com.animeav1.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeav1.data.AnimeRepository
import com.animeav1.data.LocalRepository
import com.animeav1.data.ProfileManager
import com.animeav1.data.local.AppDatabase
import com.animeav1.data.local.FavoriteSeries
import com.animeav1.data.local.ListType
import com.animeav1.data.local.WatchedEpisode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class LocalViewModel(app: Application) : AndroidViewModel(app) {

    val repo = LocalRepository(AppDatabase.get(app))

    // All favorites (all lists combined) — Eagerly so Room changes propagate immediately
    val favorites: StateFlow<List<FavoriteSeries>> =
        repo.getFavorites()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Slug of the series currently open in SeriesFragment
    private val _currentSlug = MutableStateFlow("")

    // Derives list membership reactively from Room
    val currentListType: StateFlow<ListType?> = combine(_currentSlug, favorites) { slug, favList ->
        if (slug.isBlank()) null
        else favList.find { it.slug == slug }?.let { entry ->
            if (entry.listType == "none") null else ListType.fromKey(entry.listType)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val currentIsFavorite: StateFlow<Boolean> = combine(_currentSlug, favorites) { slug, favList ->
        favList.find { it.slug == slug }?.isFavorite ?: false
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun checkListType(slug: String) {
        _currentSlug.value = slug
    }

    fun addToList(
        slug: String, title: String, coverUrl: String, listType: ListType,
        totalEpisodes: Int = 0, year: String = "", status: Int = -1, category: String = ""
    ) {
        viewModelScope.launch {
            repo.addToList(slug, title, coverUrl, listType, totalEpisodes, year, status, category)
        }
    }

    fun removeFromList(slug: String) {
        viewModelScope.launch {
            repo.removeFromList(slug)
        }
    }

    /** Backfill year/status/category and refresh the episode count when a series detail is opened. */
    fun updateSeriesMeta(slug: String, year: String, status: Int, category: String, totalEpisodes: Int) {
        viewModelScope.launch { repo.updateMeta(slug, year, status, category, totalEpisodes) }
    }

    // Refresh each airing series' episode count at most once per session (getSeries is cached).
    // ⚠️ La clave lleva el PERFIL, no solo el slug: el ViewModel es de ámbito Activity y sobrevive al
    // cambio de perfil, así que con un memo por slug el segundo perfil no refrescaba nunca su
    // totalEpisodes — y se quedaba sin el badge "NUEVO" y con el contador vistos/total desfasado.
    private val refreshedAiring = mutableSetOf<Pair<Long, String>>()

    /**
     * For the airing series in a list, fetch the current episode count in the background and update
     * `totalEpisodes`, so the "NUEVO" badge in Mi Lista reflects new releases without opening each
     * detail. Bounded: each slug is refreshed only once per session; getSeries is TTL-cached.
     */
    fun refreshAiringTotals(series: List<FavoriteSeries>) {
        // Claiming the slug up front is what keeps a re-entrant call (setTotalEpisodes updates
        // Room, Room re-emits, Mi Lista calls back in) from refetching what is already in
        // flight. The claim is released on failure so a refresh that died with the network
        // isn't remembered as done for the rest of the session.
        val profile = ProfileManager.requireActiveId()
        val toRefresh = series.filter { it.status == 2 && refreshedAiring.add(profile to it.slug) }
        if (toRefresh.isEmpty()) return
        viewModelScope.launch {
            toRefresh.forEach { fav ->
                runCatching {
                    val live = AnimeRepository.getSeries(fav.slug).episodes.size
                    if (live > 0 && live != fav.totalEpisodes) repo.setTotalEpisodes(fav.slug, live)
                }.onFailure { refreshedAiring.remove(profile to fav.slug) }
            }
        }
    }

    fun toggleFavorite(
        slug: String, title: String, coverUrl: String, totalEpisodes: Int,
        year: String = "", status: Int = -1, category: String = ""
    ) {
        viewModelScope.launch {
            repo.setFavorite(slug, title, coverUrl, totalEpisodes, !currentIsFavorite.value, year, status, category)
        }
    }

    // Watched episodes for the current series. Derived from _currentSlug so only ONE Room
    // collector is ever active — flatMapLatest cancels the previous slug's collector instead
    // of stacking a new uncancelled one on every series open (which caused stale watched badges).
    @OptIn(ExperimentalCoroutinesApi::class)
    val watchedEpisodes: StateFlow<Set<Int>> = _currentSlug
        .flatMapLatest { slug ->
            if (slug.isBlank()) flowOf(emptySet())
            else repo.getWatchedNumbers(slug).map { it.toSet() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun observeWatched(slug: String) {
        _currentSlug.value = slug
    }

    /**
     * Marking an episode from the series detail goes through the same repository call the player
     * uses, so both paths promote the series identically. This used to carry its own weaker copy
     * of the rule: it only promoted when the series was in no list at all, never reached
     * Completadas, and created the row with totalEpisodes = 0 (which left Mi Lista showing
     * "▶ 3 ep" instead of "▶ 3/12" and killed the NUEVO badge).
     */
    fun toggleWatched(
        slug: String, ep: Int, title: String = "", coverUrl: String = "", totalEpisodes: Int = 0,
        isLastEpisode: Boolean = false, year: String = "", status: Int = -1, category: String = ""
    ) {
        viewModelScope.launch {
            val wasWatched = repo.isWatched(slug, ep)
            repo.setWatched(
                slug, ep, !wasWatched,
                title, coverUrl, totalEpisodes, isLastEpisode, year, status, category
            )
        }
    }

    /**
     * Marca [ep] y todos los anteriores. Es la entrada de "marcar visto hasta aquí" de la ficha, y
     * usa la MISMA función del repositorio que el auto-marcado del reproductor: la regla de
     * promoción de listas vive solo ahí (ver CLAUDE.md).
     */
    fun markWatchedThrough(
        slug: String, ep: Int, minEpisode: Int,
        title: String, coverUrl: String, totalEpisodes: Int,
        isLastEpisode: Boolean, year: String = "", status: Int = -1, category: String = ""
    ) {
        viewModelScope.launch {
            repo.markWatchedThrough(
                slug, ep, minEpisode, title, coverUrl, totalEpisodes,
                isLastEpisode, year, status, category
            )
        }
    }

    /** Quita una serie de *Continuar viendo* (borra sus puntos de reanudación). Ver el repositorio. */
    fun removeFromContinue(slug: String) {
        viewModelScope.launch { repo.removeFromContinue(slug) }
    }

    // Recent history
    val recentHistory: StateFlow<List<WatchedEpisode>> =
        repo.getRecentHistory()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
}
