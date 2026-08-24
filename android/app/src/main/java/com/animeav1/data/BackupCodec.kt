package com.animeav1.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * A qué perfil pertenece una fila de un backup. **No** es un `profileId` local: el fichero puede
 * venir de otro aparato, donde los `id` significan otra cosa.
 *
 * Un caso por cada formato que se ha escrito alguna vez, y de ahí que sean tres:
 */
sealed class ProfileRef {
    /** FORMATO 1 — el fichero no tenía perfiles. Sus filas se adoptan en el perfil activo. */
    object Active : ProfileRef()

    /** FORMATO 2 — el fichero traía el `id` local del aparato que lo escribió. Solo sirve para
     *  restaurar en ESE aparato; es justo la fragilidad que arregla el uuid. */
    data class LegacyId(val id: Long) : ProfileRef()

    /** FORMATO 3 — identidad estable. Vale entre aparatos. */
    data class Uuid(val uuid: String) : ProfileRef()
}

/** Un perfil tal y como viaja en el fichero. */
data class BackupProfile(
    val ref: ProfileRef,
    val name: String,
    val colorIndex: Int,
    val createdAt: Long
)

data class BackupSeries(
    val profile: ProfileRef,
    val slug: String,
    val title: String,
    val coverUrl: String,
    val listType: String,
    val totalEpisodes: Int,
    val isFavorite: Boolean,
    val addedAt: Long,
    val year: String,
    val status: Int,
    val category: String
)

data class BackupWatched(
    val profile: ProfileRef,
    val slug: String,
    val episode: Int,
    val watchedAt: Long
)

/** Preferencia de pista/fuente de una serie, tal y como viaja en el fichero. */
data class BackupSeriesPrefs(
    val profile: ProfileRef,
    val slug: String,
    val audio: String,
    val server: String
)

data class BackupProgress(
    val profile: ProfileRef,
    val slug: String,
    val episode: Int,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long
)

/**
 * Todo el estado local del usuario tal y como se serializa al fichero.
 *
 * ⚠️ Deliberadamente **NO** son las `@Entity` de Room. Antes sí lo eran, y eso ataba el formato del
 * fichero al esquema local: las filas llegaban ya con un `profileId` que solo tenía sentido en el
 * aparato que las escribió. Ahora el fichero habla de perfiles por [ProfileRef] y es
 * [LocalRepository.importAll] quien lo traduce a ids locales.
 */
data class BackupData(
    val exportedAt: Long,
    val profiles: List<BackupProfile>,
    val series: List<BackupSeries>,
    val watched: List<BackupWatched>,
    val progress: List<BackupProgress>,
    /**
     * Preferencia de pista/fuente por serie. Campo **añadido después** del formato 3 y por eso
     * opcional: es puramente aditivo, así que un lector viejo lo ignora y uno nuevo lo da por vacío
     * si el fichero no lo trae. No hacía falta subir `FORMAT` para eso.
     */
    val prefs: List<BackupSeriesPrefs> = emptyList()
) {
    /** Las preferencias NO cuentan: son cómo ver las series, no qué series hay. Un fichero con solo
     *  preferencias sigue siendo "vacío" y "Sustituir" lo sigue rechazando. */
    val isEmpty: Boolean get() = series.isEmpty() && watched.isEmpty() && progress.isEmpty()

    /** Lo que se le dice al usuario que se ha guardado / restaurado. Los perfiles no cuentan como
     *  "elementos": son el continente, no el contenido. */
    val itemCount: Int get() = series.size + watched.size + progress.size
}

/**
 * Serialización del estado local a JSON. Mitad **PURA** — sin red, sin Android, sin Room — para que
 * se pueda testear el formato, que es lo que tiene que seguir leyéndose dentro de dos años.
 *
 * Se escribe a mano con `org.json` por coherencia con el resto del proyecto (no hay
 * kotlinx.serialization) y porque así el formato queda explícito en un sitio en vez de depender de
 * los nombres de los campos de las `@Entity`: renombrar una columna no debe romper los backups ya
 * escritos.
 *
 * ## Versiones del formato
 *
 * | v | perfiles | referencia de cada fila | portable entre aparatos |
 * |---|----------|-------------------------|--------------------------|
 * | 1 | ninguno  | (implícita)             | trivialmente: todo a un perfil |
 * | 2 | con `id` local | `profileId` numérico | **no** — el "perfil 2" de otra TV es otra persona |
 * | 3 | con `uuid` | `profileUuid`         | sí |
 *
 * [decode] lee las tres. Los formatos viejos no se abandonan: se escribieron backups con ellos, y
 * dejar de leerlos convertiría un respaldo válido y ya guardado por el usuario en basura.
 */
internal object BackupCodec {

    const val FORMAT = 3

    /** Marcador que identifica el fichero como una copia de ESTA app. Se escribe y se comprueba. */
    const val APP_ID = "com.animeav1"

    fun encode(data: BackupData): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("app", APP_ID)
        root.put("exportedAt", data.exportedAt)

        root.put("profiles", JSONArray().apply {
            data.profiles.forEach { p ->
                put(JSONObject().apply {
                    put("uuid", uuidOf(p.ref))
                    put("name", p.name)
                    put("colorIndex", p.colorIndex)
                    put("createdAt", p.createdAt)
                })
            }
        })
        root.put("series", JSONArray().apply {
            data.series.forEach { s ->
                put(JSONObject().apply {
                    put("profileUuid", uuidOf(s.profile))
                    put("slug", s.slug)
                    put("title", s.title)
                    put("coverUrl", s.coverUrl)
                    put("listType", s.listType)
                    put("totalEpisodes", s.totalEpisodes)
                    put("isFavorite", s.isFavorite)
                    put("addedAt", s.addedAt)
                    put("year", s.year)
                    put("status", s.status)
                    put("category", s.category)
                })
            }
        })
        root.put("watched", JSONArray().apply {
            data.watched.forEach { w ->
                put(JSONObject().apply {
                    put("profileUuid", uuidOf(w.profile))
                    put("slug", w.slug)
                    put("episode", w.episode)
                    put("watchedAt", w.watchedAt)
                })
            }
        })
        root.put("progress", JSONArray().apply {
            data.progress.forEach { p ->
                put(JSONObject().apply {
                    put("profileUuid", uuidOf(p.profile))
                    put("slug", p.slug)
                    put("episode", p.episode)
                    put("positionMs", p.positionMs)
                    put("durationMs", p.durationMs)
                    put("updatedAt", p.updatedAt)
                })
            }
        })
        root.put("prefs", JSONArray().apply {
            data.prefs.forEach { p ->
                put(JSONObject().apply {
                    put("profileUuid", uuidOf(p.profile))
                    put("slug", p.slug)
                    put("audio", p.audio)
                    put("server", p.server)
                })
            }
        })
        return root.toString(2)
    }

    /**
     * Solo se escribe el formato 3, así que todo lo que se exporta tiene que llegar con un uuid de
     * verdad. Si aparece otra cosa es un error de programación en el que llama, no un dato malo del
     * usuario: se deja explícito en vez de escribir un fichero silenciosamente inútil.
     */
    private fun uuidOf(ref: ProfileRef): String = when (ref) {
        is ProfileRef.Uuid -> ref.uuid
        else -> error("solo se exporta el formato $FORMAT, que referencia perfiles por uuid: $ref")
    }

    /**
     * @return los datos, o `null` si el texto no es un backup de esta app que se pueda leer.
     *         Nunca lanza: un fichero corrupto o de otra app tiene que dar un mensaje, no un crash.
     */
    fun decode(json: String): BackupData? {
        val root = try { JSONObject(json) } catch (e: Exception) { return null }
        // Un `format` mayor que el nuestro lo escribió una versión más nueva de la app: se rechaza
        // en vez de importar a medias y dejar los datos en un estado que nadie pretendía.
        val format = root.optInt("format", -1)
        if (format <= 0 || format > FORMAT) return null
        // Y el marcador de app: sin esto cualquier JSON con un número en "format" se aceptaba como
        // copia válida, y con la opción "Sustituir" eso vaciaba la base de datos sin restaurar nada.
        if (root.optString("app") != APP_ID) return null

        val profiles = root.optJSONArray("profiles").map { o ->
            val name = o.optString("name")
            if (name.isBlank()) return@map null
            val ref = profileRef(o, format, "uuid", "id") ?: return@map null
            BackupProfile(ref, name, o.optInt("colorIndex", 0), o.optLong("createdAt", 0L))
        }
        val series = root.optJSONArray("series").map { o ->
            val slug = o.optString("slug")
            if (slug.isBlank()) return@map null
            BackupSeries(
                profile       = rowRef(o, format),
                slug          = slug,
                title         = o.optString("title"),
                coverUrl      = o.optString("coverUrl"),
                listType      = o.optString("listType").ifBlank { "none" },
                totalEpisodes = o.optInt("totalEpisodes", 0),
                isFavorite    = o.optBoolean("isFavorite", false),
                addedAt       = o.optLong("addedAt", 0L),
                year          = o.optString("year"),
                status        = o.optInt("status", -1),
                category      = o.optString("category")
            )
        }
        val watched = root.optJSONArray("watched").map { o ->
            val slug = o.optString("slug")
            val ep   = o.optInt("episode", -1)
            if (slug.isBlank() || ep < 0) return@map null
            BackupWatched(rowRef(o, format), slug, ep, o.optLong("watchedAt", 0L))
        }
        val progress = root.optJSONArray("progress").map { o ->
            val slug = o.optString("slug")
            val ep   = o.optInt("episode", -1)
            if (slug.isBlank() || ep < 0) return@map null
            BackupProgress(
                profile    = rowRef(o, format),
                slug       = slug,
                episode    = ep,
                positionMs = o.optLong("positionMs", 0L),
                durationMs = o.optLong("durationMs", 0L),
                updatedAt  = o.optLong("updatedAt", 0L)
            )
        }
        val prefs = root.optJSONArray("prefs").map { o ->
            val slug = o.optString("slug")
            if (slug.isBlank()) return@map null
            BackupSeriesPrefs(rowRef(o, format), slug, o.optString("audio"), o.optString("server"))
        }
        return BackupData(root.optLong("exportedAt", 0L), profiles, series, watched, progress, prefs)
    }

    /** Referencia de perfil de una fila de estado (series / vistos / progreso). */
    private fun rowRef(o: JSONObject, format: Int): ProfileRef =
        profileRef(o, format, "profileUuid", "profileId") ?: ProfileRef.Active

    /**
     * @param uuidKey clave del uuid en el formato 3; [legacyKey] la del id numérico en el formato 2.
     * @return null solo cuando el formato exige una referencia y no hay ninguna usable.
     */
    private fun profileRef(o: JSONObject, format: Int, uuidKey: String, legacyKey: String): ProfileRef? =
        when {
            // Formato 1: no había perfiles. Todo va al perfil que esté activo al importar.
            format < 2 -> ProfileRef.Active
            format == 2 -> o.optLong(legacyKey, 0L).takeIf { it > 0L }?.let { ProfileRef.LegacyId(it) }
            else -> o.optString(uuidKey).takeIf { it.isNotBlank() }?.let { ProfileRef.Uuid(it) }
        }

    /** Recorre un array que puede ser null y descarta las entradas que el bloque no sepa leer. */
    private fun <T> JSONArray?.map(block: (JSONObject) -> T?): List<T> {
        this ?: return emptyList()
        val out = mutableListOf<T>()
        for (i in 0 until length()) {
            val obj = optJSONObject(i) ?: continue
            block(obj)?.let { out.add(it) }
        }
        return out
    }
}
