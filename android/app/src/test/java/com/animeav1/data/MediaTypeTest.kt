package com.animeav1.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El tipo se deduce porque el sitio marca como "TV Anime" hasta las películas (de sus 1000 fichas,
 * solo UNA dice "Película"). Todos los casos de aquí son datos **reales** capturados del sitio el
 * 23/08/2026: `category`, `title`, `aka["es-419"]`, `episodesCount`, `runtime` y `status` tal cual
 * los devuelve `/media/<slug>/__data.json`.
 */
class MediaTypeTest {

    private val SITE = "TV Anime"   // lo que el sitio le pone a todo

    // ── Se corrige: son películas ─────────────────────────────────────────────

    @Test fun `kizuna - 94 minutos y un solo episodio`() {
        assertEquals("Película", MediaType.refine(
            category = SITE,
            title = "Digimon Adventure: Last Evolution Kizuna",
            aka = "Digimon Adventure la Película: Last Evolution Kizuna",
            episodesCount = 1, runtimeMinutes = 94, status = 0
        ))
    }

    @Test fun `broly - la duracion basta cuando el nombre no lo dice`() {
        assertEquals("Película", MediaType.refine(
            category = SITE, title = "Dragon Ball Super: Broly",
            episodesCount = 1, runtimeMinutes = 100, status = 0
        ))
    }

    @Test fun `one piece film red - lo dice el titulo`() {
        assertEquals("Película", MediaType.refine(SITE, "One Piece Film: Red"))
    }

    @Test fun `chainsaw man movie`() {
        assertEquals("Película", MediaType.refine(SITE, "Chainsaw Man Movie: Reze-hen"))
    }

    @Test fun `versailles no bara - Movie entre parentesis`() {
        assertEquals("Película", MediaType.refine(SITE, "Versailles no Bara (Movie)"))
    }

    @Test fun `dragon ball z pelicula 11 - sin tilde y de solo 46 minutos`() {
        // 46' está por debajo del umbral de duración: la salva el título.
        assertEquals("Película", MediaType.refine(
            category = SITE, title = "Dragon Ball Z Pelicula 11: El combate final",
            episodesCount = 1, runtimeMinutes = 46, status = 0
        ))
    }

    @Test fun `digimon adventure 02 Movies - recopilatorio en plural`() {
        assertEquals("Película", MediaType.refine(SITE, "Digimon Adventure 02 Movies"))
    }

    // ── NO se corrige: especiales, OVAs y series ──────────────────────────────

    @Test fun `ensa no edinburgh - especial de 54 minutos, no es pelicula`() {
        assertEquals(SITE, MediaType.refine(
            category = SITE, title = "Nanatsu no Taizai: Ensa no Edinburgh Part 2",
            episodesCount = 1, runtimeMinutes = 54, status = 0
        ))
    }

    @Test fun `cocoon - 60 minutos justos se queda como esta`() {
        assertEquals(SITE, MediaType.refine(
            category = SITE, title = "Cocoon: Aru Natsu no Shoujo-tachi yori",
            episodesCount = 1, runtimeMinutes = 60, status = 0
        ))
    }

    @Test fun `one piece fan letter - especial corto`() {
        assertEquals(SITE, MediaType.refine(
            category = SITE, title = "One Piece Fan Letter",
            episodesCount = 1, runtimeMinutes = 24, status = 0
        ))
    }

    @Test fun `serie larga`() {
        assertEquals(SITE, MediaType.refine(
            category = SITE, title = "One Piece", episodesCount = 1175, runtimeMinutes = 0, status = 2
        ))
    }

    @Test fun `serie recien estrenada que abre con un episodio doble`() {
        // Un solo episodio largo pero EN EMISIÓN: es un estreno, no una película.
        assertEquals(SITE, MediaType.refine(
            category = SITE, title = "Estreno con episodio doble",
            episodesCount = 1, runtimeMinutes = 95, status = 2
        ))
    }

    @Test fun `film pegado a otra palabra no cuenta`() {
        assertEquals(SITE, MediaType.refine(SITE, "Filmarks no Bouken"))
    }

    // ── Se respeta lo que diga el sitio si no es su valor por defecto ──────────

    @Test fun `una OVA declarada por el sitio no se toca aunque dure una hora`() {
        assertEquals("OVA", MediaType.refine(
            category = "OVA", title = "The Ribbon Hero",
            episodesCount = 1, runtimeMinutes = 90, status = 0
        ))
    }

    @Test fun `el catalogo solo tiene el titulo, asi que Kizuna se queda como TV Anime ahi`() {
        // No es un descuido: el catálogo no trae duración ni nº de episodios (misma limitación que
        // con el año y el estado). La ficha, que sí los trae, la corrige.
        assertEquals(SITE, MediaType.refine(SITE, "Digimon Adventure: Last Evolution Kizuna"))
    }

    @Test fun `sin categoria del sitio tampoco se inventa nada`() {
        assertEquals("", MediaType.refine("", "Serie cualquiera"))
    }
}
