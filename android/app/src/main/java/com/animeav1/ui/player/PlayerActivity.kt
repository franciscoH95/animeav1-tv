package com.animeav1.ui.player

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import coil.load
import com.animeav1.AnimeApp
import com.animeav1.R
import com.animeav1.data.AnimeImages
import com.animeav1.data.AnimeRepository
import com.animeav1.data.LocalRepository
import com.animeav1.data.StreamUrlParser
import com.animeav1.data.local.AppDatabase
import com.animeav1.data.model.AudioTrack
import com.animeav1.data.model.EmbedServer
import com.animeav1.ui.series.SeriesActivity
import com.animeav1.viewmodel.PlayerViewModel
import com.animeav1.viewmodel.PlayerViewModel.StreamState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@UnstableApi
class PlayerActivity : FragmentActivity() {

    private lateinit var vm: PlayerViewModel
    private lateinit var loadingOverlay: View
    private lateinit var epInfoBlock: View
    private lateinit var loadingBar: ProgressBar
    private lateinit var bufferingSpinner: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var errorActions: View
    private lateinit var btnErrorRetry: Button
    private lateinit var btnErrorServers: Button
    private lateinit var btnErrorExit: Button
    private lateinit var serverPanel: View
    private lateinit var serverList: androidx.recyclerview.widget.RecyclerView
    private lateinit var serverAdapter: ServerAdapter
    private lateinit var serverLayout: androidx.recyclerview.widget.LinearLayoutManager
    private lateinit var playerView: PlayerView

    // Custom controls overlay
    private lateinit var controlsOverlay: View
    private lateinit var ctrlTitle: TextView
    private lateinit var ctrlSubtitle: TextView
    private lateinit var ctrlPosition: TextView
    private lateinit var ctrlDuration: TextView
    private lateinit var ctrlTimebar: DefaultTimeBar
    private lateinit var btnPrevEp: ImageButton
    private lateinit var btnRew: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnFfwd: ImageButton
    private lateinit var btnNextEp: ImageButton
    private lateinit var btnServers: ImageButton
    private lateinit var btnWatched: ImageButton

    // Resume prompt
    private lateinit var resumeOverlay: View
    private lateinit var resumeSubtitle: TextView
    private lateinit var btnResume: Button
    private lateinit var btnRestart: Button

    // Next-episode auto-play card
    private lateinit var nextEpisodeCard: View
    private lateinit var nextEpThumb: ImageView
    private lateinit var nextEpTitle: TextView
    private lateinit var nextEpCountdown: TextView
    private lateinit var btnPlayNext: Button
    private lateinit var btnCancelNext: Button

    /** All local state goes through the repository — the promotion and resume-point rules live
     *  there, and this Activity used to carry a second, drifting copy of them. */
    private val local by lazy { LocalRepository(AppDatabase.get(applicationContext)) }

    private var player: ExoPlayer? = null
    private var progressSaver: Job? = null
    private var controlsHideJob: Job? = null
    private var controlsUpdateJob: Job? = null
    private var endMonitorJob: Job? = null
    private var nextCountdownJob: Job? = null
    private var stallWatchdogJob: Job? = null
    private var nextCardHandled = false
    /** Ya se ha lanzado la vuelta a la ficha (STATE_ENDED puede reemitirse). */
    private var returningToSeries = false

    /**
     * Modo scrub: posición objetivo mientras el usuario mantiene IZQ/DER, y cuántas repeticiones
     * lleva (el paso crece para poder cruzar un episodio entero sin soltar).
     *
     * ⚠️ Antes cada repetición de tecla era un `seekForward()` REAL: mantener DERECHA lanzaba un salto
     * de 10 s por repetición y cada uno rebufferizaba, así que atravesar 24 min llevaba ~7 s de
     * tirones. Ahora solo se mueve un número y la barra; el `seekTo` de verdad se hace UNA vez al
     * soltar la tecla.
     */
    private var scrubTargetMs = -1L
    private var scrubRepeats = 0

    private var awaitingResumeChoice = false
    private var resumePromptPositionMs = 0L

    private var embedList: List<EmbedServer> = emptyList()

    private var currentStreamUrl: String? = null
    private var currentReferer: String = AnimeRepository.BASE_URL
    private var resumePositionMs: Long = 0L
    private var resumePlayWhenReady: Boolean = true

    /** Server actually playing right now (selectedEmbed may point to a switch attempt). */
    private var playingEmbed: EmbedServer? = null

    /** Set by the "Continuar viendo" row of Inicio: resume at the saved point without prompting. */
    private var autoResume = false

    private var slug            = ""
    private var number          = 1
    private var title           = ""
    private var coverUrl        = ""
    private var backdropUrl     = ""
    private var isWatched       = false
    private var totalEpisodes   = 0
    private var minEpisode      = 1
    private var maxEpisode      = 0
    private var seriesStatus     = -1
    private var seriesStartDate  = ""
    private var seriesCategory   = ""
    private var preferredServer: String? = null
    /** Pista de audio heredada del episodio anterior ("SUB"/"DUB"); null = sin preferencia. */
    private var preferredAudio: AudioTrack? = null

    private var selectedEmbed: EmbedServer? = null

    /** Preferencia guardada de esta serie (pista + fuente). Se lee una vez al abrir el episodio. */
    private var savedPrefs: com.animeav1.data.local.SeriesPrefs? = null

    /** True cuando el episodio ofrece SUB y DUB: solo entonces se nombra la pista en los avisos. */
    private var hasBothTracks = false

    /**
     * Fuentes ya intentadas para ESTE episodio, para que el fallback automático no vuelva a probar la
     * que acaba de fallar ni entre en bucle. Se vacía al cambiar de episodio (la Activity se recrea).
     */
    private val triedEmbeds = mutableSetOf<EmbedServer>()

    /** Se completa cuando ya se ha leído `series_prefs`; el pick por defecto la espera. */
    private val prefsReady = CompletableDeferred<Unit>()

    /** True while re-resolving a server for the episode that is already playing: resume in place
     *  on the new server, skipping the "¿Continuar viendo?" prompt. */
    private var pendingServerSwitch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        slug            = intent.getStringExtra("slug")          ?: ""
        number          = intent.getIntExtra("number", 1)
        title           = intent.getStringExtra("title")         ?: ""
        coverUrl        = intent.getStringExtra("coverUrl")      ?: ""
        backdropUrl     = intent.getStringExtra("backdropUrl")   ?: ""
        isWatched       = intent.getBooleanExtra("isWatched", false)
        totalEpisodes   = intent.getIntExtra("totalEpisodes", 0)
        minEpisode      = intent.getIntExtra("minEpisode", 1)
        maxEpisode      = intent.getIntExtra("maxEpisode", minEpisode + totalEpisodes - 1)
        seriesStatus    = intent.getIntExtra("seriesStatus", -1)
        seriesStartDate = intent.getStringExtra("startDate") ?: ""
        seriesCategory  = intent.getStringExtra("category") ?: ""
        preferredServer = intent.getStringExtra("preferredServer")
        preferredAudio  = intent.getStringExtra("preferredAudio")
            ?.let { name -> AudioTrack.values().firstOrNull { it.name == name } }
        autoResume      = intent.getBooleanExtra("autoResume", false)

        vm = ViewModelProvider(this)[PlayerViewModel::class.java]

        loadingOverlay = findViewById(R.id.loading_overlay)
        epInfoBlock    = findViewById(R.id.ep_info_block)
        loadingBar     = findViewById(R.id.loading)
        bufferingSpinner = findViewById(R.id.buffering_spinner)
        statusText     = findViewById(R.id.status_text)
        errorActions   = findViewById(R.id.error_actions)
        btnErrorRetry  = findViewById(R.id.btn_error_retry)
        btnErrorServers = findViewById(R.id.btn_error_servers)
        btnErrorExit   = findViewById(R.id.btn_error_exit)
        serverPanel    = findViewById(R.id.server_panel)
        serverList     = findViewById(R.id.server_list)
        playerView     = findViewById(R.id.player_view)

        controlsOverlay = findViewById(R.id.controls_overlay)
        ctrlTitle       = findViewById(R.id.ctrl_title)
        ctrlSubtitle    = findViewById(R.id.ctrl_subtitle)
        ctrlPosition    = findViewById(R.id.ctrl_position)
        ctrlDuration    = findViewById(R.id.ctrl_duration)
        ctrlTimebar     = findViewById(R.id.ctrl_timebar)
        btnPrevEp       = findViewById(R.id.btn_prev_ep)
        btnRew          = findViewById(R.id.btn_rew)
        btnPlayPause    = findViewById(R.id.btn_play_pause)
        btnFfwd         = findViewById(R.id.btn_ffwd)
        btnNextEp       = findViewById(R.id.btn_next_ep)
        btnServers      = findViewById(R.id.btn_servers)
        btnWatched      = findViewById(R.id.btn_watched)

        resumeOverlay  = findViewById(R.id.resume_overlay)
        resumeSubtitle = findViewById(R.id.resume_subtitle)
        btnResume      = findViewById(R.id.btn_resume)
        btnRestart     = findViewById(R.id.btn_restart)

        nextEpisodeCard = findViewById(R.id.next_episode_card)
        nextEpThumb     = findViewById(R.id.next_ep_thumb)
        nextEpTitle     = findViewById(R.id.next_ep_title)
        nextEpCountdown = findViewById(R.id.next_ep_countdown)
        btnPlayNext     = findViewById(R.id.btn_play_next)
        btnCancelNext   = findViewById(R.id.btn_cancel_next)

        btnErrorRetry.setOnClickListener { retryCurrentSource() }
        btnErrorServers.setOnClickListener { openServerPanel() }
        btnErrorExit.setOnClickListener { finish() }

        setupEpisodeInfo()
        setupServerList()
        setupControlButtons()
        setupResumeButtons()
        setupNextEpisodeCard()
        observeViewModel()
        showLoading("Cargando…")
        // La preferencia de la serie manda sobre los extras del intent: los extras solo saben del
        // episodio anterior, y esto sabe de todas las veces que el usuario ha visto esta serie.
        lifecycleScope.launch {
            savedPrefs = runCatching { local.prefsFor(slug) }.getOrNull()
            // ⚠️ La preferencia MANDA sobre los extras del intent, no al revés. Los extras solo
            // saben del episodio anterior y `goToEpisode` arrastra `selectedEmbed`, que tras un
            // fallback o un pick automático NO es lo que el usuario eligió: leyéndolos primero, un
            // stall de 25 s en YourUpload dejaba el resto del maratón en HLS (AV1), y un episodio
            // publicado solo en SUB dejaba en subtitulado una serie que se veía DOBLADA. Es el mismo
            // daño que evita no persistirlos, pero por la vía de la lectura.
            savedPrefs?.let { p ->
                if (p.server.isNotBlank()) preferredServer = p.server
                if (p.audio.isNotBlank()) {
                    AudioTrack.values().firstOrNull { it.name == p.audio }?.let { preferredAudio = it }
                }
            }
            prefsReady.complete(Unit)
            vm.loadEmbeds(slug, number)
            refreshMaxEpisode()
        }
    }

    private fun setupEpisodeInfo() {
        val imageUrl = backdropUrl.ifBlank { coverUrl }
        if (imageUrl.isNotBlank()) {
            findViewById<ImageView>(R.id.ep_backdrop).load(imageUrl) { crossfade(true) }
        }
        findViewById<TextView>(R.id.ep_badge).text = "EP $number"
        findViewById<TextView>(R.id.ep_series_title).text = title
    }

    private fun setupServerList() {
        serverAdapter = ServerAdapter { embed -> onServerSelected(embed, fromUser = true) }
        serverLayout = androidx.recyclerview.widget.LinearLayoutManager(this)
        serverList.layoutManager = serverLayout
        serverList.adapter = serverAdapter
    }

    private fun setupControlButtons() {
        ctrlTitle.text = title
        ctrlSubtitle.text =
            if (maxEpisode > 0) getString(R.string.episode_of, number, maxEpisode)
            else getString(R.string.episode_n, number)

        btnPlayPause.setOnClickListener { togglePlay(); resetHideTimer() }
        btnRew.setOnClickListener { player?.seekBack(); cancelNextIfAwayFromEnd(); resetHideTimer() }
        btnFfwd.setOnClickListener { player?.seekForward(); cancelNextIfAwayFromEnd(); resetHideTimer() }
        btnServers.setOnClickListener { openServerPanel() }
        btnWatched.setOnClickListener { toggleWatched(); resetHideTimer() }

        btnPrevEp.apply {
            // Keep focusable so the D-pad chain stays intact at the boundary; dim to signal unavailable.
            // goToEpisode() range-guards, so a click on the first episode is a harmless no-op.
            alpha = if (number > minEpisode) 1f else 0.4f
            setOnClickListener { goToEpisode(number - 1) }
        }
        btnNextEp.apply {
            alpha = if (number < maxEpisode) 1f else 0.4f
            setOnClickListener { goToNextEpisode() }
        }
        updateWatchedButton()
    }

    private fun setupResumeButtons() {
        btnResume.setOnClickListener {
            hideResumePrompt()
            resumePositionMs = resumePromptPositionMs
            initPlayer()
        }
        btnRestart.setOnClickListener {
            hideResumePrompt()
            resumePositionMs = 0L
            clearProgress()
            initPlayer()
        }
    }

    private fun goToNextEpisode() = goToEpisode(number + 1)

    private fun goToEpisode(ep: Int) {
        if (ep < minEpisode) return
        // Only bound going FORWARD. For an airing series maxEpisode can be a stale cached count
        // that is lower than the episode being watched (Inicio derives it from favorite_series),
        // and that must not turn "previous episode" into a silent no-op.
        if (ep > number && ep > maxEpisode) return
        lifecycleScope.launch {
            // The target episode may already be watched (e.g. stepping back one episode).
            val watched = local.isWatched(slug, ep)
            val nextIntent = Intent(this@PlayerActivity, PlayerActivity::class.java).apply {
                putExtra("slug", slug)
                putExtra("number", ep)
                putExtra("title", title)
                putExtra("coverUrl", coverUrl)
                putExtra("backdropUrl", backdropUrl)
                putExtra("totalEpisodes", totalEpisodes)
                putExtra("minEpisode",     minEpisode)
                putExtra("maxEpisode",     maxEpisode)
                putExtra("seriesStatus",   seriesStatus)
                putExtra("startDate",      seriesStartDate)
                putExtra("category",       seriesCategory)
                putExtra("isWatched", watched)
                // Arrastra servidor Y pista: si venías viendo el doblaje, el episodio siguiente
                // también empieza doblado.
                selectedEmbed?.let {
                    putExtra("preferredServer", it.server)
                    putExtra("preferredAudio", it.audio.name)
                }
            }
            startActivity(nextIntent)
            finish()
        }
    }

    /**
     * Refresca el número del último episodio con el conteo VIVO del sitio.
     *
     * Los extras pueden traerlo de una fila cacheada de `favorite_series` —la fila *Continuar viendo*
     * de Inicio no refresca los totales, eso solo pasa al abrir la ficha—, y en una serie en emisión
     * ese conteo va por detrás. Cuando coincide con el episodio que se está viendo, el reproductor
     * cree que es el último: ni ofrece el siguiente, ni deja avanzar con ⏭, y al marcar visto lo
     * trata como final de serie (moviéndola a Completadas). `getSeries` está cacheada, así que casi
     * siempre no cuesta red.
     */
    private fun refreshMaxEpisode() {
        lifecycleScope.launch {
            val real = runCatching { AnimeRepository.getSeries(slug) }.getOrNull()
                ?.episodes?.maxOfOrNull { it.number } ?: return@launch
            if (real <= maxEpisode) return@launch
            maxEpisode = real
            ctrlSubtitle.text = getString(R.string.episode_of, number, maxEpisode)
            btnNextEp.alpha = if (number < maxEpisode) 1f else 0.4f
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            vm.embeds.collectLatest { embeds ->
                embeds ?: return@collectLatest
                if (embeds.isEmpty()) { showError("No hay servidores disponibles"); return@collectLatest }
                embedList = embeds
                hasBothTracks = embeds.any { it.audio == AudioTrack.SUB } &&
                                embeds.any { it.audio == AudioTrack.DUB }
                serverAdapter.setServers(embeds)
                // ⚠️ Esperar la preferencia de la serie. En un arranque limpio el orden se cumple
                // solo (loadEmbeds se lanza dentro de esa misma corrutina), pero al recrearse la
                // Activity el StateFlow retenido reemite la lista al instante y pickDefaultEmbed
                // corría con preferredServer/preferredAudio todavía a null: la serie que se veía
                // doblada arrancaba en subtitulado.
                prefsReady.await()
                onServerSelected(pickDefaultEmbed(embeds))
            }
        }
        lifecycleScope.launch {
            vm.error.collectLatest { err ->
                err ?: return@collectLatest
                showError(err)
            }
        }
        lifecycleScope.launch {
            vm.stream.collectLatest { state ->
                when (state) {
                    is StreamState.Resolving -> showLoading("Cargando vídeo desde ${labelOf(state.embed)}…")
                    is StreamState.Ready -> playStream(state.url, state.referer)
                    is StreamState.Failed -> {
                        if (player != null) {
                            // Mid-playback switch failed: the old server keeps playing — don't
                            // cover it with the fullscreen error, just restore the selection.
                            pendingServerSwitch = false
                            selectedEmbed = playingEmbed
                            serverAdapter.setSelected(playingEmbed)
                            hideLoading()
                            val previous = playingEmbed?.let { labelOf(it) } ?: "el servidor anterior"
                            Toast.makeText(
                                this@PlayerActivity,
                                "No se pudo cargar ${labelOf(state.embed)}. Sigues en $previous.",
                                Toast.LENGTH_LONG
                            ).show()
                            openServerPanel()
                        } else if (nextUntriedSource(state.embed) != null) {
                            // ⚠️ Antes esto era 25 s de spinner y luego un panel de "servidores": una
                            // decisión técnica que un usuario final no puede tomar. Se prueba sola la
                            // siguiente fuente de la MISMA pista de audio, que es lo que haría
                            // cualquier app de streaming, y solo si se agotan se pregunta.
                            // ⚠️ Leer ANTES de resetear: si venimos de un fallback en cadena, esto
                            // es lo único que recuerda que había un stream reproduciéndose y que su
                            // posición hay que conservarla.
                            val keepPosition = pendingServerSwitch
                            pendingServerSwitch = false
                            currentStreamUrl = null
                            playingEmbed = null
                            val next = nextUntriedSource(state.embed)!!
                            statusText.text = getString(R.string.source_falling_back, labelOf(next))
                            onServerSelected(next, switchInPlace = keepPosition)
                        } else {
                            // Sin player, pero `currentStreamUrl` puede seguir apuntando al stream
                            // ANTERIOR: los colectores no son lifecycle-aware, así que un cambio de
                            // servidor iniciado y luego mandado a background (HOME) libera el player
                            // en onStop y aun así entrega aquí el Failed. Si no se invalida, al
                            // volver `onStart → initPlayer()` reproduce el stream viejo pero fija
                            // `playingEmbed = selectedEmbed` = el embed que acaba de fallar, y esa
                            // identidad falsa se propaga a `preferredServer`/`preferredAudio` — el
                            // episodio siguiente arrancaría doblado con el usuario oyendo subtitulado.
                            pendingServerSwitch = false
                            currentStreamUrl = null
                            playingEmbed = null
                            showError("No se pudo cargar ${labelOf(state.embed)}. Elige otro servidor.")
                            openServerPanel()
                            vm.clearStream()
                        }
                    }
                    StreamState.Idle -> Unit
                }
            }
        }
    }

    /**
     * Servidor con el que arranca el episodio. Respeta lo que el usuario venía usando en el
     * episodio anterior — servidor **y** pista de audio: si estaba viendo el doblaje, el
     * siguiente episodio no debe saltar al subtitulado solo porque SUB va primero en la lista.
     */
    private fun pickDefaultEmbed(embeds: List<EmbedServer>): EmbedServer {
        val byName = { e: EmbedServer -> preferredServer?.equals(e.server, ignoreCase = true) == true }
        return embeds.firstOrNull { byName(it) && it.audio == preferredAudio }   // servidor + pista
            ?: embeds.firstOrNull { it.audio == preferredAudio }                 // al menos la pista
            ?: embeds.firstOrNull { byName(it) }                                 // al menos el servidor
            ?: embeds.first()
    }

    /** "HLS", o "HLS (Doblado)" cuando el episodio ofrece las dos pistas y hay que distinguir. */
    private fun labelOf(embed: EmbedServer): String {
        if (!hasBothTracks) return embed.server
        val track = getString(
            if (embed.audio == AudioTrack.DUB) R.string.audio_dub else R.string.audio_sub
        )
        return getString(R.string.server_with_track, embed.server, track)
    }

    /**
     * @param fromUser true SOLO si esta fuente la ha elegido el usuario en el panel. ⚠️ Es lo único
     *   que se guarda en `series_prefs`: por aquí pasan también el pick automático del arranque y el
     *   fallback por fallo de CDN, y persistir esos convertía un accidente en la preferencia
     *   permanente de la serie. Un episodio recién emitido que el sitio publica solo en SUB haría que
     *   la serie que el usuario veía DOBLADA se abriera en subtitulado a partir de entonces, y un
     *   stall de 25 s en YourUpload (el único H.264) la dejaría fijada en HLS.
     * @param switchInPlace si el stream nuevo debe reanudar donde iba el anterior en vez de volver a
     *   preguntar "¿Continuar viendo?". ⚠️ Lo decide **quien llama**: el fallback libera el player
     *   ANTES de llegar aquí, así que `player != null` ya vale false y calcularlo aquí perdía la
     *   posición (el usuario veía el episodio empezar de cero, o un modal a mitad de reproducción).
     */
    private fun onServerSelected(
        embed: EmbedServer,
        fromUser: Boolean = false,
        switchInPlace: Boolean = player != null
    ) {
        selectedEmbed = embed
        if (fromUser) {
            // Fire-and-forget en appScope: debe sobrevivir a que el usuario salga del reproductor
            // justo después de elegir.
            val s = slug
            val audio = embed.audio.name
            val server = embed.server
            val repo = local
            AnimeApp.appScope.launch { repo.rememberPrefs(s, audio, server) }
        }
        serverAdapter.setSelected(embed)
        closeServerPanel()
        pendingServerSwitch = switchInPlace
        triedEmbeds += embed
        showLoading("Cargando vídeo desde ${labelOf(embed)}…")
        vm.resolveStream(embed)
    }

    // ── ExoPlayer ─────────────────────────────────────────────────────────────

    private fun playStream(url: String, referer: String) {
        val switching = pendingServerSwitch
        pendingServerSwitch = false
        releasePlayer()                 // saves progress and sets resumePositionMs = last position
        currentStreamUrl = url
        currentReferer = referer
        resumePlayWhenReady = true

        if (switching) {
            // Mid-playback server switch: keep the position releasePlayer() just captured and
            // resume on the new server without re-prompting "¿Continuar viendo?".
            awaitingResumeChoice = false
            initPlayer()
            return
        }

        resumePositionMs = 0L
        awaitingResumeChoice = true
        lifecycleScope.launch {
            val saved = local.progressFor(slug, number)
            if (currentStreamUrl != url) return@launch
            if (saved != null &&
                saved.positionMs > LocalRepository.RESUME_MIN_MS &&
                !LocalRepository.isFinished(saved.positionMs, saved.durationMs)
            ) {
                if (autoResume) {
                    // Launched from Inicio's "Continuar viendo": the user already chose to resume.
                    autoResume = false
                    awaitingResumeChoice = false
                    resumePositionMs = saved.positionMs
                    initPlayer()
                } else {
                    showResumePrompt(saved.positionMs)
                }
            } else {
                awaitingResumeChoice = false
                resumePositionMs = 0L
                initPlayer()
            }
        }
    }

    private fun initPlayer() {
        val url = currentStreamUrl ?: return
        if (player != null) return
        if (awaitingResumeChoice) return
        // Stream resolution can finish with the Activity stopped (HOME mid-load): don't create
        // a player that would play audio in background — onStart() re-calls initPlayer().
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(AnimeRepository.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to currentReferer,
                    // ⚠️ Sin esto NO se reproduce nada por HLS: el Cloudflare que hay delante del
                    // CDN de Zilla devuelve 403 a cada segmento cuyo request no traiga
                    // Sec-Fetch-Site: same-origin. La playlist .m3u8 sí pasa, así que media3 no
                    // recibe ningún error — se queda en STATE_BUFFERING hasta que el watchdog de
                    // stream lo mata con "HLS no responde". Ver StreamUrlParser.secFetchSite.
                    "Sec-Fetch-Site" to StreamUrlParser.secFetchSite(url, currentReferer),
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Dest" to "empty"
                )
            )

        val audioAttrs = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            // Request audio focus (pause/duck for calls, assistant, other media) and
            // pause when audio becomes "noisy" (headphones/BT disconnect).
            .setAudioAttributes(audioAttrs, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()

        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                onPlaybackError()
            }
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING ->
                        // Only show the small spinner if the big loading overlay isn't already up,
                        // so the initial load never shows two spinners at once.
                        if (loadingOverlay.visibility != View.VISIBLE) {
                            bufferingSpinner.visibility = View.VISIBLE
                        }
                    Player.STATE_READY -> { hideLoading(); bufferingSpinner.visibility = View.GONE }
                    Player.STATE_ENDED -> {
                        bufferingSpinner.visibility = View.GONE
                        clearProgress(); autoMarkWatched()
                        returnToSeriesIfLastEpisode()
                    }
                }
                updatePlayPauseLabel()
                updateKeepScreenOn()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                updatePlayPauseLabel()
                updateKeepScreenOn()
            }
        })

        // The HLS URL (Zilla) has no .m3u8 extension, so set the MIME type explicitly.
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .apply { if ("m3u8" in url) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        exo.setMediaItem(mediaItem)
        exo.seekTo(resumePositionMs)
        exo.playWhenReady = resumePlayWhenReady
        exo.prepare()

        player = exo
        playingEmbed = selectedEmbed
        playerView.player = exo
        startProgressSaver()
        startEndMonitor()
        startStallWatchdog()
    }

    private fun releasePlayer() {
        scrubTargetMs = -1L
        scrubRepeats = 0
        stopProgressSaver()
        stopEndMonitor()
        cancelNextEpisode()   // stop any countdown / hide the card so it can't fire while stopped
        stallWatchdogJob?.cancel(); stallWatchdogJob = null
        hideControls()        // also cancels the 500ms refresh loop and the auto-hide timer
        bufferingSpinner.visibility = View.GONE
        player?.let {
            resumePositionMs = it.currentPosition
            resumePlayWhenReady = it.playWhenReady
            saveProgress(it.currentPosition, it.duration)
            it.release()
        }
        player = null
        playerView.player = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Un `PlaybackException` de media3 es tan poco accionable para el usuario como un CDN callado:
     * también prueba sola la siguiente fuente de la misma pista. `triedEmbeds` lo acota, así que como
     * mucho se recorre la pista una vez antes de preguntar.
     */
    private fun onPlaybackError() {
        val failed = playingEmbed ?: selectedEmbed
        val wasPlaying = player != null
        releasePlayer()
        currentStreamUrl = null
        vm.clearStream()
        val next = failed?.let { nextUntriedSource(it) }
        if (next != null) {
            statusText.text = getString(R.string.source_falling_back, labelOf(next))
            onServerSelected(next, switchInPlace = wasPlaying)
            return
        }
        showError("Error de reproducción. Prueba otro servidor.")
        openServerPanel()
    }

    /** Watchdog: buffering that doesn't download anything for a while counts as a dead stream
     *  (some CDNs accept the connection and then never send segments — no error is ever raised). */
    private fun startStallWatchdog() {
        stallWatchdogJob?.cancel()
        stallWatchdogJob = lifecycleScope.launch {
            var lastBuffered = -1L
            var stalledMs = 0L
            while (isActive) {
                delay(STALL_CHECK_INTERVAL_MS)
                val p = player ?: continue
                // Only count a stall while we're actually trying to play: media3 can sit in
                // BUFFERING with a full buffer while paused, and killing a healthy paused
                // stream after 25s ("X no responde") is worse than the bug this guards against.
                if (p.playbackState == Player.STATE_BUFFERING && p.playWhenReady) {
                    val buffered = p.bufferedPosition
                    if (buffered != lastBuffered) {
                        lastBuffered = buffered
                        stalledMs = 0L
                    } else {
                        stalledMs += STALL_CHECK_INTERVAL_MS
                        if (stalledMs >= STALL_TIMEOUT_MS) {
                            onStallTimeout()
                            return@launch
                        }
                    }
                } else {
                    stalledMs = 0L
                    lastBuffered = p.bufferedPosition
                }
            }
        }
    }

    /**
     * Siguiente fuente sin probar **de la misma pista de audio** que [failed].
     *
     * De la misma pista a propósito: caer del doblaje al subtitulado por un CDN caído cambiaría el
     * idioma sin avisar, que es peor que el error. Si esa pista se agota, se rinde y pregunta.
     */
    private fun nextUntriedSource(failed: EmbedServer): EmbedServer? =
        embedList.firstOrNull { it.audio == failed.audio && it !in triedEmbeds }

    private fun onStallTimeout() {
        val stalled = playingEmbed ?: selectedEmbed
        val label = stalled?.let { labelOf(it) }
        // ⚠️ Antes de releasePlayer(): después ya no se puede saber que había algo reproduciéndose.
        val wasPlaying = player != null
        releasePlayer()
        currentStreamUrl = null
        vm.clearStream()
        // Un CDN que acepta la conexión y no manda un byte es EL caso típico de "prueba otra fuente":
        // el usuario no puede hacer nada con esa información, así que se intenta solo.
        val next = stalled?.let { nextUntriedSource(it) }
        if (next != null) {
            statusText.text = getString(R.string.source_falling_back, labelOf(next))
            onServerSelected(next, switchInPlace = wasPlaying)
            return
        }
        showError(
            if (label != null) getString(R.string.source_all_failed, label)
            else getString(R.string.source_none_works)
        )
        openServerPanel()
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) {
            p.seekTo(0)
            p.playWhenReady = true
        } else {
            p.playWhenReady = !p.playWhenReady
        }
        updatePlayPauseLabel()
    }

    private fun updatePlayPauseLabel() {
        val p = player
        val playing = p != null && p.playWhenReady && p.playbackState != Player.STATE_ENDED
        btnPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow)
        btnPlayPause.contentDescription = if (playing) "Pausar" else "Reproducir"
    }

    /** Keep the screen awake only while actually playing (TVs sleep on input-idle otherwise). */
    private fun updateKeepScreenOn() {
        val p = player
        val keepOn = p != null && p.playWhenReady && p.playbackState != Player.STATE_ENDED
        if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ── Custom controls overlay ────────────────────────────────────────────────

    private val isControlsVisible: Boolean get() = controlsOverlay.visibility == View.VISIBLE

    private fun showControls() {
        if (player == null) return
        controlsOverlay.visibility = View.VISIBLE
        // The loading overlay's big "EP N" label shares the bottom strip with the timebar.
        epInfoBlock.visibility = View.INVISIBLE
        updatePlayPauseLabel()
        // Land on the scrubber so LEFT/RIGHT seek immediately; DOWN reaches the button row.
        ctrlTimebar.post { ctrlTimebar.requestFocus() }
        startControlsUpdater()
        resetHideTimer()
    }

    private fun hideControls() {
        controlsOverlay.visibility = View.GONE
        epInfoBlock.visibility = View.VISIBLE
        controlsUpdateJob?.cancel(); controlsUpdateJob = null
        controlsHideJob?.cancel(); controlsHideJob = null
    }

    /** Re-arm the auto-hide timer; paused playback keeps the controls on screen. */
    private fun resetHideTimer() {
        controlsHideJob?.cancel()
        if (player?.playWhenReady == true) {
            controlsHideJob = lifecycleScope.launch {
                delay(CONTROLS_TIMEOUT_MS)
                hideControls()
            }
        }
    }

    private fun startControlsUpdater() {
        controlsUpdateJob?.cancel()
        controlsUpdateJob = lifecycleScope.launch {
            while (isActive) {
                val p = player
                // Mientras hay un scrub en curso la barra muestra el OBJETIVO, no la posición real:
                // sobrescribirla aquí haría que el indicador volviera atrás en cada tick.
                if (p != null && scrubTargetMs < 0L) {
                    val dur = p.duration.coerceAtLeast(0)
                    val pos = p.currentPosition.coerceAtLeast(0)
                    ctrlTimebar.setDuration(dur)
                    ctrlTimebar.setPosition(pos)
                    ctrlTimebar.setBufferedPosition(p.bufferedPosition.coerceAtLeast(0))
                    ctrlPosition.text = formatTime(pos)
                    // Lo que QUEDA, no la duración: es lo que se quiere saber a mitad de episodio
                    // ("¿me da tiempo a otro?"). La duración total ya se deduce de la barra.
                    ctrlDuration.text =
                        if (dur > 0L) getString(R.string.time_remaining, formatTime(dur - pos))
                        else formatTime(dur)
                    updatePlayPauseLabel()
                }
                delay(CONTROLS_UPDATE_MS)
            }
        }
    }

    /**
     * Mueve la posición OBJETIVO y refleja el cambio en la barra, sin tocar el player todavía.
     *
     * El paso crece con las repeticiones (10s → 30s → 60s) para que mantener la tecla cruce un
     * episodio largo en un par de segundos, que es lo que hace el scrub de cualquier app de TV.
     */
    private fun scrub(forward: Boolean) {
        val p = player ?: return
        val duration = p.duration
        if (scrubTargetMs < 0L) scrubTargetMs = p.currentPosition.coerceAtLeast(0L)
        scrubRepeats++
        val step = when {
            scrubRepeats <= 3  -> 10_000L
            scrubRepeats <= 10 -> 30_000L
            else               -> 60_000L
        }
        val max = if (duration > 0L) duration else Long.MAX_VALUE
        scrubTargetMs = (scrubTargetMs + if (forward) step else -step).coerceIn(0L, max)
        // Solo la UI: el seek real espera a que se suelte la tecla.
        ctrlTimebar.setPosition(scrubTargetMs)
        ctrlPosition.text = formatTime(scrubTargetMs)
        if (duration > 0L) ctrlDuration.text = getString(R.string.time_remaining, formatTime(duration - scrubTargetMs))
        resetHideTimer()
    }

    /**
     * Aplica el scrub acumulado. UN solo `seekTo`, así que solo se rebufferiza una vez.
     *
     * @return true si había un scrub pendiente (y por tanto se consume la tecla).
     */
    private fun commitScrub(): Boolean {
        val target = scrubTargetMs
        scrubTargetMs = -1L
        scrubRepeats = 0
        if (target < 0L) return false
        val p = player ?: return true
        p.seekTo(target)
        // El card de "siguiente episodio" lo dispara `endMonitorJob` mirando la posición REAL, que
        // durante el mantenido todavía no se ha movido: podía salir —y seguir su cuenta atrás— justo
        // mientras el usuario rebobinaba lejos del final. Al alejarse se cancela y se permite que
        // vuelva a aparecer cuando de verdad toque.
        cancelNextIfAwayFromEnd()
        resetHideTimer()
        return true
    }

    /**
     * Si la posición ha quedado lejos del final, mata la cuenta atrás del siguiente episodio y deja
     * que la tarjeta pueda volver a salir cuando de verdad toque.
     *
     * El card lo dispara `endMonitorJob` mirando la posición REAL, así que puede aparecer mientras el
     * usuario se está alejando del final. ⚠️ Lo llaman **todos** los caminos de seek, no solo el
     * scrub del D-pad: con la tarjeta en pantalla `hideControls()` ya se ha llevado los botones de
     * ±10 s y IZQ/DER navegan entre "Ver ahora"/"Cancelar", así que las teclas físicas ⏪/⏩ son el
     * único seek que queda — y eran justo las que se habían quedado sin esta protección, de modo que
     * rebobinar el final acababa saltando al episodio siguiente igualmente.
     */
    private fun cancelNextIfAwayFromEnd() {
        val p = player ?: return
        val duration = p.duration
        if (duration <= 0L) return
        if (duration - p.currentPosition <= NEXT_CARD_REMAINING_MS) return
        cancelNextEpisode()
        nextCardHandled = false
    }

    // ── Resume-point persistence ───────────────────────────────────────────────

    private fun startProgressSaver() {
        progressSaver?.cancel()
        progressSaver = lifecycleScope.launch {
            while (isActive) {
                delay(PROGRESS_SAVE_INTERVAL_MS)
                val p = player ?: continue
                if (p.isPlaying) saveProgress(p.currentPosition, p.duration)
            }
        }
    }

    private fun stopProgressSaver() {
        progressSaver?.cancel()
        progressSaver = null
    }

    // ── End-of-episode: auto-mark watched + next-episode auto-play ───────────────

    private fun startEndMonitor() {
        endMonitorJob?.cancel()
        endMonitorJob = lifecycleScope.launch {
            while (isActive) {
                delay(END_MONITOR_INTERVAL_MS)
                val p = player ?: continue
                if (p.isPlaying) checkEndOfEpisode(p)
            }
        }
    }

    private fun stopEndMonitor() {
        endMonitorJob?.cancel()
        endMonitorJob = null
    }

    /** Near the end: auto-mark watched (~2 min left) and offer the next episode (~30 s left). */
    private fun checkEndOfEpisode(p: ExoPlayer) {
        val duration = p.duration
        if (duration <= 0L) return
        val position = p.currentPosition
        if (position < duration / 2) return   // only in the latter half (guards short clips / seeks)
        val remaining = duration - position

        if (remaining <= WATCHED_REMAINING_MS) autoMarkWatched()

        // Don't fight the server panel for the screen/focus; retry on the next tick once closed.
        if (!nextCardHandled && !isServerPanelOpen &&
            number < maxEpisode && remaining <= NEXT_CARD_REMAINING_MS
        ) {
            showNextEpisodeCard()
        }
    }

    /**
     * Al terminar el **último episodio disponible** se vuelve a la ficha de la serie.
     *
     * Sin esto el reproductor se quedaba en negro sobre el frame final, con el ✓ ya marcado y sin
     * nada más que hacer: la única salida era BACK. Cuando hay siguiente episodio no aplica — de eso
     * se encarga el card de auto-reproducción, que aparece 30 s antes de llegar aquí.
     *
     * ⚠️ Se navega a `SeriesActivity` en vez de un `finish()` pelado porque el reproductor no siempre
     * se abre desde la ficha: la fila *Continuar viendo* de Inicio lanza el player directamente, y
     * ahí un finish devolvería al Inicio. `CLEAR_TOP | SINGLE_TOP` cubre los dos casos con el mismo
     * intent: si la ficha ya está en la pila (se vino de ella) se trae al frente **sin recrearla**,
     * conservando su scroll y su bloque de episodios; si no está, se crea encima del Inicio.
     *
     * ⚠️ La guarda es `maxEpisode > 0`: cuando no llega en los extras se deriva de `totalEpisodes`,
     * que puede ser 0 (fila cacheada de `favorite_series`), y `maxEpisode` cae a 0 — con `number >= 0`
     * cerraría el reproductor al acabar CUALQUIER episodio. Sin dato fiable, no se cierra.
     */
    private fun returnToSeriesIfLastEpisode() {
        if (maxEpisode <= 0 || number < maxEpisode) return
        if (isFinishing || returningToSeries) return
        returningToSeries = true
        startActivity(Intent(this, SeriesActivity::class.java).apply {
            putExtra("slug", slug)
            putExtra("title", title)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    private fun setupNextEpisodeCard() {
        btnPlayNext.setOnClickListener { playNextNow() }
        btnCancelNext.setOnClickListener { cancelNextEpisode() }
    }

    private fun showNextEpisodeCard() {
        nextCardHandled = true     // show only once per episode (don't re-trigger after Cancelar)
        hideControls()
        nextEpTitle.text = "Episodio ${number + 1}"
        // El fotograma del episodio que viene, no la imagen de la serie: la tarjeta enseñaba el
        // mismo backdrop para los 1172 episodios de One Piece. Si el CDN no lo tiene responde 403,
        // y entonces se cae a la imagen de la serie, que es lo que había antes.
        val fallback = backdropUrl.ifBlank { coverUrl }
        val thumb = AnimeImages.episodeThumbFromCover(coverUrl, number + 1)
        when {
            thumb.isNotBlank() -> nextEpThumb.load(thumb) {
                crossfade(true)
                listener(onError = { _, _ ->
                    if (fallback.isNotBlank()) nextEpThumb.load(fallback) { crossfade(true) }
                })
            }
            fallback.isNotBlank() -> nextEpThumb.load(fallback) { crossfade(true) }
        }
        nextEpisodeCard.visibility = View.VISIBLE
        btnPlayNext.post { btnPlayNext.requestFocus() }
        startNextCountdown()
    }

    private fun startNextCountdown() {
        nextCountdownJob?.cancel()
        nextCountdownJob = lifecycleScope.launch {
            var remaining = NEXT_COUNTDOWN_SECS
            while (remaining > 0) {
                nextEpCountdown.text = getString(R.string.next_in, remaining)
                delay(1_000)
                // Pausing pauses the auto-advance: the media keys stay live while this card is
                // up, and jumping to the next episode on a deliberately paused video is the
                // opposite of what the user asked for.
                if (player?.playWhenReady == true) remaining--
            }
            goToNextEpisode()
        }
    }

    /** User dismissed auto-play (or the player was released): stop the countdown, hide the card. */
    private fun cancelNextEpisode() {
        nextCountdownJob?.cancel()
        nextCountdownJob = null
        nextEpisodeCard.visibility = View.GONE
    }

    private fun playNextNow() {
        nextCountdownJob?.cancel()
        nextCountdownJob = null
        goToNextEpisode()
    }

    private fun saveProgress(positionMs: Long, durationMs: Long) {
        // Snapshot everything the write needs so the coroutine doesn't outlive this Activity
        // holding a reference to it.
        val repo = local
        val s = slug
        val ep = number
        val t = title
        val cover = coverUrl
        val totalEps = totalEpisodes
        val year = seriesStartDate.take(4)
        val status = seriesStatus
        val category = seriesCategory
        AnimeApp.appScope.launch {
            repo.saveProgress(s, ep, positionMs, durationMs, t, cover, totalEps, year, status, category)
        }
    }

    private fun clearProgress() {
        val repo = local
        val s = slug
        val ep = number
        AnimeApp.appScope.launch { repo.clearProgress(s, ep) }
    }

    private fun showResumePrompt(savedMs: Long) {
        resumePromptPositionMs = savedMs
        resumeSubtitle.text = "Te quedaste en ${formatTime(savedMs)}"
        hideLoading()
        hideControls()
        resumeOverlay.visibility = View.VISIBLE
        btnResume.post { btnResume.requestFocus() }
    }

    private fun hideResumePrompt() {
        awaitingResumeChoice = false
        resumeOverlay.visibility = View.GONE
    }

    // ── Watched ────────────────────────────────────────────────────────────────

    /**
     * El ✓ manual marca este episodio **y los anteriores que sigan sin marcar** (rango
     * `minEpisode..N`), igual que el auto-marcado del final y que la ficha de la serie: quedarse en
     * el 7 con el 1..6 sin marcar deja la serie mintiendo en "vistos/total", en el badge NUEVO de Mi
     * Lista y en el "Continuar" de la ficha, que apuntaría al 1.
     *
     * ⚠️ **Desmarcar sigue quitando solo este episodio**, y eso es a propósito: marcar afirma algo
     * que ya ha pasado ("he llegado hasta aquí"), pero desmarcar en bloque BORRARÍA historial que el
     * usuario no ha pedido borrar. La consecuencia es que ✓ y ✓ otra vez no deja las cosas
     * exactamente como estaban si había huecos detrás.
     */
    private fun toggleWatched() {
        val newState = !isWatched
        isWatched = newState
        updateWatchedButton()
        if (newState) {
            markWatchedInDb()
        } else {
            val repo = local
            val s = slug
            val ep = number
            AnimeApp.appScope.launch { repo.unmarkWatched(s, ep) }
        }
    }

    /** Mark watched automatically when the episode finishes (only if not already watched). */
    private fun autoMarkWatched() {
        if (isWatched) return
        isWatched = true
        updateWatchedButton()
        markWatchedInDb()
    }

    /** Delegates to the repository, which marks the range, drops its resume points and moves
     *  the series to Viendo/Completadas in one transaction. Lo usan el auto-marcado del final y
     *  el ✓ manual. */
    private fun markWatchedInDb() {
        val repo = local
        val s = slug
        val ep = number
        val minEp = minEpisode
        val isLast = number == maxEpisode
        val t = title
        val cover = coverUrl
        val totalEps = totalEpisodes
        val year = seriesStartDate.take(4)
        val status = seriesStatus
        val category = seriesCategory
        AnimeApp.appScope.launch {
            repo.markWatchedThrough(s, ep, minEp, t, cover, totalEps, isLast, year, status, category)
        }
    }

    private fun updateWatchedButton() {
        btnWatched.setImageResource(
            if (isWatched) R.drawable.ic_check_circle else R.drawable.ic_check_circle_outline
        )
        btnWatched.contentDescription = if (isWatched) "Visto" else "Marcar como visto"
    }

    // ── Server panel ───────────────────────────────────────────────────────────

    private val isServerPanelOpen: Boolean get() = serverPanel.visibility == View.VISIBLE

    private fun openServerPanel() {
        if (embedList.isEmpty()) return
        hideControls()
        serverPanel.visibility = View.VISIBLE
        // Con las dos pistas en la lista el servidor en uso puede quedar fuera de pantalla
        // (los de DUB van debajo de todos los de SUB): déjalo a la vista antes de pedir foco.
        // Se hace scroll a la CABECERA de su grupo, no al servidor, para que el rótulo de la
        // pista quede visible encima en vez de justo por encima del borde.
        serverAdapter.groupStartOf(selectedEmbed).takeIf { it >= 0 }
            ?.let { serverLayout.scrollToPositionWithOffset(it, 0) }
        serverList.post {
            // ⚠️ Enfocar la fila EN USO explícitamente. Un `serverList.requestFocus()` pelado cae
            // en el primer hijo focusable adjunto, que ya no es el servidor seleccionado: el
            // scroll apunta a la cabecera (no focusable) y, para el último grupo (DOBLADO), el
            // LinearLayoutManager ni siquiera puede subirla del todo, así que el foco aterrizaba
            // en una tarjeta de SUBTITULADO. En un mando eso es grave: ARRIBA + CENTRO por
            // reflejo cambiaba de servidor Y de idioma sin pedirlo.
            val pos = serverAdapter.positionOf(selectedEmbed)
            val row = if (pos >= 0) serverList.findViewHolderForAdapterPosition(pos) else null
            // Si la fila aún no está adjunta, el fallback deja el foco en la lista igualmente.
            if (row?.itemView?.requestFocus() != true) serverList.requestFocus()
        }
    }

    private fun closeServerPanel() {
        if (!isServerPanelOpen) return
        serverPanel.visibility = View.GONE
    }

    // ── Loading / status overlay ────────────────────────────────────────────────

    private fun showLoading(msg: String) {
        errorActions.visibility = View.GONE
        bufferingSpinner.visibility = View.GONE   // the big overlay is the single loader now
        statusText.text = msg
        statusText.visibility = View.VISIBLE
        loadingBar.visibility = View.VISIBLE
        loadingOverlay.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        loadingOverlay.visibility = View.GONE
    }

    /**
     * Estado de error CON salida. El overlay no tenía ningún focusable, así que con un mando la
     * única tecla que hacía algo era ATRÁS y en ninguna parte se decía: parecía que la app se había
     * quedado colgada. Ahora hay tres salidas y el foco aterriza en "Reintentar".
     */
    private fun showError(msg: String) {
        bufferingSpinner.visibility = View.GONE
        statusText.text = "⚠ $msg"
        statusText.visibility = View.VISIBLE
        loadingBar.visibility = View.GONE
        loadingOverlay.visibility = View.VISIBLE
        // "Elegir fuente" solo tiene sentido si hay fuentes que elegir.
        btnErrorServers.visibility = if (embedList.isEmpty()) View.GONE else View.VISIBLE
        errorActions.visibility = View.VISIBLE
        btnErrorRetry.post { btnErrorRetry.requestFocus() }
    }

    /** Reintenta la misma fuente: el fallo más común es un CDN que no responde en ese momento. */
    private fun retryCurrentSource() {
        errorActions.visibility = View.GONE
        currentStreamUrl = null
        vm.clearStream()
        val embed = selectedEmbed ?: playingEmbed ?: embedList.firstOrNull()
        if (embed == null) {
            // El fallo fue al pedir la LISTA de servidores (falló la petición, o llegó vacía): no hay
            // ninguna fuente que reintentar, hay que volver a pedirla. Sin esto el botón salía por un
            // `return` y era un no-op: la única salida de la pantalla de error era BACK.
            // ⚠️ `clearEmbeds()` no es opcional: si la lista llegó VACÍA, repedirla vuelve a poner
            // `emptyList()` y el StateFlow conflata el valor igual, así que el colector no reemitiría
            // y `showLoading` habría borrado el mensaje y los botones para siempre.
            vm.clearEmbeds()
            vm.clearError()
            showLoading("Cargando…")
            vm.loadEmbeds(slug, number)
            return
        }
        // ⚠️ Reintentar a mano es "vuelve a intentarlo todo": se olvida lo ya probado para que el
        // fallback pueda recorrer la pista otra vez. Sin esto, tras agotar la cadena por un corte de
        // red pasajero, cada pulsación reintentaba SOLO la última fuente que falló —25 s de watchdog
        // por pulsación— y `nextUntriedSource` devolvía siempre null.
        triedEmbeds.clear()
        onServerSelected(embed)
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT > 23) initPlayer()
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT <= 23) initPlayer()
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT <= 23) releasePlayer()
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT > 23) releasePlayer()
    }

    // ── D-pad / remote: drive everything ourselves ─────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // El scrub necesita saber cuándo se SUELTA la tecla: es cuando se aplica el seek de verdad.
        if (event.action == KeyEvent.ACTION_UP &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
            if (commitScrub()) return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val code = event.keyCode

        // Media transport keys work in EVERY state (modals included) — a physical play/pause
        // button must never go dead depending on what overlay happens to be on screen.
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); afterMediaKey(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                player?.let { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0); it.playWhenReady = true }
                updatePlayPauseLabel(); afterMediaKey(); return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                player?.playWhenReady = false
                updatePlayPauseLabel(); afterMediaKey(); return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                player?.seekBack(); cancelNextIfAwayFromEnd(); afterMediaKey(); return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                player?.seekForward(); cancelNextIfAwayFromEnd(); afterMediaKey(); return true
            }
        }

        // Modals: let their views navigate; only intercept BACK.
        if (nextEpisodeCard.visibility == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK) { cancelNextEpisode(); return true }
            return super.dispatchKeyEvent(event)   // navigate Ver ahora / Cancelar
        }
        if (isServerPanelOpen) {
            if (code == KeyEvent.KEYCODE_BACK) {
                closeServerPanel()
                // A failed/aborted server switch can leave the loading overlay up while the old
                // stream keeps playing underneath — never leave it stuck on screen.
                if (player != null) hideLoading()
                showControls()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (resumeOverlay.visibility == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK) { finish(); return true }
            return super.dispatchKeyEvent(event)
        }

        // ⏭ / ⏮ del mando: lo mismo que los botones de episodio, que ya existían en pantalla. Estaban
        // muertas mientras ⏪/⏩ sí funcionaban, así que media botonera multimedia del mando no hacía
        // nada.
        //
        // ⚠️ Van AQUÍ y no en el bloque común de arriba: `goToEpisode` hace `finish()`, y con un modal
        // abierto (cuenta atrás del siguiente, panel de fuentes, "¿continuar viendo?") eso mataría la
        // Activity por debajo de la pregunta que el usuario está respondiendo. Los guards de rango de
        // `goToEpisode` hacen que en el primer/último episodio sea un no-op.
        when (code) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> { goToNextEpisode(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { goToEpisode(number - 1); return true }
        }

        // Pantalla de error: es un modal con botones focusables ("Reintentar" / "Elegir fuente" /
        // "Salir"). ⚠️ Sin esta rama las teclas del D-pad las consumía la rama de "controles
        // ocultos" de más abajo devolviendo true, y sus dos acciones (showControls / scrub) hacen
        // return inmediato con `player == null` — que es exactamente el estado del error: el mando
        // quedaba muerto y no se podía pulsar ni "Reintentar" ni "Salir". Va DESPUÉS de ⏭/⏮ a
        // propósito: cambiar de episodio es una salida legítima de un error.
        if (errorActions.visibility == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK) { finish(); return true }
            if (code == KeyEvent.KEYCODE_DPAD_UP && embedList.isNotEmpty()) {
                openServerPanel(); return true
            }
            return super.dispatchKeyEvent(event)
        }

        if (isControlsVisible) {
            if (code == KeyEvent.KEYCODE_BACK) { hideControls(); return true }
            // On the scrubber, LEFT/RIGHT seek ±10s and CENTER toggles play; other keys (UP/DOWN)
            // navigate normally to/among the buttons.
            if (controlsOverlay.findFocus() === ctrlTimebar) {
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT  -> { scrub(false); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { scrub(true);  return true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        togglePlay(); resetHideTimer(); return true
                    }
                }
            }
            resetHideTimer()
            return super.dispatchKeyEvent(event)   // navigate the buttons
        }

        // Controls hidden — any remote key reveals them.
        return when (code) {
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            KeyEvent.KEYCODE_DPAD_LEFT  -> { showControls(); scrub(false); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { showControls(); scrub(true);  true }
            // UP opens the server panel directly (documented gesture); falls back to the
            // controls if the server list isn't available yet.
            // No player check here: after a stream failure the controls can't be shown
            // (showControls() bails on a null player), so UP is the only way back to the
            // server list — requiring a player would leave the remote dead on the error screen.
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (embedList.isNotEmpty()) openServerPanel() else showControls()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MENU -> { showControls(); true }
            else -> super.dispatchKeyEvent(event)
        }
    }

    /** After a media key: keep the visible controls alive, or reveal them if nothing modal is up. */
    private fun afterMediaKey() {
        when {
            isControlsVisible -> resetHideTimer()
            isServerPanelOpen || resumeOverlay.visibility == View.VISIBLE ||
                nextEpisodeCard.visibility == View.VISIBLE -> Unit
            else -> showControls()
        }
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    companion object {
        private const val PROGRESS_SAVE_INTERVAL_MS = 5_000L
        private const val CONTROLS_TIMEOUT_MS = 5_000L
        private const val CONTROLS_UPDATE_MS = 500L
        private const val END_MONITOR_INTERVAL_MS = 1_000L
        private const val STALL_CHECK_INTERVAL_MS = 1_000L
        private const val STALL_TIMEOUT_MS = 25_000L              // buffering w/o progress = dead stream
        private const val WATCHED_REMAINING_MS = 2 * 60 * 1000L   // mark watched when ≤ 2 min remain
        private const val NEXT_CARD_REMAINING_MS = 30 * 1000L     // offer next episode when ≤ 30 s remain
        private const val NEXT_COUNTDOWN_SECS = 10                // auto-advance countdown (seconds)
    }
}
