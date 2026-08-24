package com.animeav1.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Predefined list types for watchlist tracking */
enum class ListType(val displayName: String, val key: String) {
    POR_VER("Por Ver", "por_ver"),
    VIENDO("Viendo", "viendo"),
    COMPLETADAS("Completadas", "completadas"),
    FAVORITOS("Favoritos", "favoritos");

    companion object {
        fun fromKey(key: String) = values().firstOrNull { it.key == key } ?: POR_VER
    }
}

/**
 * Un perfil de usuario. Todo el estado local (listas, vistos, puntos de reanudación) cuelga de uno,
 * así que dos personas comparten la TV sin pisarse.
 *
 * [DEFAULT_ID] es el perfil que la migración 7→8 crea para adoptar los datos que ya existían: el
 * `id` es fijo porque la migración tiene que poder referenciarlo desde SQL puro.
 *
 * ## Dos identificadores, y cada uno tiene su sitio
 *
 * - **[id]** — clave local. Es lo que llevan las tres tablas de estado en su clave primaria: un
 *   entero es más barato de indexar y de comparar, y dentro de este aparato basta.
 * - **[uuid]** — identidad **estable entre aparatos**, y lo único que viaja en la copia de
 *   seguridad. El `id` es un `AUTOINCREMENT`: el "perfil 2" de una TV no es el "perfil 2" de otra,
 *   así que un backup que se refiriera a los perfiles por `id` fusionaba personas distintas al
 *   importarlo en otro aparato. Con el uuid, importar reconoce "este perfil ya está aquí" o crea uno
 *   nuevo, y las dos cosas son correctas.
 */
@Entity(
    tableName = "profiles",
    indices = [Index(value = ["uuid"], unique = true)]
)
data class Profile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Identidad estable entre aparatos. Se genera al crear el perfil y NO se reasigna nunca. */
    @ColumnInfo(defaultValue = "") val uuid: String = "",
    val name: String,
    /** Índice en la paleta de avatares (`Profile.COLORS` en la capa de UI). Sin imágenes: en una TV
     *  una inicial sobre color se lee de lejos y no hay que gestionar ficheros. */
    val colorIndex: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val DEFAULT_ID = 1L
        /** Cuántos colores de avatar hay; el índice se guarda módulo este número. */
        const val COLOR_COUNT = 6
    }
}

@Entity(tableName = "favorite_series", primaryKeys = ["profileId", "slug"])
data class FavoriteSeries(
    val profileId: Long,
    val slug: String,
    val title: String,
    val coverUrl: String,
    val listType: String = ListType.POR_VER.key,
    val totalEpisodes: Int = 0,
    val isFavorite: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "") val year: String = "",
    @ColumnInfo(defaultValue = "-1") val status: Int = -1,
    @ColumnInfo(defaultValue = "") val category: String = ""
) {
    /** Display label for the series status (empty when unknown / not yet stored). */
    val statusName: String get() = when (status) {
        0 -> "Finalizado"
        1 -> "Próximamente"
        2 -> "En emisión"
        3 -> "Cancelado"
        else -> ""
    }
}

@Entity(tableName = "watched_episodes", primaryKeys = ["profileId", "animeSlug", "episodeNumber"])
data class WatchedEpisode(
    val profileId: Long,
    val animeSlug: String,
    val episodeNumber: Int,
    val watchedAt: Long = System.currentTimeMillis()
)

/**
 * Cómo quiere ver el usuario ESTA serie: qué pista de audio y qué fuente.
 *
 * Por perfil y por serie, no global: en casa es normal ver una serie doblada y otra subtitulada, y la
 * elección es de la persona, no del aparato. Antes solo se arrastraba de un episodio al siguiente, así
 * que entrar desde la ficha o desde Inicio volvía siempre a subtitulado — y el episodio empezaba a
 * sonar en japonés antes de que se pudiera corregir.
 */
@Entity(tableName = "series_prefs", primaryKeys = ["profileId", "slug"])
data class SeriesPrefs(
    val profileId: Long,
    val slug: String,
    /** Nombre de [com.animeav1.data.model.AudioTrack]; vacío = sin preferencia. */
    val audio: String = "",
    /** Nombre del servidor tal y como lo publica el sitio; vacío = sin preferencia. */
    val server: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

/** Resume point for an episode: how far the user got, to continue where they left off. */
@Entity(tableName = "episode_progress", primaryKeys = ["profileId", "animeSlug", "episodeNumber"])
data class EpisodeProgress(
    val profileId: Long,
    val animeSlug: String,
    val episodeNumber: Int,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long = System.currentTimeMillis()
)
