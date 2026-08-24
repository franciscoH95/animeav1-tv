package com.animeav1.data.model

data class Anime(
    val id: String,
    val title: String,
    val slug: String,
    val category: String,
    val synopsis: String = ""
) {
    val coverUrl: String get() = "https://cdn.animeav1.com/covers/$id.jpg"
}

data class Series(
    val id: Int,
    val title: String,
    val slug: String,
    val synopsis: String,
    val category: String,
    val genres: List<String>,
    val status: Int,
    val score: Double,
    val votes: Int,
    val episodesCount: Int,
    val startDate: String,
    val episodes: List<EpisodeRef>,
    val relations: List<Relation> = emptyList(),
    /**
     * Ancla de la cadencia de emisión ("yyyy-MM-dd"), del campo `nextDate` del sitio.
     * ⚠️ Pese al nombre **no es la fecha del próximo episodio** —coincide con `startDate`—; solo
     * sirve como punto de partida para contar. Ver [com.animeav1.data.AiringSchedule].
     */
    val airingAnchor: String = "",
    /** Días entre episodios (`waitDays`). 0 = sin cadencia conocida. */
    val waitDays: Int = 0
) {
    val statusName: String get() = when (status) {
        0    -> "Finalizado"
        1    -> "Próximamente"
        2    -> "En emisión"
        3    -> "Cancelado"
        else -> "Desconocido"
    }
    val coverUrl:    String get() = "https://cdn.animeav1.com/covers/$id.jpg"
    val backdropUrl: String get() = "https://cdn.animeav1.com/backdrops/$id.jpg"
}

data class EpisodeRef(val number: Int, val id: Int)

/**
 * Pista de audio de un episodio. El sitio publica cada episodio en dos bloques independientes
 * (`embeds.SUB` / `embeds.DUB`) con su propia lista de servidores: el mismo "HLS" existe en las
 * dos, así que el nombre del servidor por sí solo NO identifica un embed.
 *
 * Sin etiqueta de UI a propósito: el texto visible vive en `strings.xml` (ver `AudioTrack.labelRes`
 * en la capa de UI), esta capa no toca recursos de Android.
 */
enum class AudioTrack { SUB, DUB }

data class EmbedServer(
    val server: String,
    val url: String,
    val audio: AudioTrack = AudioTrack.SUB
)

data class CatalogPage(val results: List<Anime>, val total: Int)

data class CatalogFilter(
    val category: String = "",   // tv-anime | pelicula | ova | especial
    val genre: String    = "",   // accion | aventura | …
    val status: String   = "",   // emision | finalizado | proximamente
    val year: String     = "",   // display-only label, not sent to API
    /** Orden del catálogo: "" (por defecto del sitio) | popular | score | title. */
    val order: String    = ""
) {
    /**
     * ⚠️ `order` queda FUERA a propósito: no es un filtro que reduzca resultados, así que
     * "Quitar filtros" no debe reordenar la rejilla bajo los pies del usuario.
     */
    val isEmpty: Boolean get() = category.isBlank() && genre.isBlank() && status.isBlank()
}

data class Relation(
    val type: Int,
    val id: Int,
    val slug: String,
    val title: String,
    val startDate: String
) {
    val typeName: String get() = when (type) {
        1    -> "Precuela"
        2    -> "Secuela"
        3    -> "Alternativa"
        4    -> "Spin-off"
        10   -> "Relacionado"
        else -> "Relacionado"
    }
    val coverUrl: String get() = "https://cdn.animeav1.com/covers/$id.jpg"
}

data class ScheduleItem(
    val id: Int,
    val title: String,
    val slug: String,
    val category: String,
    val latestEpisodeNumber: Int,
    val latestEpisodeDate: String,
    val dayOfWeek: String
) {
    val coverUrl: String get() = "https://cdn.animeav1.com/covers/$id.jpg"
}

