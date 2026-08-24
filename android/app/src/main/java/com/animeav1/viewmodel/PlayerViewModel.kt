package com.animeav1.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.animeav1.data.AnimeRepository
import com.animeav1.data.model.EmbedServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URI

class PlayerViewModel : ViewModel() {

    private val _embeds  = MutableStateFlow<List<EmbedServer>?>(null)
    val embeds  = _embeds.asStateFlow()

    private val _error   = MutableStateFlow<String?>(null)
    val error   = _error.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    /**
     * State of resolving the direct video URL for the selected server.
     *
     * Carries the whole [EmbedServer], not just its name: the same server ("HLS") exists in both
     * the SUB and the DUB track, so a name is no longer enough to say which entry this is about.
     */
    sealed class StreamState {
        object Idle : StreamState()
        data class Resolving(val embed: EmbedServer) : StreamState()
        data class Ready(val url: String, val referer: String, val embed: EmbedServer) : StreamState()
        data class Failed(val embed: EmbedServer) : StreamState()
    }

    private val _stream = MutableStateFlow<StreamState>(StreamState.Idle)
    val stream = _stream.asStateFlow()

    private var lastEmbedKey: String? = null
    private var resolveJob: Job? = null

    fun loadEmbeds(slug: String, number: Int) {
        val key = "$slug:$number"
        if (key == lastEmbedKey && _embeds.value?.isNotEmpty() == true) return
        lastEmbedKey = key
        _loading.value = true
        viewModelScope.launch {
            runCatching { AnimeRepository.getEmbeds(slug, number) }
                .onSuccess { _embeds.value = it; _loading.value = false }
                .onFailure { _error.value = it.message ?: "Error al cargar servidores"; _loading.value = false }
        }
    }

    /**
     * Resolve the embed page into a direct .mp4/.m3u8 URL playable by ExoPlayer,
     * deriving the Referer from the embed host (most CDNs require it).
     */
    fun resolveStream(embed: EmbedServer) {
        resolveJob?.cancel()
        _stream.value = StreamState.Resolving(embed)
        resolveJob = viewModelScope.launch {
            val url = try {
                AnimeRepository.extractStreamUrl(embed.url)
            } catch (e: CancellationException) {
                // This resolve was superseded (user picked another server, or clearStream ran).
                // Swallowing it would publish Failed(old server) on top of the new server's
                // Resolving and bounce the user back to the panel they just used.
                throw e
            } catch (e: Exception) {
                null
            }
            _stream.value = if (url != null) {
                StreamState.Ready(url, refererOf(embed.url), embed)
            } else {
                StreamState.Failed(embed)
            }
        }
    }

    /**
     * Olvida el último error. Hace falta antes de reintentar: `_error` es un StateFlow y conflata
     * valores iguales, así que si el reintento vuelve a fallar con el mismo mensaje el colector no
     * recibiría nada y la pantalla se quedaría cargando para siempre.
     */
    fun clearError() { _error.value = null }

    /**
     * Olvida la lista de servidores para que el siguiente [loadEmbeds] vuelva a emitir sí o sí.
     *
     * ⚠️ Hace falta por la misma razón que [clearError], y es el caso que se escapó: cuando la lista
     * llegó **vacía** ("no hay servidores"), repedirla vuelve a poner `emptyList()` — el MISMO valor—,
     * y `MutableStateFlow` conflata valores iguales, así que el colector no se enteraba y la pantalla
     * se quedaba con el spinner para siempre, sin mensaje y sin ningún botón. También se limpia
     * `lastEmbedKey`, que si no cortocircuitaría la petición.
     */
    fun clearEmbeds() {
        lastEmbedKey = null
        _embeds.value = null
    }

    /** Mark the current stream as consumed so lifecycle re-entry doesn't replay a stale state. */
    fun clearStream() {
        resolveJob?.cancel()
        _stream.value = StreamState.Idle
    }

    private fun refererOf(embedUrl: String): String =
        runCatching { URI(embedUrl).let { "${it.scheme}://${it.host}/" } }
            .getOrNull() ?: AnimeRepository.BASE_URL
}
