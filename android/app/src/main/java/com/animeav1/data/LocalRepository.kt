package com.animeav1.data

import androidx.room.withTransaction
import com.animeav1.data.local.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Single owner of every local-state rule: watchlist membership, watched episodes and resume
 * points. Nothing outside this class should touch the DAOs — the promotion rules (mark watched
 * ⇒ move to Viendo/Completadas) and the resume-point policy used to live here *and* duplicated
 * inside the player, which is exactly how the two drifted apart.
 *
 * ## Perfiles
 *
 * Todo lo que hay aquí está acotado al perfil activo de [ProfileManager]. Las funciones `suspend`
 * resuelven el id **en el momento de la llamada** ([pid]); los `Flow` lo encadenan con
 * `flatMapLatest`, así que cambiar de perfil vuelve a suscribir la consulta y la UI se repuebla sin
 * reiniciar la app.
 *
 * ⚠️ El id NO se guarda en un campo de la clase: `LocalRepository` se construye una vez (en
 * `LocalViewModel`, el reproductor y la pantalla de copias) y sobrevive al cambio de perfil. Un
 * campo cacheado dejaría al reproductor escribiendo en el perfil anterior.
 */
class LocalRepository(private val db: AppDatabase) {

    private val profileDao = db.profileDao()
    private val favDao = db.favoriteSeriesDao()
    private val epDao = db.watchedEpisodeDao()
    private val progressDao = db.episodeProgressDao()
    private val prefsDao = db.seriesPrefsDao()

    /** Perfil al que pertenece esta operación. Se lee en cada llamada, nunca se cachea. */
    private val pid: Long get() = ProfileManager.requireActiveId()

    companion object {
        /** Nombre del perfil que se crea solo en una instalación limpia. La migración 7→8 usa el
         *  mismo texto para el perfil que adopta los datos que ya existían. */
        const val DEFAULT_PROFILE_NAME = "Principal"

        /** Nombre de respaldo cuando un backup trae filas de un perfil cuya ficha no viene en el
         *  fichero (por ejemplo un formato 2 de otro aparato). */
        const val IMPORTED_PROFILE_NAME = "Perfil importado"

        /** Identidad estable de un perfil. `java.util.UUID` no es de Android, así que esto también
         *  funciona en los tests JVM. */
        fun newUuid(): String = java.util.UUID.randomUUID().toString()

        /** Below this the user has barely started; there is nothing worth resuming. */
        const val RESUME_MIN_MS = 10_000L

        /** Past this fraction the episode counts as finished and keeps no resume point. */
        const val RESUME_END_FRACTION = 0.97

        /** True once playback is far enough that the episode counts as finished. */
        fun isFinished(positionMs: Long, durationMs: Long): Boolean =
            durationMs > 0L && positionMs >= durationMs * RESUME_END_FRACTION
    }

    // ── Watchlists ─────────────────────────────────────────────────────────────

    // ⚠️ `flatMapLatest` sobre `activeId`, no `favDao.getX(pid)` a secas: así cambiar de perfil
    // cancela la consulta anterior y suscribe la del nuevo. Con el id resuelto una sola vez, Inicio
    // y Mi Lista seguían mostrando las listas del perfil que estaba activo al crear el colector.
    @OptIn(ExperimentalCoroutinesApi::class)
    fun getFavorites(): Flow<List<FavoriteSeries>> =
        ProfileManager.activeId.flatMapLatest { favDao.getAll(pid) }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getByList(listType: ListType): Flow<List<FavoriteSeries>> =
        ProfileManager.activeId.flatMapLatest { favDao.getByList(pid, listType.key) }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getFavoritesList(): Flow<List<FavoriteSeries>> =
        ProfileManager.activeId.flatMapLatest { favDao.getFavoritesList(pid) }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getContinueWatching(): Flow<List<ContinueItem>> =
        ProfileManager.activeId.flatMapLatest { favDao.getContinueWatching(pid) }

    /** Add/move series to a watchlist preserving the isFavorite flag. */
    suspend fun addToList(
        slug: String, title: String, coverUrl: String, listType: ListType,
        totalEpisodes: Int = 0, year: String = "", status: Int = -1, category: String = ""
    ) = db.withTransaction {
        favDao.insertIfAbsent(FavoriteSeries(pid, slug, title, coverUrl, listType.key, totalEpisodes, year = year, status = status, category = category))
        favDao.setListType(pid, slug, listType.key)
        if (hasMeta(year, status, category)) favDao.setMeta(pid, slug, year, status, category)
    }

    /** Toggle isFavorite. If series is not in any list, creates a favorites-only entry (listType="none"). */
    suspend fun setFavorite(
        slug: String, title: String, coverUrl: String, totalEpisodes: Int, isFavorite: Boolean,
        year: String = "", status: Int = -1, category: String = ""
    ) {
        val current = favDao.getBySlug(pid, slug)
        if (current != null) {
            favDao.setFavorite(pid, slug, isFavorite)
            if (hasMeta(year, status, category)) favDao.setMeta(pid, slug, year, status, category)
            // Un-favouriting a series that was in no list used to delete its row outright. But
            // the player also creates listType="none" rows purely so Home's "Continuar viendo"
            // JOIN has a title and cover: deleting one that still holds a resume point makes
            // that card disappear even though the user never finished the episode.
            if (!isFavorite && current.listType == "none" && progressDao.countForSeries(pid, slug) == 0) {
                removeSeriesRow(slug)
            }
        } else if (isFavorite) {
            favDao.add(FavoriteSeries(pid, slug, title, coverUrl, "none", totalEpisodes, isFavorite = true, year = year, status = status, category = category))
        }
    }

    /**
     * Backfill year/status/category and refresh the available-episode count on an existing
     * watchlist row (no-op if not listed). Refreshing totalEpisodes keeps the "vistos/total"
     * badge correct as airing series get new episodes.
     */
    suspend fun updateMeta(slug: String, year: String, status: Int, category: String, totalEpisodes: Int = 0) {
        if (hasMeta(year, status, category)) favDao.setMeta(pid, slug, year, status, category)
        if (totalEpisodes > 0) favDao.setTotalEpisodes(pid, slug, totalEpisodes)
    }

    private fun hasMeta(year: String, status: Int, category: String) =
        year.isNotBlank() || status >= 0 || category.isNotBlank()

    /** Refresh the available-episode count for a listed series (no-op if not listed). */
    suspend fun setTotalEpisodes(slug: String, total: Int) = favDao.setTotalEpisodes(pid, slug, total)

    suspend fun removeFromList(slug: String) {
        val current = favDao.getBySlug(pid, slug)
        if (current?.isFavorite == true) {
            favDao.setListType(pid, slug, "none")   // still a favourite: keep the row
        } else {
            removeSeriesRow(slug)
        }
    }

    /**
     * Drop the series row and its resume points together. Without the second half the progress
     * rows outlive the series for good and resurface as a stale "¿Continuar viendo?" the moment
     * it is re-added. Watched history is deliberately kept: leaving a list is not a request to
     * erase what you have already seen.
     */
    private suspend fun removeSeriesRow(slug: String) = db.withTransaction {
        favDao.remove(pid, slug)
        progressDao.clearSeries(pid, slug)
    }

    /**
     * Quita una serie de la fila *Continuar viendo* de Inicio, borrando sus puntos de reanudación:
     * la próxima vez, sus episodios empiezan desde el principio.
     *
     * ⚠️ Borra el progreso de la serie ENTERA, no solo el del episodio que muestra la tarjeta. La
     * fila enseña un episodio por serie (el más reciente sin terminar), así que quitando solo ese
     * la serie reaparecería al instante con el anterior: el usuario habría pedido quitarla y la
     * vería volver.
     *
     * ⚠️ Si la fila de `favorite_series` existía SOLO para dar título y portada a esta tarjeta
     * —`listType = "none"` y sin favorito, que es como la crea `saveProgress` para que el JOIN de
     * `getContinueWatching` tenga qué mostrar— se va con ella: sin progreso ya no le queda ningún
     * motivo para estar. Es la misma regla que aplica `setFavorite` por el otro lado.
     *
     * Lo ya visto NO se toca: quitar de "continuar viendo" no es pedir que se borre el historial.
     */
    suspend fun removeFromContinue(slug: String) = db.withTransaction {
        progressDao.clearSeries(pid, slug)
        val row = favDao.getBySlug(pid, slug)
        if (row != null && row.listType == "none" && !row.isFavorite) favDao.remove(pid, slug)
    }

    // ── Watched episodes ───────────────────────────────────────────────────────

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getWatchedNumbers(slug: String): Flow<List<Int>> =
        ProfileManager.activeId.flatMapLatest { epDao.getWatchedNumbers(pid, slug) }
    suspend fun isWatched(slug: String, ep: Int) = epDao.isWatched(pid, slug, ep) > 0
    suspend fun unmarkWatched(slug: String, ep: Int) = epDao.unmarkWatched(pid, slug, ep)

    /**
     * Mark or unmark a single episode. Marking drops its resume point (the two always move
     * together) and runs the SAME promotion rules as finishing it in the player — the series
     * detail used to have its own, weaker copy that never promoted to Completadas and created
     * the row with totalEpisodes = 0.
     */
    suspend fun setWatched(
        slug: String, ep: Int, watched: Boolean,
        title: String = "", coverUrl: String = "", totalEpisodes: Int = 0,
        isLastEpisode: Boolean = false, year: String = "", status: Int = -1, category: String = ""
    ) = db.withTransaction {
        if (!watched) {
            epDao.unmarkWatched(pid, slug, ep)
        } else {
            epDao.markWatched(WatchedEpisode(pid, slug, ep))
            progressDao.clear(pid, slug, ep)
            applyPromotion(slug, title, coverUrl, totalEpisodes, isLastEpisode, year, status, category)
        }
    }

    /**
     * Mark [ep] and every earlier episode watched, drop their resume points and promote the
     * series to Viendo — or to Completadas when it is the last episode of a finished series.
     *
     * One transaction: these steps are a single user-visible action, and a half-applied version
     * leaves a series marked watched but still sitting in "Por Ver".
     */
    suspend fun markWatchedThrough(
        slug: String, ep: Int, minEpisode: Int,
        title: String, coverUrl: String, totalEpisodes: Int,
        isLastEpisode: Boolean, year: String = "", status: Int = -1, category: String = ""
    ) = db.withTransaction {
        // Unconditionally first: minEpisode can arrive higher than ep (Inicio hardcodes 1 while
        // a series numbered from 0 exists), and minEpisode..ep would then be an empty range that
        // silently marks nothing at all.
        epDao.markWatched(WatchedEpisode(pid, slug, ep))
        progressDao.clear(pid, slug, ep)
        epDao.markWatchedBatch((minEpisode..ep).map { WatchedEpisode(pid, slug, it) })
        progressDao.clearRange(pid, slug, minEpisode, ep)

        applyPromotion(slug, title, coverUrl, totalEpisodes, isLastEpisode, year, status, category)
    }

    /** Viendo when the series isn't tracked yet, Completadas on the last episode of a finished one. */
    private suspend fun applyPromotion(
        slug: String, title: String, coverUrl: String, totalEpisodes: Int,
        isLastEpisode: Boolean, year: String, status: Int, category: String
    ) {
        if (title.isBlank()) return
        val current = favDao.getListType(pid, slug)
        if (current == null || current == ListType.POR_VER.key || current == "none") {
            promoteTo(ListType.VIENDO, slug, title, coverUrl, totalEpisodes, year, status, category)
        }
        if (isLastEpisode && status == 0) {
            promoteTo(ListType.COMPLETADAS, slug, title, coverUrl, totalEpisodes, year, status, category)
        }
        if (hasMeta(year, status, category)) favDao.setMeta(pid, slug, year, status, category)
    }

    private suspend fun promoteTo(
        listType: ListType, slug: String, title: String, coverUrl: String,
        totalEpisodes: Int, year: String, status: Int, category: String
    ) {
        favDao.insertIfAbsent(
            FavoriteSeries(pid, slug, title, coverUrl, listType.key, totalEpisodes, year = year, status = status, category = category)
        )
        favDao.setListType(pid, slug, listType.key)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getRecentHistory(): Flow<List<WatchedEpisode>> =
        ProfileManager.activeId.flatMapLatest { epDao.getRecentHistory(pid) }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getWatchedCountsPerSeries(): Flow<List<WatchedCount>> =
        ProfileManager.activeId.flatMapLatest { epDao.getWatchedCountsPerSeries(pid) }

    // ── Resume points ──────────────────────────────────────────────────────────

    suspend fun progressFor(slug: String, ep: Int): EpisodeProgress? = progressDao.get(pid, slug, ep)

    suspend fun clearProgress(slug: String, ep: Int) = progressDao.clear(pid, slug, ep)

    /**
     * Store — or drop — the resume point for an episode. Also makes sure the series has a row
     * in favorite_series, because Home's "Continuar viendo" INNER JOINs it for title and cover;
     * listType "none" keeps it out of every Mi Lista sub-list.
     */
    suspend fun saveProgress(
        slug: String, ep: Int, positionMs: Long, durationMs: Long,
        title: String, coverUrl: String, totalEpisodes: Int,
        year: String = "", status: Int = -1, category: String = ""
    ) {
        if (durationMs <= 0L) return
        // The end-monitor marks an episode watched ~2 min before it ends and wipes its resume
        // point, but playback continues — without the isWatched guard the 5 s saver would
        // recreate the row seconds later and leave it behind as a zombie.
        if (positionMs < RESUME_MIN_MS || isFinished(positionMs, durationMs) || isWatched(slug, ep)) {
            progressDao.clear(pid, slug, ep)
            return
        }
        progressDao.upsert(EpisodeProgress(pid, slug, ep, positionMs, durationMs))
        if (title.isNotBlank()) {
            favDao.insertIfAbsent(
                FavoriteSeries(pid, slug, title, coverUrl, "none", totalEpisodes, year = year, status = status, category = category)
            )
        }
    }

    // ── Preferencia de pista / fuente por serie ────────────────────────────────────────────────

    /** Lo que el usuario eligió la última vez para ESTA serie, o null si nunca eligió nada. */
    suspend fun prefsFor(slug: String): SeriesPrefs? = prefsDao.get(pid, slug)

    /**
     * Recuerda pista y servidor de esta serie. Se llama al elegir fuente en el reproductor, así que
     * la elección sobrevive a salir y volver a entrar — desde la ficha, desde Inicio o desde donde sea.
     */
    suspend fun rememberPrefs(slug: String, audio: String, server: String) =
        prefsDao.upsert(SeriesPrefs(pid, slug, audio, server))

    /** Puntos de reanudación de una serie, para pintar "a medias" en la rejilla de episodios. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun progressForSeries(slug: String): Flow<List<EpisodeProgress>> =
        ProfileManager.activeId.flatMapLatest { progressDao.getForSeries(pid, slug) }

    // ── Copia de seguridad ─────────────────────────────────────────────────────────────────────

    /**
     * Lee las tres tablas dentro de UNA transacción, para que el fichero no pueda salir con una
     * serie ya presente y su progreso todavía no (el reproductor escribe en segundo plano por
     * `AnimeApp.appScope` mientras esto corre).
     */
    suspend fun exportAll(nowMs: Long): BackupData = db.withTransaction {
        // TODOS los perfiles, no solo el activo: si la copia se dejara los demás fuera, el usuario
        // perdería los perfiles de casa justo cuando cree estar a salvo.
        val profiles = profileDao.getAllOnce()
        // El fichero referencia perfiles por **uuid**, no por el id local, que es un ordinal sin
        // significado fuera de este aparato. Un perfil sin uuid no debería existir (la migración 8→9
        // los rellenó y `createProfile` los genera), pero si apareciera se le da uno aquí para no
        // escribir una copia con filas que después nadie sabría a quién asignar.
        val uuidById = profiles.associate { it.id to it.uuid.ifBlank { newUuid() } }
        fun ref(profileId: Long): ProfileRef =
            uuidById[profileId]?.let { ProfileRef.Uuid(it) } ?: ProfileRef.Active

        BackupData(
            exportedAt = nowMs,
            profiles = profiles.map {
                BackupProfile(ProfileRef.Uuid(uuidById.getValue(it.id)), it.name, it.colorIndex, it.createdAt)
            },
            series = favDao.getAllOnce().map {
                BackupSeries(
                    ref(it.profileId), it.slug, it.title, it.coverUrl, it.listType,
                    it.totalEpisodes, it.isFavorite, it.addedAt, it.year, it.status, it.category
                )
            },
            watched = epDao.getAllOnce().map {
                BackupWatched(ref(it.profileId), it.animeSlug, it.episodeNumber, it.watchedAt)
            },
            progress = progressDao.getAllOnce().map {
                BackupProgress(
                    ref(it.profileId), it.animeSlug, it.episodeNumber,
                    it.positionMs, it.durationMs, it.updatedAt
                )
            },
            prefs = prefsDao.getAllOnce().map {
                BackupSeriesPrefs(ref(it.profileId), it.slug, it.audio, it.server)
            }
        )
    }

    /**
     * Escribe un backup en la base de datos, todo o nada.
     *
     * @param replace `true` = sustituir (borra lo que hay y deja EXACTAMENTE lo del fichero);
     *                `false` = fusionar (mantiene lo local y sobrescribe solo las claves que
     *                coincidan). Fusionar es lo que se ofrece por defecto en la UI: importar no
     *                debería poder borrar de un botonazo lo que el usuario ha visto desde entonces.
     *
     * ⚠️ NO pasa por `applyPromotion`: un backup es un estado ya decidido, y volver a aplicar las
     * reglas de promoción movería series de lista al restaurarlas.
     */
    suspend fun importAll(data: BackupData, replace: Boolean) = db.withTransaction {
        if (replace) {
            // Orden irrelevante (no hay FOREIGN KEY), pero se borra todo antes de insertar para
            // que un `replace` no deje huérfanos de una serie que el fichero ya no tiene.
            prefsDao.deleteAll()
            progressDao.deleteAll()
            epDao.deleteAll()
            favDao.deleteAll()
        }
        // Los perfiles PRIMERO: si el fichero trae filas de un perfil que aquí no existe, sin su
        // fila en `profiles` quedarían invisibles (ninguna consulta las pediría) aunque estén en
        // disco. Nunca se borran los perfiles existentes, ni con `replace`: borrar el perfil activo
        // dejaría la app apuntando a uno que ya no está.
        val resolver = ProfileResolver(data)
        for (p in data.profiles) resolver.localIdFor(p.ref)

        favDao.upsertAll(data.series.map {
            FavoriteSeries(
                resolver.localIdFor(it.profile), it.slug, it.title, it.coverUrl, it.listType,
                it.totalEpisodes, it.isFavorite, it.addedAt, it.year, it.status, it.category
            )
        })
        epDao.upsertAll(data.watched.map {
            WatchedEpisode(resolver.localIdFor(it.profile), it.slug, it.episode, it.watchedAt)
        })
        progressDao.upsertAll(data.progress.map {
            EpisodeProgress(
                resolver.localIdFor(it.profile), it.slug, it.episode,
                it.positionMs, it.durationMs, it.updatedAt
            )
        })
        prefsDao.upsertAll(data.prefs.map {
            SeriesPrefs(resolver.localIdFor(it.profile), it.slug, it.audio, it.server)
        })
    }

    /**
     * Traduce las referencias de perfil de un backup a **ids locales**, creando los perfiles que
     * hagan falta. Es el corazón de que una copia sea portable entre aparatos.
     *
     * Una instancia por importación: cachea lo ya resuelto para no repetir consultas por cada fila
     * y, sobre todo, para que dos filas del mismo perfil nuevo no creen dos perfiles.
     */
    private inner class ProfileResolver(private val data: BackupData) {
        private val cache = mutableMapOf<ProfileRef, Long>()

        suspend fun localIdFor(ref: ProfileRef): Long = cache.getOrPut(ref) { resolve(ref) }

        private suspend fun resolve(ref: ProfileRef): Long = when (ref) {
            // El fichero no decía de quién era (formato 1): lo importa quien lo está pidiendo.
            ProfileRef.Active -> ProfileManager.requireActiveId()

            // Formato 2: el id local del aparato que escribió el fichero. Si ese id existe aquí se
            // usa —que es el caso real, restaurar tu propia copia—; si no, se busca por NOMBRE antes
            // de crear nada.
            //
            // ⚠️ El paso por nombre es lo que hace que importar dos veces el mismo fichero no
            // duplique: el perfil que crea `createFromBackup` recibe un id nuevo de AUTOINCREMENT,
            // nunca el que traía el fichero, así que el segundo import volvía a fallar el `getById`
            // y creaba otro perfil —y otro juego completo de listas, vistos y progreso— cada vez.
            // Casar por nombre puede fusionar dos personas homónimas, pero el formato 2 ya es
            // inherentemente no portable (ver "Perfiles" en CLAUDE.md) y duplicar sin límite es peor.
            is ProfileRef.LegacyId ->
                profileDao.getById(ref.id)?.id
                    ?: nameOf(ref)?.let { profileDao.getByName(it)?.id }
                    ?: createFromBackup(ref)

            // Formato 3: identidad estable. Existe aquí → es el mismo perfil; no existe → es una
            // persona que este aparato no conocía, y merece su propio perfil.
            is ProfileRef.Uuid ->
                profileDao.getByUuid(ref.uuid)?.id ?: createFromBackup(ref)
        }

        /** Crea el perfil que el fichero describe, conservando su uuid para que un segundo import
         *  del mismo backup lo reconozca en vez de duplicarlo. */
        /** Nombre que el fichero le da a este perfil, si lo trae. */
        private fun nameOf(ref: ProfileRef): String? =
            data.profiles.firstOrNull { it.ref == ref }?.name?.takeIf { it.isNotBlank() }

        private suspend fun createFromBackup(ref: ProfileRef): Long {
            val fromFile = data.profiles.firstOrNull { it.ref == ref }
            val uuid = (ref as? ProfileRef.Uuid)?.uuid ?: newUuid()
            return profileDao.insert(
                Profile(
                    uuid = uuid,
                    name = fromFile?.name ?: IMPORTED_PROFILE_NAME,
                    colorIndex = fromFile?.colorIndex ?: 0,
                    createdAt = fromFile?.createdAt?.takeIf { it > 0L } ?: System.currentTimeMillis()
                )
            )
        }
    }

    // ── Perfiles ───────────────────────────────────────────────────────────────────────────────

    fun getProfiles(): Flow<List<Profile>> = profileDao.getAll()
    suspend fun getProfilesOnce(): List<Profile> = profileDao.getAllOnce()
    suspend fun getProfile(id: Long): Profile? = profileDao.getById(id)
    suspend fun profileCount(): Int = profileDao.count()

    /**
     * Crea un perfil y devuelve su fila ya con el id asignado (hace falta para activarlo).
     *
     * Un perfil nuevo arranca **vacío** a propósito: no hereda nada del activo. Es lo que se espera
     * de "otro perfil", y copiar el historial de otra persona sería justo lo contrario.
     */
    suspend fun createProfile(name: String, colorIndex: Int): Profile {
        val profile = Profile(uuid = newUuid(), name = name, colorIndex = colorIndex)
        return profile.copy(id = profileDao.insert(profile))
    }

    suspend fun renameProfile(id: Long, name: String, colorIndex: Int) =
        profileDao.rename(id, name, colorIndex)

    /**
     * Borra un perfil **y todo lo suyo**, en una sola transacción.
     *
     * ⚠️ No hay `@ForeignKey`/CASCADE en este esquema, así que las tres tablas se limpian a mano.
     * Sin esto, las filas del perfil borrado sobreviven para siempre y reaparecen si el id se
     * reutiliza (AUTOINCREMENT no los reutiliza, pero un import de un backup viejo sí puede
     * reintroducir ese id).
     *
     * @return false si es el último perfil: quedarse sin ninguno dejaría la app sin dónde escribir.
     */
    suspend fun deleteProfile(id: Long): Boolean = db.withTransaction {
        if (profileDao.count() <= 1) return@withTransaction false
        prefsDao.deleteProfile(id)
        progressDao.deleteProfile(id)
        epDao.deleteProfile(id)
        favDao.deleteProfile(id)
        profileDao.delete(id)
        true
    }

    /**
     * Se llama al arrancar. Garantiza que existe al menos un perfil y que el activo apunta a uno
     * real: una instalación nueva no pasa por la migración 7→8, así que nadie habría creado el
     * perfil por defecto, y todas las escrituras irían a un `profileId` fantasma.
     *
     * @return el perfil activo resultante.
     */
    suspend fun ensureActiveProfile(): Profile = db.withTransaction {
        val existing = profileDao.getAllOnce()
        if (existing.isEmpty()) {
            // Instalación limpia: se crea con el id por defecto para que coincida con lo que
            // `ProfileManager.requireActiveId()` devuelve antes de que se elija nada.
            val first = Profile(Profile.DEFAULT_ID, newUuid(), DEFAULT_PROFILE_NAME, 0)
            profileDao.upsertAll(listOf(first))
            return@withTransaction first
        }
        // El id guardado puede apuntar a un perfil ya borrado (o borrado en otro aparato y traído
        // por un import): se cae al primero en vez de dejar la app escribiendo en la nada.
        existing.firstOrNull { it.id == ProfileManager.requireActiveId() } ?: existing.first()
    }
}
