package com.animeav1.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.animeav1.data.AnimeRepository
import com.animeav1.data.model.ScheduleItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ScheduleViewModel : ViewModel() {

    private val _schedule = MutableStateFlow<Map<String, List<ScheduleItem>>?>(null)
    val schedule = _schedule.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private var loadJob: kotlinx.coroutines.Job? = null

    init { load() }

    fun load() {
        if (loadJob?.isActive == true) return
        _error.value = null
        loadJob = viewModelScope.launch {
            runCatching { AnimeRepository.getSchedule() }
                .onSuccess { _schedule.value = it }
                .onFailure { _error.value = it.message ?: "Error de red" }
        }
    }
}
