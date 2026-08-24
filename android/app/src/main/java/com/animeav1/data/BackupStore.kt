package com.animeav1.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Dónde vive el fichero de copia de seguridad. Toda la fealdad de almacenamiento de Android está
 * aquí para que ni la UI ni el repositorio la vean.
 *
 * ## Por qué NO hay selector de ficheros
 *
 * Android TV **no trae DocumentsUI**. Lo único que responde a `ACTION_CREATE_DOCUMENT` /
 * `ACTION_OPEN_DOCUMENT` es `com.android.tv.frameworkpackagestubs.Stubs$DocumentsStub`, un stub que
 * solo avisa de que no se soporta (comprobado con `cmd package resolve-activity` en una imagen de
 * Android TV, API 36). Así que el patrón habitual "que el usuario elija dónde guardar" no existe en
 * esta plataforma: hay que escribir en un sitio fijo y ofrecer nosotros la lista.
 *
 * ## Dónde se escribe, y qué se puede volver a leer
 *
 * Se escribe en **dos** sitios a la vez, porque ninguno cubre solo los dos casos:
 *
 * 1. **[DIR_PUBLIC] en la carpeta pública de descargas** — SOBREVIVE a desinstalar la app. Es la
 *    copia que sirve de verdad como respaldo: se saca del aparato con `adb pull`, por USB o desde
 *    otro dispositivo. En API 29+ se escribe por MediaStore (sin permisos).
 *    ⚠️ Con scoped storage la app **no puede volver a leerla** después de reinstalarse: pierde la
 *    propiedad del fichero y `/sdcard/Download` le da `Permission denied` (comprobado en API 36).
 *    Por eso existe el punto 2.
 * 2. **La carpeta externa propia de la app** (`getExternalFilesDir("backups")`) — la app siempre
 *    puede leerla y escribirla sin permisos, en cualquier versión. Se borra al desinstalar, así que
 *    NO es un respaldo; es el buzón de **importación**: se deja ahí un fichero (con `adb push`, o
 *    porque es un backup de esta misma instalación) y la app lo ve en la lista.
 */
internal object BackupStore {

    /** Subcarpeta dentro de Descargas, para no soltar ficheros sueltos en la raíz. */
    private const val DIR_PUBLIC = "AnimeAV1"
    private const val DIR_INBOX  = "backups"
    private const val MIME       = "application/json"
    /** Prefijo de los ficheros que escribe esta app; también es el filtro al listarlos. */
    private const val NAME_PREFIX = "animeav1-"

    /**
     * Un fichero de backup que la app **puede leer** (o sea, importable). Puede estar en varios
     * sitios a la vez, y de ahí [sources]: el buzón de la app, una entrada de MediaStore de la que
     * este paquete sigue siendo propietario, o —en API ≤ 28— la carpeta pública por ruta directa.
     */
    data class Entry(
        val name: String,
        val sizeBytes: Long,
        val lastModified: Long,
        /**
         * TODAS las copias físicas que hay detrás de este nombre. Nunca vacía.
         *
         * ⚠️ Es una lista y no una sola `Source` a propósito. [write] escribe el MISMO nombre en los
         * dos destinos, así que lo normal es que una copia exista por duplicado. Cuando esto era una
         * `source` única, [list] se quedaba con la del buzón y **tiraba la de Descargas**: la Uri de
         * la gemela no llegaba a existir, así que era imposible borrarla — se borraba media copia y
         * la fila reaparecía en el refresco siguiente, con una fecha ligeramente distinta
         * (`DATE_MODIFIED` va en segundos y `File.lastModified()` en milisegundos), como si fuera
         * otro fichero. Y al revés era peor: borrar solo la de Descargas destruye el único respaldo
         * que sobrevive a desinstalar mientras la lista sigue enseñando la copia del buzón.
         */
        val sources: List<Source>
    ) {
        /** Está en la carpeta de la app: se puede importar ahora, pero se va al desinstalar. */
        val inInbox: Boolean get() = sources.any { it is Source.Local }

        /**
         * Está en Descargas: es el respaldo que sobrevive a desinstalar la app.
         *
         * ⚠️ Cuenta las DOS formas de estar ahí. En API 29+ es una fila de MediaStore; por debajo es
         * un fichero por ruta directa. Cuando esto solo miraba MediaStore, en API ≤28 **toda** copia
         * salía como "solo en la app · se pierde al desinstalar" aunque estuviera en Descargas —
         * y [delete] la borraba igual. Etiqueta, aviso y efecto decían tres cosas distintas, y la
         * que se cumplía era la destructiva.
         */
        val inDownloads: Boolean get() = sources.any { it is Source.Media || it is Source.Public }

        /** Dónde vive, ya resuelto. Aquí y no en cada capa de UI: el mismo `when` repetido en el
         *  adapter y en la Activity es como se acaba arreglando un sitio y olvidando el otro. */
        val location: Location get() = when {
            inDownloads && inInbox -> Location.BOTH
            inDownloads            -> Location.DOWNLOADS_ONLY
            else                   -> Location.INBOX_ONLY
        }

        sealed class Source {
            /** Buzón de la app (`getExternalFilesDir`). */
            data class Local(val file: File) : Source()
            /** Descargas por MediaStore (API 29+). */
            data class Media(val uri: Uri) : Source()
            /** Descargas por ruta directa (API ≤ 28). */
            data class Public(val file: File) : Source()
        }
    }

    /** Dónde vive una copia. Lo consulta la UI para no repetir la misma decisión en dos capas. */
    enum class Location { BOTH, DOWNLOADS_ONLY, INBOX_ONLY }

    /** Resultado de exportar: qué se consiguió escribir y dónde, para poder decírselo al usuario. */
    data class ExportResult(val publicLocation: String?, val inboxFile: File?, val error: String?)

    fun fileName(stamp: String): String = "$NAME_PREFIX$stamp.json"

    /**
     * Escribe el backup en los dos destinos. El buzón interno es el que no puede fallar; el
     * público puede fallar (sin tarjeta, sin permiso en API<=28) y eso no invalida la exportación.
     */
    fun write(context: Context, name: String, json: String): ExportResult {
        var inbox: File? = null
        var inboxError: String? = null
        try {
            val dir = File(context.getExternalFilesDir(null), DIR_INBOX).apply { mkdirs() }
            inbox = File(dir, name).apply { writeText(json) }
        } catch (e: Exception) {
            inboxError = e.message ?: "no se pudo escribir en la carpeta de la app"
        }

        val publicLocation = try {
            writePublic(context, name, json)
        } catch (e: Exception) {
            null
        }
        // Solo es un error de verdad si NO se pudo escribir en ningún sitio.
        val error = if (inbox == null && publicLocation == null) {
            inboxError ?: "no se pudo escribir el fichero"
        } else null
        return ExportResult(publicLocation, inbox, error)
    }

    /** @return una ruta legible para mostrar, o null si no se pudo escribir en Descargas. */
    private fun writePublic(context: Context, name: String, json: String): String? {
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/$DIR_PUBLIC"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29+: MediaStore, sin permisos. Sobrevive a desinstalar la app.
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, MIME)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            }
            val resolver = context.contentResolver
            val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            resolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } ?: return null
            return "$relative/$name"
        }
        // API <= 28: ruta directa. Requiere WRITE_EXTERNAL_STORAGE concedido (ver el manifest, que
        // la declara con maxSdkVersion=28); si no está, esto lanza y se queda solo el buzón interno.
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DIR_PUBLIC)
        dir.mkdirs()
        File(dir, name).writeText(json)
        return "$relative/$name"
    }

    /**
     * Backups que la app puede abrir **ahora mismo**, los más recientes primero. Se juntan las dos
     * procedencias y se descartan duplicados por nombre (una exportación escribe en las dos).
     *
     * Ojo: aquí solo entra lo que se puede leer de verdad. Listar un fichero que después falla al
     * abrirse es peor que no listarlo.
     */
    fun list(context: Context): List<Entry> =
        (inboxEntries(context) + mediaEntries(context) + publicFileEntries(context))
            // AGRUPAR, no `distinctBy`: una copia normal está en los dos sitios y las dos
            // procedencias tienen que sobrevivir hasta [delete] (ver el KDoc de [Entry.sources]).
            .groupBy { it.name }
            .map { (name, found) ->
                Entry(
                    name = name,
                    // El buzón va primero, y su `lastModified` tiene precisión de milisegundo
                    // mientras que el de MediaStore viene redondeado a segundos.
                    sizeBytes = found.first().sizeBytes,
                    lastModified = found.first().lastModified,
                    sources = found.flatMap { it.sources }
                )
            }
            .sortedByDescending { it.lastModified }

    private fun inboxEntries(context: Context): List<Entry> {
        // mkdirs() aquí a propósito: el directorio tiene que existir y ser de la APP. Si lo crea
        // `adb push` (o cualquier otro proceso) queda a nombre de ese usuario y la app se come un
        // "Permission denied" al leer dentro — comprobado en el emulador.
        val dir = File(context.getExternalFilesDir(null), DIR_INBOX).apply { mkdirs() }
        // Mismo filtro por nombre que `mediaEntries`: solo lo que escribió esta app. Aceptar
        // cualquier *.json convertía un fichero suelto dejado ahí en una "copia restaurable".
        val files = dir.listFiles { f: File ->
            f.isFile && f.name.startsWith(NAME_PREFIX) && f.name.endsWith(".json")
        } ?: return emptyList()
        return files.map {
            Entry(it.name, it.length(), it.lastModified(), listOf(Entry.Source.Local(it)))
        }
    }

    /**
     * Exportaciones anteriores que siguen en Descargas y que este paquete puede volver a abrir.
     *
     * MediaStore atribuye cada fichero a un `owner_package_name`, así que la consulta sin permisos
     * devuelve justo lo que escribió `com.animeav1` — y el nombre de paquete no cambia al
     * reinstalar. Si la atribución sobrevive a la desinstalación, esto recupera la copia sin que el
     * usuario tenga que mover nada; si no, la consulta sale vacía y queda el buzón. Los dos casos
     * están cubiertos, y por eso se consultan ambas fuentes en vez de apostar por una.
     */
    private fun mediaEntries(context: Context): List<Entry> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        val out = mutableListOf<Entry>()
        try {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("$NAME_PREFIX%.json"),
                null
            )?.use { c ->
                val idCol   = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                while (c.moveToNext()) {
                    val uri = Uri.withAppendedPath(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(idCol).toString()
                    )
                    out.add(Entry(
                        name = c.getString(nameCol),
                        sizeBytes = c.getLong(sizeCol),
                        // DATE_MODIFIED va en segundos, no en milisegundos.
                        lastModified = c.getLong(dateCol) * 1000L,
                        sources = listOf(Entry.Source.Media(uri))
                    ))
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return out
    }

    /**
     * Lee la copia. Prueba las procedencias en orden y se queda con la primera que responda: el
     * buzón primero, que es un fichero normal y no pasa por ningún ContentProvider.
     */
    /**
     * La copia de Descargas en API ≤ 28, que va por **ruta directa** y no por MediaStore.
     *
     * ⚠️ Sin esto, por debajo de Q ninguna [Entry] tenía nunca `inDownloads`, así que la pantalla
     * etiquetaba como "solo en la app · se pierde al desinstalar" el único respaldo que sobrevive a
     * desinstalar — y el modal de borrado prometía no tocar Descargas mientras [delete] lo borraba.
     *
     * ⚠️ Va detrás del permiso ([canAccessPublicDir]): sin él no se puede ni leer esa carpeta, y
     * listar a ciegas llevaría a afirmar cosas que no se pueden comprobar. Lo que no se lista
     * tampoco se borra (ver [delete]).
     *
     * ⚠️ Ya NO es solo cosa de API ≤ 28: desde API 30, con acceso a todos los archivos concedido,
     * este es el ÚNICO camino que ve las copias de instalaciones anteriores — MediaStore solo
     * devuelve las de esta.
     */
    private fun publicFileEntries(context: Context): List<Entry> {
        if (!canAccessPublicDir(context)) return emptyList()
        @Suppress("DEPRECATION")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DIR_PUBLIC
        )
        val files = dir.listFiles { f: File ->
            f.isFile && f.name.startsWith(NAME_PREFIX) && f.name.endsWith(".json")
        } ?: return emptyList()
        return files.map {
            Entry(it.name, it.length(), it.lastModified(), listOf(Entry.Source.Public(it)))
        }
    }

    /**
     * Si el proceso puede tocar la carpeta pública de Descargas por ruta.
     *
     * Solo aplica a API ≤ 28 (en Q+ manda MediaStore y no hace falta permiso). En 21-22 el permiso
     * se concede al instalar; de 23 a 28 es de ejecución, y `BackupActivity` ya lo pide antes de
     * exportar.
     */
    private fun canAccessPublicDir(context: Context): Boolean = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M -> true
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P ->
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        else -> false   // API 29: ni permiso legacy ni "todos los archivos"
    }

    /** ¿Se ven también las copias que dejó otra instalación? Ver [canAccessPublicDir]. */
    fun seesForeignBackups(context: Context): Boolean = canAccessPublicDir(context)

    /**
     * Pantalla donde se concede el acceso a todos los archivos, o null si en este aparato no aplica
     * (por debajo de API 30 se resuelve con el permiso normal de almacenamiento).
     *
     * ⚠️ Con `package:` en los datos: el intent sin datos abre la lista de TODAS las apps, que con
     * un mando es buscarse a uno mismo en una lista larga — y en esta imagen de TV la variante sin
     * datos **no la resuelve nadie** (comprobado), así que ni se abriría.
     */
    fun allFilesAccessIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
    }

    fun read(context: Context, entry: Entry): String? {
        for (source in entry.sources.sortedBy { it !is Entry.Source.Local }) {
            val text = try {
                when (source) {
                    is Entry.Source.Local  -> source.file.readText()
                    is Entry.Source.Public -> source.file.readText()
                    is Entry.Source.Media  -> context.contentResolver
                        .openInputStream(source.uri)?.use { it.readBytes().decodeToString() }
                }
            } catch (e: Exception) {
                null
            }
            if (text != null) return text
        }
        return null
    }

    /**
     * Qué quedó en pie tras [delete]. Cada campo significa "en ese destino ya NO hay una copia con
     * ese nombre" — se comprueba por EXISTENCIA, no por el valor que devuelve `File.delete()`:
     * borrar algo que ya no estaba no es un fallo, y lo que le importa al usuario es el estado
     * final.
     *
     * ⚠️ No es un `Boolean` a propósito. Los dos destinos son independientes y no fallan juntos, y
     * el desenlace interesante es el del medio: se fue una mitad y la otra sigue viva. Con un
     * booleano la pantalla diría "Copia borrada" y acto seguido la fila reaparecería en el
     * refresco, que es peor que no haber borrado nada.
     */
    data class DeleteResult(val inbox: Boolean, val downloads: Boolean) {
        val isComplete: Boolean get() = inbox && downloads
        val isPartial:  Boolean get() = inbox != downloads
    }

    /**
     * Borra la copia llamada [name] de **las dos** procedencias.
     *
     * ⚠️ La unidad de borrado es el **nombre**, no una `Source` suelta: ver [Entry.sources]. Se
     * vuelve a listar aquí dentro en vez de fiarse de la [Entry] que traiga la UI, porque entre que
     * se pintó la lista y el usuario confirmó pueden haber cambiado los ficheros (`onResume`
     * refresca, y el buzón lo puede tocar `adb push`).
     *
     * Lo que hay detrás de cada destino:
     * - **Buzón**: `unlink(2)` mira los permisos del DIRECTORIO, no los del fichero, y aquí no hay
     *   sticky bit — así que un fichero dejado con `adb push` (owner `shell`) también se borra,
     *   siempre que el directorio lo haya creado la app, que es justo lo que garantiza el `mkdirs()`
     *   de [inboxEntries]. `File.delete()` no lanza en Android: un EACCES sale como `false`.
     * - **Descargas, API 29+**: `ContentResolver.delete` de un fichero propio funciona sin permisos
     *   y se lleva la fila **y** el fichero del disco. Se borra por las Uris que [mediaEntries] ya
     *   lista, así que es imposible tocar algo que la pantalla no enseña. Un fichero de otra
     *   instalación no aparece en esa consulta (scoped storage lo esconde), y por tanto ni se lista
     *   ni se borra: no hay forma de llegar a él desde aquí, y pedir consentimiento con
     *   `MediaStore.createDeleteRequest` para algo que el usuario no ve no tendría sentido.
     * - **Descargas, API ≤ 28**: ruta directa. ⚠️ Ese `unlink` necesita `WRITE_EXTERNAL_STORAGE`
     *   concedido (el manifest lo declara con `maxSdkVersion=28`); sin él devuelve `false` en
     *   silencio y el resultado lo dirá. Se ataca **por nombre** aunque esa copia no venga en
     *   ninguna [Entry]: por debajo de Q [mediaEntries] no lista nada, así que si no se borrara
     *   aquí quedaría en el disco para siempre y fuera del alcance de la app.
     *
     * ⚠️ Hace E/S de disco y de ContentProvider: llamar desde `Dispatchers.IO`.
     */
    fun delete(context: Context, name: String): DeleteResult {
        // El nombre llega desde la UI y acaba en `File(dir, name)`: un "../../databases/animeav1.db"
        // saldría del buzón. Solo se acepta lo que escribe esta app.
        // Mismo criterio que el filtro de listado, más la garantía de que es un nombre suelto:
        // `name` acaba en `File(dir, name)`, y un "../../databases/animeav1.db" saldría del buzón.
        // `File(name).name == name` lo cubre sin dejar callejones (un `..` no se puede borrar nunca,
        // así que tampoco debe poder listarse: por eso el mismo filtro está en las dos puntas).
        if (!name.startsWith(NAME_PREFIX) || !name.endsWith(".json") || File(name).name != name) {
            return DeleteResult(inbox = false, downloads = false)
        }

        val inbox = deleteFromInbox(context, name)
        val downloads = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Los dos caminos: MediaStore solo alcanza lo que escribió ESTA instalación, y con
            // acceso a todos los archivos también se ven —y se borran— las de las anteriores, que
            // MediaStore ni devuelve. Cada mitad verifica volviendo a mirar, así que un `&&` de dos
            // "ya no queda nada con ese nombre" sigue significando eso.
            val byMedia = deleteFromMediaStore(context, name)
            val byPath  = if (canAccessPublicDir(context)) deleteFromPublicPath(context, name) else true
            byMedia && byPath
        } else deleteFromPublicPath(context, name)
        return DeleteResult(inbox, downloads)
    }

    private fun deleteFromInbox(context: Context, name: String): Boolean {
        // ⚠️ `File(null, DIR_INBOX)` NO lanza: con padre nulo se resuelve contra el directorio de
        // trabajo del proceso, o sea que se estaría borrando en una ruta inventada.
        // getExternalFilesDir devuelve null con el almacenamiento no montado.
        val dir = context.getExternalFilesDir(null) ?: return false
        val file = File(File(dir, DIR_INBOX), name)
        return runCatching {
            if (file.exists()) file.delete()
            !file.exists()
        }.getOrDefault(false)
    }

    private fun deleteFromMediaStore(context: Context, name: String): Boolean = runCatching {
        for (entry in mediaEntries(context).filter { it.name == name }) {
            for (media in entry.sources.filterIsInstance<Entry.Source.Media>()) {
                // selection/args a null: con una Uri de id, MediaProvider no admite `where`.
                runCatching { context.contentResolver.delete(media.uri, null, null) }
            }
        }
        // ⚠️ El desenlace se decide volviendo a MIRAR, no por el valor que devolvió `delete`. Es lo
        // único que puede afirmar "ahí ya no queda nada con ese nombre", que es lo que la pantalla
        // va a decirle al usuario.
        mediaEntries(context).none { it.name == name }
    }.getOrDefault(false)

    /**
     * ⚠️ Sin permiso NO se afirma nada: se devuelve `false`. Antes esta rama deducía el desenlace de
     * un `exists()` que sin acceso a `/sdcard` da `false` de todas formas, así que anunciaba "Copia
     * borrada" con el fichero intacto — justo lo contrario de lo que prometía su documentación.
     * Y solo se borra lo que [publicFileEntries] ha listado, que va detrás del mismo permiso: lo que
     * la pantalla no enseña, la pantalla no lo borra.
     */
    private fun deleteFromPublicPath(context: Context, name: String): Boolean {
        if (!canAccessPublicDir(context)) return false
        return runCatching {
            for (entry in publicFileEntries(context).filter { it.name == name }) {
                for (source in entry.sources.filterIsInstance<Entry.Source.Public>()) {
                    source.file.delete()
                }
            }
            publicFileEntries(context).none { it.name == name }
        }.getOrDefault(false)
    }

    /** Ruta del buzón, para poder decirle al usuario dónde dejar un fichero a importar. */
    fun inboxPath(context: Context): String =
        File(context.getExternalFilesDir(null), DIR_INBOX).absolutePath
}
