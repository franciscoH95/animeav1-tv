package com.animeav1.ui.backup

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.animeav1.R
import com.animeav1.data.BackupCodec
import com.animeav1.data.BackupStore
import com.animeav1.data.LocalRepository
import com.animeav1.data.ProfileManager
import com.animeav1.data.local.AppDatabase
import com.animeav1.ui.rescuingFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Exportar / importar el estado local (listas, vistos, puntos de reanudación) a un fichero JSON.
 *
 * Pantalla aparte y no una pestaña: añadir un tab obliga a tocar `tabTags`, `lastFocus[]`,
 * `isTabButton`, `activeTabButton`, `newFragment` y `updateTabStyle` a la vez, y esto no necesita
 * nada de la maquinaria de show/hide de MainActivity.
 *
 * Sobre por qué no hay selector de ficheros (Android TV no tiene DocumentsUI) y qué se puede volver
 * a leer después de reinstalar, ver el KDoc de [BackupStore].
 */
class BackupActivity : FragmentActivity() {

    private val local by lazy { LocalRepository(AppDatabase.get(applicationContext)) }

    private lateinit var btnExport: Button
    private lateinit var exportStatus: TextView
    private lateinit var importHint: TextView
    private lateinit var list: RecyclerView
    private lateinit var listEmpty: TextView
    private lateinit var adapter: BackupFileAdapter

    private lateinit var content: ViewGroup
    private lateinit var listHint: TextView
    private lateinit var confirmOverlay: View
    private lateinit var confirmTitle: TextView
    private lateinit var confirmDetail: TextView
    private lateinit var btnMerge: Button
    private lateinit var btnReplace: Button
    private lateinit var btnDelete: Button
    private lateinit var btnCancelImport: Button

    /** Fichero elegido, esperando a que se confirme la acción del modal. */
    private var pending: BackupStore.Entry? = null

    /** Dónde estaba el foco al abrir el modal, para devolverlo al cancelar (patrón `ExitConfirm`). */
    private var focusBefore: View? = null

    /** Evita lanzar dos exportaciones o dos importaciones a la vez con pulsaciones rápidas. */
    private var busy = false

    /** El permiso de escritura pública (API ≤28) se pide una sola vez por visita a la pantalla. */
    private var writeAsked = false
    private lateinit var btnAllFiles: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup)

        btnExport    = findViewById(R.id.btn_export)
        exportStatus = findViewById(R.id.export_status)
        importHint   = findViewById(R.id.import_hint)
        list         = findViewById(R.id.backup_list)
        listEmpty    = findViewById(R.id.list_empty)

        content         = findViewById(R.id.backup_content)
        listHint        = findViewById(R.id.list_hint)
        confirmOverlay  = findViewById(R.id.confirm_overlay)
        confirmTitle    = findViewById(R.id.confirm_title)
        confirmDetail   = findViewById(R.id.confirm_detail)
        btnMerge        = findViewById(R.id.btn_merge)
        btnReplace      = findViewById(R.id.btn_replace)
        btnDelete       = findViewById(R.id.btn_delete)
        btnCancelImport = findViewById(R.id.btn_cancel_import)

        adapter = BackupFileAdapter(
            onClick     = { entry -> askImportMode(entry) },
            onLongClick = { entry -> askDelete(entry) }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.setHasFixedSize(true)

        btnAllFiles = findViewById(R.id.btn_all_files)
        btnAllFiles.setOnClickListener {
            val intent = BackupStore.allFilesAccessIntent(this) ?: return@setOnClickListener
            // Puede no haber quién lo resuelva en un aparato raro: mejor un aviso que un crash.
            runCatching { startActivity(intent) }
                .onFailure { toast(getString(R.string.backup_no_settings)) }
        }

        btnExport.setOnClickListener { exportNow() }
        btnMerge.setOnClickListener { runImport(replace = false) }
        btnReplace.setOnClickListener { runImport(replace = true) }
        btnDelete.setOnClickListener { deleteNow() }
        btnCancelImport.setOnClickListener { hideConfirm() }

        btnExport.post { btnExport.requestFocus() }
        updateAccessHint()
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        // Un fichero puede haber aparecido por `adb push` mientras la pantalla estaba en segundo
        // plano, y —lo importante— aquí se vuelve justo después de conceder el acceso a todos los
        // archivos en Ajustes: sin releer, el usuario da el permiso y la lista sigue igual de vacía.
        updateAccessHint()
        refreshList()
    }

    /**
     * El aviso y el botón de permiso, según se estén viendo TODAS las copias o solo las de esta
     * instalación. Ver `BackupStore.seesForeignBackups`.
     */
    private fun updateAccessHint() {
        val full = BackupStore.seesForeignBackups(this)
        val canAsk = BackupStore.allFilesAccessIntent(this) != null
        btnAllFiles.visibility = if (!full && canAsk) View.VISIBLE else View.GONE
        importHint.text = when {
            full     -> getString(R.string.backup_hint_full_access)
            canAsk   -> getString(R.string.backup_hint_no_access)
            // Sin forma de pedirlo (API 29): queda el camino manual de dejar el fichero en el buzón.
            else     -> getString(R.string.backup_import_hint, BackupStore.inboxPath(this))
        }
    }

    // ── Exportar ───────────────────────────────────────────────────────────────────────────────

    private fun exportNow() {
        if (busy) { toast(getString(R.string.backup_busy)); return }
        // En API <= 28 la copia pública necesita permiso; sin él solo se escribe el buzón interno,
        // así que se pide antes en vez de dejar media exportación en silencio.
        // ⚠️ Se pide UNA sola vez (`writeAsked`). Sin ese flag, denegarlo dejaba un bucle: el
        // callback volvía a llamar aquí, el permiso seguía sin estar y se volvía a pedir, y con
        // "no preguntar más" el diálogo ni aparecía — la exportación nunca ocurría.
        if (!writeAsked &&
            Build.VERSION.SDK_INT in Build.VERSION_CODES.M..Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
        ) {
            writeAsked = true
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_WRITE)
            return
        }
        busy = true
        btnExport.isEnabled = false
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val result = runCatching {
                val data = local.exportAll(now)
                val json = BackupCodec.encode(data)
                withContext(Dispatchers.IO) {
                    BackupStore.write(this@BackupActivity, BackupStore.fileName(BackupFormat.fileStamp(now)), json)
                } to data.itemCount
            }
            busy = false
            btnExport.isEnabled = true
            exportStatus.visibility = View.VISIBLE
            result.onSuccess { (written, count) ->
                val where = written.publicLocation ?: BackupStore.inboxPath(this@BackupActivity)
                exportStatus.text = when {
                    written.error != null -> getString(R.string.backup_export_failed, written.error)
                    else -> getString(R.string.backup_export_ok, count, where)
                }
                refreshList()
            }.onFailure {
                exportStatus.text = getString(
                    R.string.backup_export_failed, it.message ?: "error desconocido"
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Concedido o no, se exporta: sin permiso queda solo el buzón interno, que sigue sirviendo
        // para importar en esta misma instalación, y el texto de resultado dice dónde acabó.
        if (requestCode == REQ_WRITE) exportNow()
    }

    // ── Importar ───────────────────────────────────────────────────────────────────────────────

    private fun askImportMode(entry: BackupStore.Entry) {
        if (busy) { toast(getString(R.string.backup_busy)); return }
        // Con el modal ya abierto, un clic que se colara por detrás del scrim lo reapuntaría a otro
        // fichero sin que se vea.
        if (confirmOverlay.visibility == View.VISIBLE) return
        pending = entry
        confirmTitle.setText(R.string.backup_confirm_title)
        confirmDetail.text = getString(
            R.string.backup_confirm_detail, entry.name, BackupFormat.dateTime(entry.lastModified)
        )
        // Fusionar por defecto: es la opción que no puede destruir nada.
        showModal(merge = true, delete = false, focusOn = btnMerge)
    }

    /**
     * Confirmación de borrado. Mantener pulsado ya expresa intención, así que el foco arranca en
     * "Borrar copia" —dos pulsaciones deliberadas, el mismo criterio que la confirmación de salida—
     * y BACK cancela, como en todos los modales de la app.
     */
    private fun askDelete(entry: BackupStore.Entry) {
        if (busy) { toast(getString(R.string.backup_busy)); return }
        if (confirmOverlay.visibility == View.VISIBLE) return
        pending = entry
        confirmTitle.setText(R.string.backup_delete_title)
        confirmDetail.text = getString(
            R.string.backup_delete_detail,
            entry.name,
            BackupFormat.dateTime(entry.lastModified),
            getString(whereLabel(entry))
        )
        showModal(merge = false, delete = true, focusOn = btnDelete)
    }

    /** Dónde se va a borrar de verdad. Decirlo importa: la copia de Descargas es la única que
     *  sobrevive a desinstalar la app, y borrarla no tiene vuelta atrás. */
    private fun whereLabel(entry: BackupStore.Entry): Int = when (entry.location) {
        BackupStore.Location.BOTH           -> R.string.backup_where_both
        BackupStore.Location.DOWNLOADS_ONLY -> R.string.backup_where_downloads
        BackupStore.Location.INBOX_ONLY     -> R.string.backup_where_inbox
    }

    /**
     * ⚠️ Un overlay VISIBLE no aísla nada: `FocusFinder` no sabe de oclusión, así que sin
     * `FOCUS_BLOCK_DESCENDANTS` las filas siguen alcanzables **por detrás del scrim** y un CENTRO
     * abre la acción de un fichero que no se ve. Mismo remedio que `ExitConfirm` y que el panel de
     * sinopsis de la ficha. Esto ya fallaba con la confirmación de importar; el borrado no lo hereda.
     *
     * ⚠️ Las visibilidades se fijan ANTES de pedir el foco: esconder un botón que lo tiene lo expulsa.
     */
    private fun showModal(merge: Boolean, delete: Boolean, focusOn: View) {
        focusBefore = content.findFocus()
        btnMerge.visibility   = if (merge) View.VISIBLE else View.GONE
        btnReplace.visibility = if (merge) View.VISIBLE else View.GONE
        btnDelete.visibility  = if (delete) View.VISIBLE else View.GONE
        confirmOverlay.visibility = View.VISIBLE
        content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        focusOn.post { focusOn.requestFocus() }
    }

    /**
     * ⚠️ El foco vuelve a donde estaba, no a `list.requestFocus()` pelado: con `afterDescendants`
     * eso aterriza en la PRIMERA fila, no en la que abrió el modal — y si la lista se quedó vacía
     * (`GONE`), `requestFocus` falla en silencio sobre una vista invisible y la pantalla se queda
     * sin ningún aro. Con borrado esa ruta sí es alcanzable.
     */
    private fun hideConfirm() {
        confirmOverlay.visibility = View.GONE
        content.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        pending = null
        val previous = focusBefore
        focusBefore = null
        content.post {
            if (previous?.isShown != true || !previous.requestFocus()) btnExport.requestFocus()
        }
    }

    private fun runImport(replace: Boolean) {
        val entry = pending ?: return
        if (busy) return
        busy = true
        hideConfirm()
        lifecycleScope.launch {
            val outcome = runCatching {
                val json = withContext(Dispatchers.IO) { BackupStore.read(this@BackupActivity, entry) }
                    ?: error(getString(R.string.backup_read_failed))
                // El fichero describe a qué perfil pertenece cada fila con un `ProfileRef`, y quien
                // lo traduce a ids locales (creando los perfiles que falten) es
                // `LocalRepository.importAll`: aquí no hace falta saber nada de perfiles.
                val data = BackupCodec.decode(json)
                    ?: error(getString(R.string.backup_invalid_file))
                // ⚠️ "Sustituir" con una copia sin elementos vaciaría las tres tablas de TODOS los
                // perfiles y no restauraría nada — y el usuario lo vería como "Restaurados 0
                // elementos", con la biblioteca borrada. Nadie pide eso nunca, así que se rechaza
                // antes de tocar la base de datos. Fusionar con una copia vacía sí es inocuo.
                if (replace && data.isEmpty) error(getString(R.string.backup_replace_empty))
                local.importAll(data, replace)
                data.itemCount
            }
            busy = false
            outcome.onSuccess { count ->
                toast(getString(R.string.backup_import_ok, count))
            }.onFailure {
                toast(it.message ?: getString(R.string.backup_read_failed))
            }
        }
    }

    /**
     * Borra la copia elegida de **los dos** destinos.
     *
     * ⚠️ `focusBefore` se anula ANTES de cerrar el modal: la fila que lo abrió está a punto de
     * desaparecer, y `RecyclerView` recicla esa misma `View` para OTRA entrada — `isShown` seguiría
     * diciendo true, `requestFocus()` triunfaría y el foco acabaría sobre un fichero distinto del
     * que el usuario tenía. Aquí el foco lo decide [applyList] con la posición.
     */
    private fun deleteNow() {
        val entry = pending ?: return
        if (busy) { toast(getString(R.string.backup_busy)); return }
        busy = true
        val gone = adapter.positionOf(entry.name)
        focusBefore = null
        hideConfirm()
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    BackupStore.delete(this@BackupActivity, entry.name)
                }
                val entries = withContext(Dispatchers.IO) { BackupStore.list(this@BackupActivity) }
                // ⚠️ `gone` puede ser -1 (la copia ya no estaba en el adapter): sin este guard, el
                // `coerceIn` lo convertiría en 0 y el foco saltaría a la copia MÁS RECIENTE en vez
                // de quedarse donde estaba el usuario.
                val focusPos =
                    if (entries.isEmpty() || gone < 0) -1 else gone.coerceAtMost(entries.lastIndex)
                applyList(entries, focusPos)
                // Por toast y no en `exportStatus`: ese rótulo es el de exportar, y escribir aquí
                // pisaba el "Guardado: N elementos en…" con un mensaje de otra acción, que además
                // se quedaba pegado el resto de la sesión.
                toast(when {
                    result.isComplete -> getString(R.string.backup_delete_ok)
                    // El caso del medio es el que importa: si se dijera "borrada" a secas, la fila
                    // reaparecería en el refresco y parecería que la app no hace caso.
                    result.isPartial && result.inbox -> getString(R.string.backup_delete_partial_inbox)
                    result.isPartial -> getString(R.string.backup_delete_partial_downloads)
                    else -> getString(R.string.backup_delete_failed, entry.name)
                })
            } finally {
                // En `finally`: si algo lanza, la pantalla no puede quedarse bloqueada el resto de
                // la sesión.
                busy = false
            }
        }
    }

    private fun refreshList() {
        lifecycleScope.launch {
            applyList(withContext(Dispatchers.IO) { BackupStore.list(this@BackupActivity) })
        }
    }

    /**
     * Pinta la lista y deja el foco en su sitio. Es el ÚNICO camino que toca esas visibilidades:
     * cuando `refreshList` las aplicaba por su cuenta, un fichero que desapareciera por `adb` con la
     * pantalla en segundo plano dejaba la lista `GONE` con el foco dentro.
     *
     * ⚠️ El flip de visibilidad va dentro de `rescuingFocus` —la única implementación de la regla en
     * este proyecto— porque esconder la lista con el foco dentro lo expulsa al primer focusable de
     * la ventana, que aquí es *Exportar ahora*: un CENTRO por reflejo lanzaría una exportación.
     * ⚠️ Y la rejilla vacía **no** puede quedarse focusable: con `afterDescendants` y cero hijos, el
     * propio RecyclerView acepta el foco y no dibuja nada.
     */
    private fun applyList(entries: List<BackupStore.Entry>, focusPos: Int = -1) {
        rescuingFocus(content, { btnExport }) {
            adapter.submit(entries)
            listEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
            listHint.visibility  = if (entries.isEmpty()) View.GONE   else View.VISIBLE
            list.visibility      = if (entries.isEmpty()) View.GONE   else View.VISIBLE
            list.isFocusable     = entries.isNotEmpty()
        }
        if (focusPos in entries.indices) {
            // `post`: `notifyDataSetChanged` solo PROGRAMA el layout, así que en este instante el
            // RecyclerView aún no tiene hijos para esa posición.
            list.post {
                list.findViewHolderForAdapterPosition(focusPos)?.itemView?.requestFocus()
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // BACK con la confirmación abierta la cierra en vez de salir de la pantalla.
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK &&
            confirmOverlay.visibility == View.VISIBLE
        ) {
            hideConfirm()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private companion object {
        const val REQ_WRITE = 1
    }
}
