package com.animeav1.data

import org.json.JSONObject

/**
 * Lo que anuncia el fichero `update.json` publicado junto al APK.
 *
 * @property sha256 huella del APK. **No es un adorno**: es lo único que garantiza que lo que se
 *   instala es lo que se publicó, así que el instalador se niega a seguir si no cuadra.
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long = 0L,
    val notes: String = "",
    val minSdk: Int = 0
)

/**
 * Mitad **pura** de las actualizaciones OTA: leer el manifiesto y decidir si hay algo que ofrecer.
 * Sin red y sin Android, para poder probarla (ver `UpdateManifestTest`).
 *
 * El formato es deliberadamente pequeño y **aditivo**: un campo nuevo lo ignora un lector viejo, y
 * uno que falte cae en su valor por defecto. Mismo criterio que el formato de la copia de seguridad.
 *
 * ```json
 * {
 *   "versionCode": 9,
 *   "versionName": "1.5.0",
 *   "apkUrl": "https://github.com/usuario/repo/releases/download/v1.5.0/animeav1.apk",
 *   "sha256": "…64 hex…",
 *   "sizeBytes": 9123456,
 *   "notes": "Qué cambia",
 *   "minSdk": 21
 * }
 * ```
 *
 * ⚠️ Un manifiesto que no cumpla TODO lo exigido se descarta entero (`null`) en vez de completarse
 * con valores por defecto: aquí el resultado de creerse un dato a medias es instalar un APK, que es
 * la operación menos reversible de la app.
 */
object UpdateManifest {

    /** Huella hexadecimal de 64 caracteres; cualquier otra cosa no es un SHA-256. */
    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")

    fun parse(json: String): UpdateInfo? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val versionCode = o.optInt("versionCode", 0)
        val versionName = o.optString("versionName").trim()
        val apkUrl      = o.optString("apkUrl").trim()
        val sha256      = o.optString("sha256").trim()
        if (versionCode <= 0 || versionName.isEmpty()) return null
        // Solo http(s): sin esto un manifiesto manipulado podría apuntar a un `file://` del propio
        // aparato y colar un APK que nadie ha descargado.
        if (!apkUrl.startsWith("http://") && !apkUrl.startsWith("https://")) return null
        if (!SHA256.matches(sha256)) return null
        return UpdateInfo(
            versionCode = versionCode,
            versionName = versionName,
            apkUrl      = apkUrl,
            sha256      = sha256.lowercase(),
            sizeBytes   = o.optLong("sizeBytes", 0L),
            notes       = o.optString("notes").trim(),
            minSdk      = o.optInt("minSdk", 0)
        )
    }

    /**
     * ¿Se le ofrece al usuario?
     *
     * ⚠️ Se compara por `versionCode` y **nunca por `versionName`**: el nombre es para leerlo y no
     * ordena ("1.10.0" es posterior a "1.9.0" pero menor alfabéticamente). Y se exige mayor
     * ESTRICTO: reofrecer la misma versión que ya está instalada sería un bucle de descargas.
     *
     * ⚠️ `minSdk` se comprueba aquí y no al instalar: en un aparato viejo el instalador fallaría con
     * un error que no dice nada, después de haberse bajado el APK entero.
     */
    fun shouldOffer(info: UpdateInfo?, installedVersionCode: Int, deviceSdk: Int): Boolean {
        if (info == null) return false
        if (info.versionCode <= installedVersionCode) return false
        return deviceSdk >= info.minSdk
    }
}
