package com.animeav1.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El manifiesto OTA decide si se descarga y se instala un APK, que es la operación menos reversible
 * de la app: por eso lo que no cumple se descarta ENTERO en vez de completarse con valores por
 * defecto, y por eso hay tests de lo que debe rechazar más que de lo que debe aceptar.
 */
class UpdateManifestTest {

    private val SHA = "a".repeat(64)

    private fun json(
        code: String = "9", name: String = "\"1.5.0\"",
        url: String = "\"https://github.com/u/r/releases/download/v1.5.0/animeav1.apk\"",
        sha: String = "\"$SHA\"", extra: String = ""
    ) = """{"versionCode":$code,"versionName":$name,"apkUrl":$url,"sha256":$sha$extra}"""

    // ── Lo que acepta ─────────────────────────────────────────────────────────

    @Test fun `manifiesto completo`() {
        val info = UpdateManifest.parse(json(extra = ""","sizeBytes":9123456,"notes":"Arregla el buscador","minSdk":21"""))!!
        assertEquals(9, info.versionCode)
        assertEquals("1.5.0", info.versionName)
        assertEquals(9123456L, info.sizeBytes)
        assertEquals("Arregla el buscador", info.notes)
        assertEquals(21, info.minSdk)
    }

    @Test fun `los campos opcionales pueden faltar`() {
        val info = UpdateManifest.parse(json())!!
        assertEquals(0L, info.sizeBytes)
        assertEquals("", info.notes)
        assertEquals(0, info.minSdk)
    }

    @Test fun `la huella se normaliza a minusculas`() {
        assertEquals(SHA, UpdateManifest.parse(json(sha = "\"${SHA.uppercase()}\""))!!.sha256)
    }

    // ── Lo que rechaza ────────────────────────────────────────────────────────

    @Test fun `json roto`() = assertNull(UpdateManifest.parse("no soy json"))
    @Test fun `json vacio`() = assertNull(UpdateManifest.parse(""))
    @Test fun `sin versionCode`() = assertNull(UpdateManifest.parse(json(code = "0")))
    @Test fun `sin versionName`() = assertNull(UpdateManifest.parse(json(name = "\"\"")))

    @Test fun `una url que no es http no se acepta`() {
        // Sin esto, un manifiesto manipulado podría señalar a un fichero del propio aparato.
        assertNull(UpdateManifest.parse(json(url = "\"file:///sdcard/malo.apk\"")))
        assertNull(UpdateManifest.parse(json(url = "\"\"")))
    }

    @Test fun `una huella que no es un sha256 no se acepta`() {
        assertNull(UpdateManifest.parse(json(sha = "\"1234\"")))            // corta
        assertNull(UpdateManifest.parse(json(sha = "\"${"z".repeat(64)}\"")))  // no hexadecimal
        assertNull(UpdateManifest.parse(json(sha = "\"\"")))                // ausente
    }

    // ── A quién se le ofrece ──────────────────────────────────────────────────

    private fun info(code: Int, minSdk: Int = 0) =
        UpdateInfo(code, "x", "https://x/y.apk", SHA, minSdk = minSdk)

    @Test fun `se ofrece una version mayor`() = assertTrue(UpdateManifest.shouldOffer(info(9), 8, 34))

    @Test fun `no se ofrece la misma version ya instalada`() =
        assertFalse(UpdateManifest.shouldOffer(info(8), 8, 34))

    @Test fun `no se ofrece una version anterior`() =
        assertFalse(UpdateManifest.shouldOffer(info(7), 8, 34))

    @Test fun `sin manifiesto no se ofrece nada`() =
        assertFalse(UpdateManifest.shouldOffer(null, 8, 34))

    @Test fun `no se ofrece lo que el aparato no puede instalar`() {
        // Comprobarlo aquí evita bajarse 9 MB para que el instalador falle sin explicar por qué.
        assertFalse(UpdateManifest.shouldOffer(info(9, minSdk = 26), 8, 21))
        assertTrue(UpdateManifest.shouldOffer(info(9, minSdk = 21), 8, 21))
    }
}
