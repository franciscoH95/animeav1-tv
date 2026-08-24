package com.animeav1.data

/**
 * URLs de las imágenes del CDN.
 *
 * ⚠️ **Ninguna la publica la API**: todas se arman por convención a partir del id, así que este es
 * el único sitio donde vive esa convención. Si el sitio cambia una ruta, se cambia aquí.
 *
 * La miniatura por episodio existe —comprobado contra el CDN— y es lo que enseña la propia web en su
 * lista de episodios:
 *
 * ```
 * https://cdn.animeav1.com/screenshots/<idDeLaSerie>/<númeroDeEpisodio>.jpg
 * ```
 *
 * ⚠️ Va por **número de episodio, NO por su id**, que es el error fácil porque el resto del CDN va
 * por id. Medido: para la película de Digimon (serie 1280, episodio con id 20309 y número 1),
 * `/screenshots/1280/1.jpg` devuelve la imagen y `/screenshots/1280/20309.jpg` devuelve **403**.
 *
 * Lo que hay que saber de esas imágenes antes de confiar en ellas:
 * - Son **220×124** (16:9) y pesan de 3 a 8 KB. No hay versión mayor: `?w=640`, `_large`, `@2x` y
 *   `.webp` no existen (la propia web estira las de 220 px a 300).
 * - Cobertura buena: 36 de 36 en una muestra de 6 series, incluidos los episodios 1, 500 y 1175 de
 *   One Piece, una película y una serie recién estrenada.
 * - Cuando falta, el CDN responde **403 con HTML**, no un 404 de imagen: quien la cargue tiene que
 *   tratar el error, no basta con un placeholder para datos nulos.
 * - Un ~8% son fotogramas casi negros (capturados en un momento oscuro). Existen y cargan; se ven
 *   como un rectángulo negro y no hay forma barata de distinguirlos.
 */
object AnimeImages {

    const val CDN = "https://cdn.animeav1.com"

    fun cover(id: String): String = "$CDN/covers/$id.jpg"

    fun cover(id: Int): String = cover(id.toString())

    fun backdrop(id: Int): String = "$CDN/backdrops/$id.jpg"

    fun episodeThumb(seriesId: Int, episodeNumber: Int): String =
        "$CDN/screenshots/$seriesId/$episodeNumber.jpg"

    /**
     * La miniatura cuando solo se tiene la **portada** y no el id de la serie.
     *
     * Hace falta porque no todo el que enseña un episodio conoce ese id: el reproductor recibe la
     * portada por los extras del intent, y la fila *Continuar viendo* de Inicio sale de
     * `favorite_series`, que guarda la URL de la portada pero no el id. Las dos rutas del CDN son la
     * misma convención, así que traducir una en otra es más honesto que arrastrar un id nuevo por
     * media app.
     *
     * @return la URL, o `""` si [coverUrl] no es una portada de este CDN (y entonces quien llama se
     *   queda con lo que tuviera).
     */
    fun episodeThumbFromCover(coverUrl: String, episodeNumber: Int): String {
        val id = COVER_ID.find(coverUrl)?.groupValues?.get(1) ?: return ""
        return "$CDN/screenshots/$id/$episodeNumber.jpg"
    }

    private val COVER_ID = Regex("""/covers/(\d+)\.jpg""")
}
