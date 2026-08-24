package com.animeav1.data

/**
 * Corrige el **tipo** (categoría) de una obra cuando el sitio se equivoca.
 *
 * ⚠️ `animeav1.com` publica casi TODO como "TV Anime". Medido contra el propio sitio: de las 1000
 * fichas del catálogo, exactamente **una** está marcada como "Película", dos como OVA y una como
 * Especial (`/catalogo/__data.json?category=pelicula|ova|especial`). Así que ni *One Piece Film:
 * Red* ni *Digimon Adventure: Last Evolution Kizuna* —un solo episodio de 94 minutos— salían como
 * película. No es un fallo de la app: la propia web del sitio también las enseña como "TV Anime",
 * y su ficha JSON trae `category: {name: "TV Anime"}` con `runtime: 94`.
 *
 * Por eso el tipo se **deduce**, pero solo cuando la evidencia es inequívoca. Hoy el error de
 * origen es inofensivo (todo dice "TV Anime"), mientras que pasarse de listo diría algo falso de
 * una obra concreta, así que ante la duda se deja lo que diga el sitio:
 *
 * 1. **Lo dice el título** (el romaji o el español): "Movie", "Film", "Película". Verificado sobre
 *    los 1000 títulos del catálogo: casan 32 y las 32 son películas de verdad (*Chainsaw Man
 *    Movie: Reze-hen*, *Spy x Family Movie: Code: White*, *Versailles no Bara (Movie)*…), **cero
 *    falsos positivos**.
 * 2. **Un ÚNICO episodio de [MOVIE_MIN_RUNTIME] minutos o más**, en algo que ya no está en emisión.
 *    Es lo que rescata a las películas que no lo dicen en el nombre (Kizuna 94', *Dragon Ball
 *    Super: Broly* 100', *Kingsglaive* 115').
 *
 * ⚠️ El umbral es 70 minutos y no 60 a propósito: por debajo empiezan los **especiales y OVAs** de
 * un solo capítulo largo, que no son películas —*Nanatsu no Taizai: Ensa no Edinburgh* dura 52',
 * *Grisaia: Caprice no Mayu 0* 47', *Cocoon: Aru Natsu no Shoujo-tachi yori* 60'—. Las películas
 * cortas de ese rango (las de Dragon Ball Z, de 46' a 51') ya las caza la regla del título.
 * Y se exige que no esté **en emisión** porque una serie recién estrenada tiene `episodesCount == 1`
 * y puede abrir con un episodio doble.
 *
 * Mitad **pura**: sin red y sin Android, para poder probarla (ver `MediaTypeTest`).
 */
object MediaType {

    /** Etiqueta de tipo para una película. Coincide con la del filtro del catálogo. */
    const val MOVIE = "Película"

    /** La categoría que el sitio le pone a todo; es la ÚNICA que se corrige. */
    private const val GENERIC = "TV Anime"

    /** `status` del sitio para "En emisión". */
    private const val STATUS_AIRING = 2

    /** Minutos a partir de los cuales un episodio único es una película y no un especial largo. */
    private const val MOVIE_MIN_RUNTIME = 70

    /**
     * "Movie"/"Film"/"Película" como **palabra**, no como subcadena: los `lookaround` evitan que
     * "film" case dentro de "filmación" o de un título que lo lleve pegado.
     */
    private val MOVIE_WORDS = Regex(
        """(?<!\p{L})(?:movies?|pel[ií]culas?|film)(?!\p{L})""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Devuelve el tipo que se debe enseñar.
     *
     * @param category  lo que dice el sitio.
     * @param title     título principal (romaji).
     * @param aka       título en español (`aka["es-419"]`), si se conoce.
     * @param episodesCount episodios publicados; 0 = no se sabe (el catálogo no lo trae).
     * @param runtimeMinutes duración del episodio en minutos; 0 = no se sabe.
     * @param status    estado del sitio; -1 = no se sabe.
     */
    fun refine(
        category: String,
        title: String,
        aka: String = "",
        episodesCount: Int = 0,
        runtimeMinutes: Int = 0,
        status: Int = -1
    ): String {
        // Si el sitio se ha molestado en decir algo distinto de su valor por defecto, se le cree.
        if (category.isNotBlank() && category != GENERIC) return category
        if (saysMovie(title) || saysMovie(aka)) return MOVIE
        if (episodesCount == 1 && runtimeMinutes >= MOVIE_MIN_RUNTIME && status != STATUS_AIRING) return MOVIE
        return category
    }

    private fun saysMovie(text: String): Boolean =
        text.isNotBlank() && MOVIE_WORDS.containsMatchIn(text)
}
