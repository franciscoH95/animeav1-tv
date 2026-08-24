package com.animeav1.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.animeav1.data.AnimeRepository
import com.animeav1.data.model.Series
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SeriesViewModel : ViewModel() {

    private val _series = MutableStateFlow<Series?>(null)
    val series = _series.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private var loadedSlug: String? = null

    fun loadSeries(slug: String) {
        // Skip if same slug already loaded with no error (cache in repo handles freshness)
        if (slug == loadedSlug && _series.value != null && _error.value == null) return
        loadedSlug = slug
        _series.value = null
        _error.value  = null
        viewModelScope.launch {
            runCatching { AnimeRepository.getSeries(slug) }
                .onSuccess { _series.value = it }
                .onFailure { _error.value  = it.message ?: "Error de red" }
        }
    }
}
