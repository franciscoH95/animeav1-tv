package com.animeav1.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the SvelteKit `__data.json` decoder against a real capture of
 * `animeav1.com/catalogo/__data.json?page=1`.
 */
class SvelteKitDecoderTest {

    private val catalog = fixture("catalogo__data.json")

    @Test
    fun `finds the data node that carries the requested root key`() {
        val data = SvelteKitDecoder.decode(catalog, "results")
        assertNotNull("no encontró el nodo con la clave 'results'", data)
        assertEquals(1000, data!!.optInt("total"))
        assertEquals(20, data.optJSONArray("results")!!.length())
    }

    @Test
    fun `resolves integer references into their actual values`() {
        val first = SvelteKitDecoder.decode(catalog, "results")!!
            .getJSONArray("results").getJSONObject(0)

        assertEquals("4429", first.optString("id"))
        assertEquals("Bleach: Sennen Kessen-hen - Kashin-tan", first.optString("title"))
        assertEquals("bleach-sennen-kessen-hen-kashin-tan", first.optString("slug"))
        assertTrue(first.optString("synopsis").startsWith("Parte final de Bleach"))
    }

    /**
     * The flat array is deduplicated: in this capture all 20 results point at the *same*
     * physical category node (index 8). Each reference has to be expanded into a full object,
     * not handed back as the raw integer — that integer is what used to reach the UI.
     */
    @Test
    fun `expands a node shared by every result`() {
        val results = SvelteKitDecoder.decode(catalog, "results")!!.getJSONArray("results")
        for (i in 0 until results.length()) {
            val category = results.getJSONObject(i).optJSONObject("category")
            assertNotNull("el result $i perdió su objeto category", category)
            assertEquals("TV Anime", category!!.optString("name"))
        }
    }

    @Test
    fun `returns null when no node carries the key`() {
        assertNull(SvelteKitDecoder.decode(catalog, "media"))
    }

    @Test
    fun `returns null instead of throwing on malformed input`() {
        assertNull(SvelteKitDecoder.decode("""{"nodes":[]}""", "results"))
        assertNull(SvelteKitDecoder.decode("""{"nodes":[{"type":"data","data":[]}]}""", "results"))
    }
}
