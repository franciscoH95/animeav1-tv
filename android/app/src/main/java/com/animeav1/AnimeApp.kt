package com.animeav1

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.animeav1.ui.player.Av1Support
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.animeav1.data.AnimeRepository
import com.animeav1.data.ProfileManager
import com.animeav1.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

class AnimeApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        AnimeRepository.init(this)
        // Antes de AppDatabase: en cuanto alguien consulte, LocalRepository preguntará por el
        // perfil activo, y sin init() el `lateinit` de las preferencias reventaría.
        ProfileManager.init(this)
        AppDatabase.get(this)
        // En segundo plano y una sola vez: si este aparato hace AV1 Main10 por hardware, MP4Upload
        // (1080p AV1) va primero; si no, Voe (720p H.264). Ver Av1Support / PlaybackPolicy.rank.
        probeAv1()
    }

    @OptIn(UnstableApi::class)
    private fun probeAv1() = Av1Support.probeAsync(this)

    /**
     * App-wide Coil loader. RGB_565 halves bitmap memory for the opaque posters/backdrops
     * (no alpha needed) — meaningful on low-RAM TV boxes. Rounded-corner loads keep ARGB_8888
     * automatically since the transformation needs transparency.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .allowRgb565(true)
            .crossfade(true)
            .build()

    companion object {
        /**
         * App-lifetime scope for fire-and-forget DB writes that must outlive an Activity.
         * Single-threaded so writes to the same row run in submission order (no reorder races
         * between e.g. a periodic save and a clear-on-finish for the same episode).
         */
        val appScope = CoroutineScope(
            SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        )
    }
}
