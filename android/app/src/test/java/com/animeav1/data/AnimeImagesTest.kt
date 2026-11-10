package com.animeav1.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Las URLs del CDN son convenciones, no datos de la API, así que se prueban: un cambio silencioso
 * aquí deja la app sin imágenes y no lo caza ningún compilador.
 */
class AnimeImagesTest {

    @Test fun `portada y backdrop`() {
        assertEquals("https://cdn.animeav1.com/covers/197.jpg", AnimeImages.cover(197))
        assertEquals("https://cdn.animeav1.com/backdrops/197.jpg", AnimeImages.backdrop(197))
    }

    @Test fun `la miniatura va por NUMERO de episodio, no por su id`() {
        // Comprobado contra el CDN: la película de Digimon es la serie 1280 y su único episodio
        // tiene id 20309 y número 1. Por número devuelve la imagen; por id, 403.
        assertEquals("https://cdn.animeav1.com/screenshots/1280/1.jpg", AnimeImages.episodeThumb(1280, 1))
        assertEquals("https://cdn.animeav1.com/screenshots/197/1175.jpg", AnimeImages.episodeThumb(197, 1175))
    }

    @Test fun `la miniatura se puede derivar de la portada`() {
        assertEquals(
            "https://cdn.animeav1.com/screenshots/197/5.jpg",
            AnimeImages.episodeThumbFromCover("https://cdn.animeav1.com/covers/197.jpg", 5)
        )
    }

    @Test fun `un backdrop solo sirve de banner si es lo bastante ancho`() {
        // Medido en el CDN: los backdrops miden 1900×400 y alguno 1522×320. La portada que antes
        // se estiraba en su lugar es mucho más estrecha y se veía pixelada.
        assertEquals(true, AnimeImages.isSharpBanner(1900))
        assertEquals(true, AnimeImages.isSharpBanner(1522))
        assertEquals(true, AnimeImages.isSharpBanner(AnimeImages.MIN_BANNER_WIDTH_PX))
        assertEquals(false, AnimeImages.isSharpBanner(AnimeImages.MIN_BANNER_WIDTH_PX - 1))
        assertEquals(false, AnimeImages.isSharpBanner(0))
    }

    @Test fun `reducir un backdrop al tope de decodificacion nunca lo deja por debajo del minimo`() {
        assertEquals(true, AnimeImages.isSharpBanner(AnimeImages.BANNER_DECODE_WIDTH_PX))
    }

    @Test fun `sin una portada reconocible no se inventa una URL`() {
        // Devolver algo aquí sería pedirle al CDN una ruta imposible en cada tile.
        assertEquals("", AnimeImages.episodeThumbFromCover("", 1))
        assertEquals("", AnimeImages.episodeThumbFromCover("https://otro.sitio/imagen.jpg", 1))
        assertEquals("", AnimeImages.episodeThumbFromCover("https://cdn.animeav1.com/backdrops/197.jpg", 1))
    }
}
