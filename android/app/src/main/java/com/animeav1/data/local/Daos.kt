package com.animeav1.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Projection used to get how many episodes are watched per series. */
data class WatchedCount(val animeSlug: String, val watchedCount: Int)

/** Projection for the "Continuar viendo" row: the most recent in-progress episode per series. */
data class ContinueItem(
    val slug: String,
    val title: String,
    val coverUrl: String,
    val episodeNumber: Int,
    val positionMs: Long,
    val durationMs: Long,
    val totalEpisodes: Int,
    val status: Int,
    val year: String,
    val category: String,
    val updatedAt: Long
)

/**
 * ⚠️ **Toda consulta de estado local lleva `profileId`.** No es opcional ni por comodidad: una
 * consulta sin filtrar mezcla las listas de dos personas, y el fallo se ve como "en mi lista hay
 * series que yo no puse". Si añades un método aquí, el `profileId` va en el WHERE (y en la clave
 * primaria de la entidad si inserta).
 */
@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY createdAt ASC")
    fun getAll(): Flow<List<Profile>>

    @Query("SELECT * FROM profiles ORDER BY createdAt ASC")
    suspend fun getAllOnce(): List<Profile>

    @Query("SELECT * FROM profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Profile?

    /** Por identidad estable. Es lo que usa el import para saber si el perfil ya está en este
     *  aparato, en vez de fiarse del `id`, que es un ordinal local. */
    @Query("SELECT * FROM profiles WHERE uuid = :uuid LIMIT 1")
    suspend fun getByUuid(uuid: String): Profile?

    /**
     * Por nombre. Solo lo usa el import de un backup del **FORMATO 2**, que no traía uuid: es la
     * única forma de reconocer un perfil ya importado de ese formato y no duplicarlo.
     */
    @Query("SELECT * FROM profiles WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): Profile?

    @Query("SELECT COUNT(*) FROM profiles")
    suspend fun count(): Int

    /** @return el id asignado, para poder activar el perfil recién creado. */
    @Insert
    suspend fun insert(profile: Profile): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(profiles: List<Profile>)

    @Query("UPDATE profiles SET name = :name, colorIndex = :colorIndex WHERE id = :id")
    suspend fun rename(id: Long, name: String, colorIndex: Int)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SeriesPrefsDao {
    @Query("SELECT * FROM series_prefs WHERE profileId = :profileId AND slug = :slug LIMIT 1")
    suspend fun get(profileId: Long, slug: String): SeriesPrefs?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(prefs: SeriesPrefs)

    @Query("DELETE FROM series_prefs WHERE profileId = :profileId")
    suspend fun deleteProfile(profileId: Long)

    // ── Copia de seguridad ────────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM series_prefs")
    suspend fun getAllOnce(): List<SeriesPrefs>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<SeriesPrefs>)

    @Query("DELETE FROM series_prefs")
    suspend fun deleteAll()
}

@Dao
interface FavoriteSeriesDao {
    @Query("SELECT * FROM favorite_series WHERE profileId = :profileId ORDER BY addedAt DESC")
    fun getAll(profileId: Long): Flow<List<FavoriteSeries>>

    @Query("SELECT * FROM favorite_series WHERE profileId = :profileId AND listType = :listType ORDER BY addedAt DESC")
    fun getByList(profileId: Long, listType: String): Flow<List<FavoriteSeries>>

    @Query("SELECT * FROM favorite_series WHERE profileId = :profileId AND isFavorite = 1 ORDER BY addedAt DESC")
    fun getFavoritesList(profileId: Long): Flow<List<FavoriteSeries>>

    @Query("SELECT * FROM favorite_series WHERE profileId = :profileId AND slug = :slug LIMIT 1")
    suspend fun getBySlug(profileId: Long, slug: String): FavoriteSeries?

    @Query("SELECT listType FROM favorite_series WHERE profileId = :profileId AND slug = :slug LIMIT 1")
    suspend fun getListType(profileId: Long, slug: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(entry: FavoriteSeries)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entry: FavoriteSeries)

    @Query("UPDATE favorite_series SET listType = :listType WHERE profileId = :profileId AND slug = :slug")
    suspend fun setListType(profileId: Long, slug: String, listType: String)

    @Query("UPDATE favorite_series SET year = :year, status = :status, category = :category WHERE profileId = :profileId AND slug = :slug")
    suspend fun setMeta(profileId: Long, slug: String, year: String, status: Int, category: String)

    /** Refresh the available-episode count for an already-listed series (no-op if not listed). */
    @Query("UPDATE favorite_series SET totalEpisodes = :total WHERE profileId = :profileId AND slug = :slug")
    suspend fun setTotalEpisodes(profileId: Long, slug: String, total: Int)

    @Query("UPDATE favorite_series SET isFavorite = :isFavorite WHERE profileId = :profileId AND slug = :slug")
    suspend fun setFavorite(profileId: Long, slug: String, isFavorite: Boolean)

    @Query("DELETE FROM favorite_series WHERE profileId = :profileId AND slug = :slug")
    suspend fun remove(profileId: Long, slug: String)

    /**
     * "Continuar viendo": the most recent in-progress episode per series (episode_progress only
     * holds unfinished episodes), joined with the listed series for title/cover/meta.
     * Episodes already marked watched are excluded — resuming them is never useful.
     *
     * ⚠️ El `profileId` va en las TRES partes (progreso, JOIN de la serie y el NOT EXISTS de
     * vistos): con el JOIN sin filtrar, el progreso de un perfil se emparejaría con la ficha de
     * otro, y con el NOT EXISTS sin filtrar, lo que uno ha visto ocultaría el "continuar" del otro.
     */
    @Query(
        """
        SELECT f.slug AS slug, f.title AS title, f.coverUrl AS coverUrl,
               p.episodeNumber AS episodeNumber, p.positionMs AS positionMs, p.durationMs AS durationMs,
               f.totalEpisodes AS totalEpisodes, f.status AS status, f.year AS year, f.category AS category,
               MAX(p.updatedAt) AS updatedAt
        FROM episode_progress p
        INNER JOIN favorite_series f ON f.slug = p.animeSlug AND f.profileId = p.profileId
        WHERE p.profileId = :profileId
          AND NOT EXISTS (
            SELECT 1 FROM watched_episodes w
            WHERE w.profileId = p.profileId AND w.animeSlug = p.animeSlug
              AND w.episodeNumber = p.episodeNumber
        )
        GROUP BY p.animeSlug
        ORDER BY updatedAt DESC
        LIMIT 20
        """
    )
    fun getContinueWatching(profileId: Long): Flow<List<ContinueItem>>

    // ── Copia de seguridad ────────────────────────────────────────────────────────────────────

    /** TODOS los perfiles: una copia de seguridad que dejara perfiles fuera no sería una copia. */
    @Query("SELECT * FROM favorite_series")
    suspend fun getAllOnce(): List<FavoriteSeries>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<FavoriteSeries>)

    @Query("DELETE FROM favorite_series")
    suspend fun deleteAll()

    /** Al borrar un perfil. Solo lo suyo: los demás perfiles no se tocan. */
    @Query("DELETE FROM favorite_series WHERE profileId = :profileId")
    suspend fun deleteProfile(profileId: Long)
}

@Dao
interface WatchedEpisodeDao {
    @Query("SELECT episodeNumber FROM watched_episodes WHERE profileId = :profileId AND animeSlug = :slug")
    fun getWatchedNumbers(profileId: Long, slug: String): Flow<List<Int>>

    @Query("SELECT COUNT(*) FROM watched_episodes WHERE profileId = :profileId AND animeSlug = :slug AND episodeNumber = :ep")
    suspend fun isWatched(profileId: Long, slug: String, ep: Int): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markWatched(entry: WatchedEpisode)

    /**
     * IGNORE, not REPLACE: this is called with the whole minEpisode..current range on every
     * episode, so replacing would re-stamp watchedAt on episodes watched months ago. Nothing
     * reads watchedAt today, but keeping the original timestamps costs nothing and is the only
     * record of when each episode was actually seen.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markWatchedBatch(entries: List<WatchedEpisode>)

    @Query("DELETE FROM watched_episodes WHERE profileId = :profileId AND animeSlug = :slug AND episodeNumber = :ep")
    suspend fun unmarkWatched(profileId: Long, slug: String, ep: Int)

    @Query("SELECT * FROM watched_episodes WHERE profileId = :profileId ORDER BY watchedAt DESC LIMIT 50")
    fun getRecentHistory(profileId: Long): Flow<List<WatchedEpisode>>

    @Query("SELECT animeSlug, COUNT(*) as watchedCount FROM watched_episodes WHERE profileId = :profileId GROUP BY animeSlug")
    fun getWatchedCountsPerSeries(profileId: Long): Flow<List<WatchedCount>>

    // ── Copia de seguridad ────────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM watched_episodes")
    suspend fun getAllOnce(): List<WatchedEpisode>

    /** REPLACE, al contrario que [markWatchedBatch]: al importar manda el fichero, incluido su
     *  `watchedAt`, que es justamente el dato que hay que restaurar. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<WatchedEpisode>)

    @Query("DELETE FROM watched_episodes")
    suspend fun deleteAll()

    @Query("DELETE FROM watched_episodes WHERE profileId = :profileId")
    suspend fun deleteProfile(profileId: Long)
}

@Dao
interface EpisodeProgressDao {
    @Query("SELECT * FROM episode_progress WHERE profileId = :profileId AND animeSlug = :slug AND episodeNumber = :ep LIMIT 1")
    suspend fun get(profileId: Long, slug: String, ep: Int): EpisodeProgress?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: EpisodeProgress)

    @Query("DELETE FROM episode_progress WHERE profileId = :profileId AND animeSlug = :slug AND episodeNumber = :ep")
    suspend fun clear(profileId: Long, slug: String, ep: Int)

    /** One statement for the whole watched range, instead of a DELETE per episode. */
    @Query("DELETE FROM episode_progress WHERE profileId = :profileId AND animeSlug = :slug AND episodeNumber BETWEEN :from AND :to")
    suspend fun clearRange(profileId: Long, slug: String, from: Int, to: Int)

    /** Used when a series row is deleted: resume points must not outlive it as orphans. */
    @Query("DELETE FROM episode_progress WHERE profileId = :profileId AND animeSlug = :slug")
    suspend fun clearSeries(profileId: Long, slug: String)

    @Query("SELECT COUNT(*) FROM episode_progress WHERE profileId = :profileId AND animeSlug = :slug")
    suspend fun countForSeries(profileId: Long, slug: String): Int

    @Query("SELECT * FROM episode_progress WHERE profileId = :profileId ORDER BY updatedAt DESC LIMIT 50")
    fun getRecent(profileId: Long): Flow<List<EpisodeProgress>>

    /** Puntos de reanudación de UNA serie: lo que la rejilla de episodios necesita para pintar
     *  "a medias" en los tiles que el usuario dejó empezados. */
    @Query("SELECT * FROM episode_progress WHERE profileId = :profileId AND animeSlug = :slug")
    fun getForSeries(profileId: Long, slug: String): Flow<List<EpisodeProgress>>

    // ── Copia de seguridad ────────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM episode_progress")
    suspend fun getAllOnce(): List<EpisodeProgress>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<EpisodeProgress>)

    @Query("DELETE FROM episode_progress")
    suspend fun deleteAll()

    @Query("DELETE FROM episode_progress WHERE profileId = :profileId")
    suspend fun deleteProfile(profileId: Long)
}
