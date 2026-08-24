package com.animeav1.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El formato del fichero de copia de seguridad. Es lo único de este proyecto que tiene que seguir
 * siendo legible por versiones futuras de la app, así que los tests fijan el contrato, no solo el
 * ida y vuelta. Los tres formatos que se han escrito alguna vez tienen que seguir importándose.
 */
class BackupCodecTest {

    private val uuidPrincipal = "11111111-2222-3333-4444-555555555555"
    private val uuidAna       = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    private fun refP() = ProfileRef.Uuid(uuidPrincipal)
    private fun refA() = ProfileRef.Uuid(uuidAna)

    private val data = BackupData(
        exportedAt = 1_786_219_231_881L,
        profiles = listOf(
            BackupProfile(refP(), "Principal", 0, 1_700_000_000_000L),
            BackupProfile(refA(), "Ana", 3, 1_780_000_000_000L)
        ),
        series = listOf(
            BackupSeries(refP(), "dandadan", "Dandadan", "https://x/1.jpg", "viendo",
                12, true, 1_700_000_000_000L, "2024", 0, "TV Anime"),
            BackupSeries(refA(), "dandadan", "Dandadan", "https://x/1.jpg", "por_ver",
                12, false, 1_780_000_000_000L, "2024", 0, "TV Anime")
        ),
        watched  = listOf(BackupWatched(refP(), "dandadan", 1, 1_786_074_769_247L)),
        progress = listOf(BackupProgress(refP(), "dandadan", 3, 889_421L, 1_437_024L, 1_786_100_000_000L)),
        prefs    = listOf(
            BackupSeriesPrefs(refP(), "dandadan", "DUB", "HLS"),
            BackupSeriesPrefs(refA(), "dandadan", "SUB", "YourUpload")
        )
    )

    @Test
    fun `ida y vuelta sin perder ningun campo`() {
        val back = BackupCodec.decode(BackupCodec.encode(data))
        assertNotNull(back)
        assertEquals(data.exportedAt, back!!.exportedAt)
        assertEquals(data.profiles, back.profiles)
        assertEquals(data.series, back.series)
        assertEquals(data.watched, back.watched)
        assertEquals(data.progress, back.progress)
        assertEquals(data.prefs, back.prefs)
    }

    /**
     * Las preferencias de pista/fuente se añadieron DESPUÉS del formato 3, y a propósito sin subir
     * `FORMAT`: son puramente aditivas. Un fichero escrito antes no las trae y tiene que seguir
     * importándose entero, con las preferencias vacías — no rechazado ni a medias.
     */
    @Test
    fun `un formato 3 sin prefs se importa con las preferencias vacias`() {
        val root = JSONObject(BackupCodec.encode(data))
        root.remove("prefs")
        val back = BackupCodec.decode(root.toString())
        assertNotNull(back)
        assertTrue(back!!.prefs.isEmpty())
        // Y lo importante: el resto del fichero llega intacto.
        assertEquals(data.series, back.series)
        assertEquals(data.progress, back.progress)
    }

    /**
     * Las preferencias son CÓMO ver una serie, no QUÉ series hay. Un fichero que solo las traiga
     * sigue estando vacío, así que "Sustituir" lo sigue rechazando en vez de vaciar las tablas de
     * todos los perfiles y anunciar "Restaurados 0 elementos".
     */
    @Test
    fun `un fichero con solo preferencias sigue contando como vacio`() {
        val soloPrefs = BackupData(
            exportedAt = 1L,
            profiles = listOf(BackupProfile(refP(), "Principal", 0, 1L)),
            series = emptyList(), watched = emptyList(), progress = emptyList(),
            prefs = listOf(BackupSeriesPrefs(refP(), "dandadan", "DUB", "HLS"))
        )
        assertTrue(soloPrefs.isEmpty)
        assertEquals(0, soloPrefs.itemCount)
        val back = BackupCodec.decode(BackupCodec.encode(soloPrefs))!!
        assertTrue(back.isEmpty)
    }

    /** Una preferencia sin slug no identifica ninguna serie: se descarta esa fila, no el fichero. */
    @Test
    fun `una preferencia sin slug se descarta sin tumbar la importacion`() {
        val root = JSONObject(BackupCodec.encode(data))
        root.getJSONArray("prefs").getJSONObject(0).put("slug", "")
        val back = BackupCodec.decode(root.toString())
        assertNotNull(back)
        assertEquals(1, back!!.prefs.size)
        assertEquals("dandadan", back.prefs[0].slug)
        assertEquals(data.series, back.series)
    }

    /**
     * **El motivo de que exista el uuid.** El fichero identifica a cada perfil por su uuid, nunca
     * por un `id` local: un `AUTOINCREMENT` es un ordinal del aparato que lo escribió, así que el
     * "perfil 2" de una TV no es el "perfil 2" de otra.
     */
    @Test
    fun `el fichero referencia perfiles por uuid y nunca por id local`() {
        val root = JSONObject(BackupCodec.encode(data))
        assertEquals(3, root.optInt("format"))

        val perfil = root.getJSONArray("profiles").getJSONObject(0)
        assertEquals(uuidPrincipal, perfil.optString("uuid"))
        assertTrue("el id local no debe viajar en el fichero", !perfil.has("id"))

        for (clave in listOf("series", "watched", "progress")) {
            val fila = root.getJSONArray(clave).getJSONObject(0)
            assertEquals("$clave debe referenciar el perfil por uuid",
                uuidPrincipal, fila.optString("profileUuid"))
            assertTrue("$clave no debe llevar profileId local", !fila.has("profileId"))
        }
    }

    /** La misma serie en dos perfiles son dos filas distintas y ninguna pisa a la otra. */
    @Test
    fun `conserva la misma serie en perfiles distintos`() {
        val back = BackupCodec.decode(BackupCodec.encode(data))!!
        val porSlug = back.series.filter { it.slug == "dandadan" }
        assertEquals(2, porSlug.size)
        assertEquals(setOf(refP(), refA()), porSlug.map { it.profile }.toSet())
        assertEquals("viendo",  porSlug.first { it.profile == refP() }.listType)
        assertEquals("por_ver", porSlug.first { it.profile == refA() }.listType)
    }

    /**
     * El contrato que importa de verdad: las claves del fichero son estables y NO son los nombres
     * de las columnas de Room. Renombrar un campo de la `@Entity` no debe invalidar los backups ya
     * escritos, y este test es lo que avisa si alguien acopla las dos cosas.
     */
    @Test
    fun `las claves del fichero estan congeladas`() {
        val root = JSONObject(BackupCodec.encode(data))
        assertEquals(
            setOf("uuid", "name", "colorIndex", "createdAt"),
            root.getJSONArray("profiles").getJSONObject(0).keys().asSequence().toSet()
        )
        assertEquals(
            setOf("profileUuid", "slug", "title", "coverUrl", "listType", "totalEpisodes",
                  "isFavorite", "addedAt", "year", "status", "category"),
            root.getJSONArray("series").getJSONObject(0).keys().asSequence().toSet()
        )
        assertEquals(
            setOf("profileUuid", "slug", "episode", "watchedAt"),
            root.getJSONArray("watched").getJSONObject(0).keys().asSequence().toSet()
        )
        assertEquals(
            setOf("profileUuid", "slug", "episode", "positionMs", "durationMs", "updatedAt"),
            root.getJSONArray("progress").getJSONObject(0).keys().asSequence().toSet()
        )
    }

    // ── Compatibilidad con los formatos ya escritos ──────────────────────────────────────────────

    /**
     * **FORMATO 1.** Así escribía la app antes de que existieran los perfiles. Sus filas no dicen de
     * quién son, así que se marcan [ProfileRef.Active] y el repositorio las adopta en el perfil que
     * esté activo al importar. Dejar de leerlo convertiría un respaldo ya guardado en basura.
     */
    @Test
    fun `formato 1 sin perfiles marca sus filas como del perfil activo`() {
        val formato1 = """
            {
              "format": 1, "app": "com.animeav1", "exportedAt": 1786220393111,
              "series": [{"slug":"dandadan","title":"Dandadan","coverUrl":"https://x/1.jpg",
                          "listType":"viendo","totalEpisodes":12,"isFavorite":false,
                          "addedAt":1786073418437,"year":"2024","status":0,"category":"TV Anime"}],
              "watched": [{"slug":"dandadan","episode":1,"watchedAt":1786074769247}],
              "progress": [{"slug":"dandadan","episode":3,"positionMs":1089955,
                            "durationMs":1437000,"updatedAt":1786077452883}]
            }
        """.trimIndent()

        val out = BackupCodec.decode(formato1)
        assertNotNull("un backup del formato 1 debe seguir leyéndose", out)
        assertTrue("el formato 1 no traía perfiles", out!!.profiles.isEmpty())
        assertEquals(ProfileRef.Active, out.series.single().profile)
        assertEquals(ProfileRef.Active, out.watched.single().profile)
        assertEquals(ProfileRef.Active, out.progress.single().profile)
        // …y sin perder nada de lo que sí traía.
        assertEquals("viendo", out.series.single().listType)
        assertEquals(12, out.series.single().totalEpisodes)
        assertEquals(1_089_955L, out.progress.single().positionMs)
        assertEquals(3, out.itemCount)
    }

    /**
     * **FORMATO 2.** Traía el `id` local del aparato que lo escribió. Se lee como
     * [ProfileRef.LegacyId] para que el repositorio pueda intentar casarlo con ese id local — que es
     * lo correcto al restaurar tu propia copia — en vez de tratarlo como identidad estable.
     */
    @Test
    fun `formato 2 se lee como id local heredado`() {
        val formato2 = """
            {
              "format": 2, "app": "com.animeav1", "exportedAt": 1786220393111,
              "profiles": [{"id":1,"name":"Principal","colorIndex":0,"createdAt":1},
                           {"id":2,"name":"Ana","colorIndex":1,"createdAt":2}],
              "series": [{"profileId":2,"slug":"dandadan","title":"Dandadan","coverUrl":"https://x/1.jpg",
                          "listType":"viendo","totalEpisodes":12,"isFavorite":false,"addedAt":1,
                          "year":"2024","status":0,"category":"TV Anime"}],
              "watched": [{"profileId":1,"slug":"dandadan","episode":1,"watchedAt":2}],
              "progress": [{"profileId":2,"slug":"dandadan","episode":3,"positionMs":10,
                            "durationMs":20,"updatedAt":3}]
            }
        """.trimIndent()

        val out = BackupCodec.decode(formato2)!!
        assertEquals(
            listOf(ProfileRef.LegacyId(1L), ProfileRef.LegacyId(2L)),
            out.profiles.map { it.ref }
        )
        assertEquals(ProfileRef.LegacyId(2L), out.series.single().profile)
        assertEquals(ProfileRef.LegacyId(1L), out.watched.single().profile)
        assertEquals(ProfileRef.LegacyId(2L), out.progress.single().profile)
        assertEquals("Ana", out.profiles.first { it.ref == ProfileRef.LegacyId(2L) }.name)
    }

    /** Un formato 2 sin `profileId` en una fila no puede inventarse un perfil: cae en el activo. */
    @Test
    fun `formato 2 sin profileId en una fila cae en el perfil activo`() {
        val roto = """
            {"format":2,"app":"com.animeav1",
             "series":[{"slug":"x","title":"X"}]}
        """.trimIndent()
        assertEquals(ProfileRef.Active, BackupCodec.decode(roto)!!.series.single().profile)
    }

    /** Un backup de una versión FUTURA se rechaza entero antes que importarse a medias. */
    @Test
    fun `rechaza un formato mas nuevo que el que entiende`() {
        val futuro = JSONObject(BackupCodec.encode(data)).put("format", BackupCodec.FORMAT + 1)
        assertNull(BackupCodec.decode(futuro.toString()))
    }

    @Test
    fun `rechaza basura sin lanzar`() {
        assertNull(BackupCodec.decode(""))
        assertNull(BackupCodec.decode("no soy json"))
        assertNull(BackupCodec.decode("""{"format":0}"""))
        assertNull(BackupCodec.decode("""{"nada":1}"""))          // sin format
    }

    /**
     * **Regresión de un fallo grave.** `decode` solo miraba `format`, así que cualquier JSON con un
     * número ahí se aceptaba como copia válida y decodificaba a un backup con las tres listas
     * VACÍAS. Elegir "Sustituir" con uno de esos vaciaba las tres tablas de todos los perfiles y
     * anunciaba "Restaurados 0 elementos".
     */
    @Test
    fun `exige el marcador de app y no solo el formato`() {
        assertNull("un format suelto no es una copia", BackupCodec.decode("""{"format":1}"""))
        assertNull("sin marcador de app", BackupCodec.decode("""{"format":3,"series":[]}"""))
        assertNull(
            "el fichero de otra app",
            BackupCodec.decode("""{"format":1,"app":"com.otracosa"}""")
        )
        assertNotNull(BackupCodec.decode("""{"format":3,"app":"${BackupCodec.APP_ID}"}"""))
    }

    /**
     * `isEmpty` es lo que la UI consulta para negarse a "Sustituir" con una copia sin nada. Era
     * código muerto y por eso el fallo de arriba llegaba hasta la base de datos.
     */
    @Test
    fun `una copia con perfiles pero sin estado cuenta como vacia`() {
        val soloPerfiles = BackupCodec.encode(
            BackupData(1L, listOf(BackupProfile(refP(), "Principal", 0, 1L)),
                emptyList(), emptyList(), emptyList())
        )
        val out = BackupCodec.decode(soloPerfiles)!!
        assertTrue("los perfiles no son contenido restaurable", out.isEmpty)
        assertEquals(0, out.itemCount)
        assertEquals(1, out.profiles.size)
    }

    @Test
    fun `tolera campos ausentes`() {
        val minimo = """
            {"format":3,"app":"com.animeav1",
             "series":[{"profileUuid":"$uuidPrincipal","slug":"x","title":"X"}],
             "watched":[{"profileUuid":"$uuidPrincipal","slug":"x","episode":2}],
             "progress":[{"profileUuid":"$uuidPrincipal","slug":"x","episode":2}]}
        """.trimIndent()
        val out = BackupCodec.decode(minimo)!!
        val s = out.series.single()
        assertEquals("x", s.slug)
        assertEquals(0, s.totalEpisodes)
        assertEquals(-1, s.status)
        assertEquals("", s.category)
        assertEquals(2, out.watched.single().episode)
        assertEquals(0L, out.progress.single().positionMs)
    }

    /** Filas sin clave primaria usable se descartan en vez de meter basura en Room. */
    @Test
    fun `descarta filas sin slug o sin episodio`() {
        val roto = """
            {"format":3,"app":"com.animeav1",
             "series":[{"profileUuid":"$uuidPrincipal","title":"sin slug"},
                       {"profileUuid":"$uuidPrincipal","slug":"ok","title":"OK"}],
             "watched":[{"profileUuid":"$uuidPrincipal","slug":"ok"},
                        {"profileUuid":"$uuidPrincipal","slug":"ok","episode":1}],
             "progress":[{"profileUuid":"$uuidPrincipal","episode":3},
                         {"profileUuid":"$uuidPrincipal","slug":"ok","episode":3}]}
        """.trimIndent()
        val out = BackupCodec.decode(roto)!!
        assertEquals(listOf("ok"), out.series.map { it.slug })
        assertEquals(1, out.watched.size)
        assertEquals(1, out.progress.size)
    }

    /** Un perfil sin uuid o sin nombre no se puede referenciar ni activar: fuera. */
    @Test
    fun `descarta perfiles sin uuid o sin nombre`() {
        val roto = """
            {"format":3,"app":"com.animeav1",
             "profiles":[{"name":"sin uuid"},{"uuid":"$uuidAna"},
                         {"uuid":"$uuidPrincipal","name":"Bien"}]}
        """.trimIndent()
        val out = BackupCodec.decode(roto)!!
        assertEquals(listOf(refP()), out.profiles.map { it.ref })
        assertEquals("Bien", out.profiles.single().name)
    }

    /** Exportar solo escribe el formato 3, así que una referencia que no sea uuid es un bug del
     *  llamante y se detecta en el sitio en vez de escribir un fichero inútil. */
    @Test(expected = IllegalStateException::class)
    fun `no permite exportar una referencia que no sea uuid`() {
        BackupCodec.encode(
            BackupData(1L, emptyList(),
                listOf(BackupSeries(ProfileRef.Active, "x", "X", "", "viendo", 0, false, 0L, "", -1, "")),
                emptyList(), emptyList())
        )
    }

    @Test
    fun `un backup vacio es valido y se reconoce como vacio`() {
        val vacio = BackupCodec.decode("""{"format":3,"app":"com.animeav1"}""")
        assertNotNull(vacio)
        assertTrue(vacio!!.isEmpty)
        assertEquals(0, vacio.itemCount)
    }

    @Test
    fun `itemCount suma las tres tablas y no los perfiles`() {
        assertEquals(4, data.itemCount)
        assertEquals(2, data.profiles.size)
    }
}
