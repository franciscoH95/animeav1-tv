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
    /** Subcarpeta de Descargas donde la app escribe sus copias. */
    const val DIR_PUBLIC = "AnimeAV1"
    private const val DIR_INBOX  = "backups"
    private const val MIME       = "application/json"
    /** Prefijo de los ficheros que escribe esta app; también es el filtro al listarlos. */
    private const val NAME_PREFIX = "animeav1-"

    /** Subcarpetas de Descargas que se recorren buscando copias. 2 = `Download/loquesea/…`. */
    private const val SCAN_MAX_DEPTH = 2

    /** Tope de `.json` examinados en ese recorrido: la pantalla no puede tardar en abrir. */
    private const val SCAN_MAX_FILES = 400

    /** Por encima de esto no se abre para mirar si es una copia: la nuestra son unos pocos KB. */
    private const val MAX_BACKUP_BYTES = 8L * 1024 * 1024

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

        /**
         * La escribió esta app (nombre `animeav1-…json`).
         *
         * Lo de fuera se puede **importar pero no borrar**: la app no destruye ficheros que no ha
         * creado, y con nombres libres "borrar por nombre" podría llevarse el que no era.
         */
        val isOurs: Boolean get() = name.startsWith(NAME_PREFIX) && name.endsWith(".json")

        /**
         * Carpeta donde vive, relativa a Descargas ("AnimeAV1", "Telegram", "" si está suelta en la
         * raíz), o null si solo se conoce por MediaStore. Es lo que le dice al usuario CUÁL de las
         * dos copias con el mismo nombre está mirando.
         */
        val downloadsFolder: String? get() =
            sources.filterIsInstance<Source.Public>().firstOrNull()?.file?.parentFile?.let { dir ->
                @Suppress("DEPRECATION")
                val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (dir.absolutePath == root.absolutePath) "" else dir.name
            }

        sealed class Source {
            /** Buzón de la app (`getExternalFilesDir`). */
            data class Local(val file: File) : Source()
            /** Descargas por MediaStore (API 29+). */
            data class Media(val uri: Uri) : Source()
            /** Descargas por ruta directa: API ≤ 28, y API 30+ con acceso a todos los archivos. */
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
            // ⚠️ Por nombre Y TAMAÑO. Agrupar solo por nombre unía la copia del buzón con su gemela
            // de Descargas —que es lo que se busca—, pero desde que se listan ficheros con nombre
            // libre, dos copias distintas de dos carpetas pueden llamarse igual y se fundirían en
            // una fila que al restaurar abriría cualquiera de las dos.
            .groupBy { it.name to it.sizeBytes }
            .map { (key, found) ->
                Entry(
                    name = key.first,
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
        // Nombre nuestro o contenido nuestro (ver [looksLikeBackup]): al buzón llegan ficheros
        // traídos a mano, y exigir el nombre exacto dejaba fuera una copia perfectamente válida que
        // alguien había renombrado. Aceptar cualquier `.json` sí sería mentir, y por eso se mira
        // dentro.
        val files = dir.listFiles { f: File ->
            f.isFile && f.name.endsWith(".json", ignoreCase = true) && looksLikeBackup(f)
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
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return scanForBackups(downloads).map {
            Entry(it.name, it.length(), it.lastModified(), listOf(Entry.Source.Public(it)))
        }
    }

    /**
     * Busca copias en **toda** la carpeta de Descargas, no solo en [DIR_PUBLIC].
     *
     * ⚠️ Mirar únicamente `Download/AnimeAV1/` daba por inexistente cualquier copia que el usuario
     * hubiera dejado suelta en Descargas —bajada de un correo, copiada desde un USB o traída de otro
     * aparato— y en una TV **no hay explorador de ficheros** con el que ir a buscarla: la copia
     * estaba ahí y no había forma de llegar a ella.
     *
     * Se recorre hasta [SCAN_MAX_DEPTH] niveles y se examinan como mucho [SCAN_MAX_FILES] ficheros:
     * una carpeta de Descargas puede tener miles y esto corre al abrir la pantalla.
     */
    private fun scanForBackups(root: File): List<File> {
        val out = mutableListOf<File>()
        var examined = 0

        fun walk(dir: File, depth: Int) {
            if (depth > SCAN_MAX_DEPTH || examined >= SCAN_MAX_FILES) return
            val children = dir.listFiles() ?: return
            for (f in children) {
                if (examined >= SCAN_MAX_FILES) return
                if (f.isDirectory) walk(f, depth + 1)
                else if (f.name.endsWith(".json", ignoreCase = true)) {
                    examined++
                    if (looksLikeBackup(f)) out += f
                }
            }
        }

        walk(root, 0)
        return out
    }

    /**
     * ¿Este `.json` es una copia de ESTA app?
     *
     * Si el nombre es el nuestro se acepta sin abrirlo. Si no, se miran los primeros bytes: el
     * formato empieza por `{"format":N,"app":"com.animeav1",…}`, así que la marca está en la
     * cabecera y no hace falta leer —ni parsear— el fichero entero.
     *
     * ⚠️ Se comprueba el CONTENIDO y no solo el nombre porque el usuario puede haber renombrado la
     * copia, y al revés: un `.json` cualquiera que alguien dejó en Descargas no es una copia y
     * ofrecerlo para restaurar sería mentir. El tope de tamaño evita abrir ficheros enormes.
     */
    private fun looksLikeBackup(file: File): Boolean {
        if (file.name.startsWith(NAME_PREFIX) && file.name.endsWith(".json")) return true
        if (file.length() !in 1..MAX_BACKUP_BYTES) return false
        return runCatching {
            file.inputStream().use { input ->
                val head = ByteArray(512)
                val n = input.read(head)
                n > 0 && String(head, 0, n, Charsets.UTF_8).contains(BackupCodec.APP_ID)
            }
        }.getOrDefault(false)
    }

    /**
     * Si el proceso puede tocar la carpeta pública de Descargas **por ruta**, que es la única forma
     * de ver las copias que no escribió esta instalación (MediaStore solo devuelve las suyas).
     *
     * - API ≤ 22: el permiso se concede al instalar.
     * - API 23-28: `WRITE_EXTERNAL_STORAGE` de ejecución, que `BackupActivity` ya pide para exportar.
     * - API 29: no hay forma; se queda con las suyas.
     * - API 30+: `MANAGE_EXTERNAL_STORAGE`, que se concede en Ajustes (ver [allFilesAccessIntent]).
     */
    private fun canAccessPublicDir(context: Context): Boolean = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M -> true
        // ⚠️ Hasta Q incluido. En Android 10 el permiso clásico SÍ sirve gracias a
        // `requestLegacyExternalStorage`, y es el único camino que hay: el acceso a "todos los
        // archivos" no existe hasta API 30. Dejando fuera a Q, en un Android TV 10 la app no pedía
        // nada y las copias de Descargas eran invisibles.
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q ->
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        else -> Environment.isExternalStorageManager()
    }

    /** El permiso clásico de almacenamiento es el que hace falta (API ≤ 29). */
    fun needsLegacyStoragePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT in Build.VERSION_CODES.M..Build.VERSION_CODES.Q &&
            !canAccessPublicDir(context)

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
        // ⚠️ Solo se borra lo que escribió la app. Desde que se listan también copias con OTRO
        // nombre —las que el usuario dejó sueltas en Descargas—, borrar "por nombre" podría
        // llevarse un fichero que la app no creó y que quizá esté en dos carpetas a la vez. Lo
        // ajeno se importa, no se borra: quien lo dejó ahí sabe dónde está.
        if (!name.startsWith(NAME_PREFIX)) return false
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
