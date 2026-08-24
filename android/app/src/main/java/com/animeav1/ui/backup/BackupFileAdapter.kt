package com.animeav1.ui.backup

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.animeav1.R
import com.animeav1.data.BackupStore

/**
 * Ficheros de backup que la app puede abrir. Un toque abre la confirmación de importación;
 * mantener pulsado, la de borrado.
 *
 * `internal` porque [BackupStore] lo es: la decisión de dónde vive un backup no debe filtrarse
 * fuera del módulo.
 */
internal class BackupFileAdapter(
    private val onClick: (BackupStore.Entry) -> Unit,
    /**
     * Mantener pulsado = borrar. Mismo gesto que la rejilla de episodios de la ficha y las tarjetas
     * de *Continuar viendo*: es donde esta app esconde la acción secundaria de una tarjeta. La
     * pulsación corta sigue siendo restaurar, y ninguno de los dos gestos toca el disco por sí solo
     * —los dos abren una confirmación—, así que un mantenido accidental cuesta un BACK.
     */
    private val onLongClick: (BackupStore.Entry) -> Unit
) : RecyclerView.Adapter<BackupFileAdapter.VH>() {

    private val items = mutableListOf<BackupStore.Entry>()

    fun submit(list: List<BackupStore.Entry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /** Posición de una copia por NOMBRE, que es lo único estable entre refrescos. -1 si ya no está. */
    fun positionOf(name: String): Int = items.indexOfFirst { it.name == name }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_backup_file, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        val ctx = holder.itemView.context
        holder.name.text = entry.name
        holder.meta.text = ctx.getString(
            R.string.backup_file_meta,
            BackupFormat.dateTime(entry.lastModified),
            BackupFormat.size(entry.sizeBytes),
            ctx.getString(locationLabel(entry))
        )
        // ⚠️ Reset explícito de la animación de foco: aquí se rebindean vistas RECICLADAS, y una que
        // venía enfocada conserva su escala y su lift de Z. Sin esto, tras borrar una fila queda
        // otra "levantada" que no tiene el foco. El `cancel()` es parte del arreglo: una animación
        // en vuelo volvería a poner los valores viejos justo después. El guard evita desescalar la
        // fila que sí tiene el foco.
        if (!holder.itemView.hasFocus()) {
            holder.itemView.animate().cancel()
            holder.itemView.scaleX = 1f
            holder.itemView.scaleY = 1f
            holder.itemView.translationZ = 0f
        }
    }

    /**
     * Dónde vive esta copia. Es la diferencia entre un respaldo de verdad y uno que se evapora: la
     * de Descargas sobrevive a desinstalar la app, la del buzón no. Antes la fila no lo decía y las
     * dos se veían igual.
     *
     * La DECISIÓN la toma `Entry.location`; aquí solo se elige el texto. Repetir el `when` en cada
     * capa es como se acaba arreglando un sitio y olvidando el otro.
     */
    private fun locationLabel(entry: BackupStore.Entry): Int = when (entry.location) {
        BackupStore.Location.BOTH           -> R.string.backup_loc_both
        BackupStore.Location.DOWNLOADS_ONLY -> R.string.backup_loc_downloads
        BackupStore.Location.INBOX_ONLY     -> R.string.backup_loc_inbox
    }

    override fun getItemCount() = items.size

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.backup_name)
        val meta: TextView = v.findViewById(R.id.backup_meta)

        init {
            // Misma animación de foco que los adapters de tarjeta: escala + lift de Z (sin el
            // translationZ la fila crecida se dibuja por debajo de sus vecinas).
            v.setOnFocusChangeListener { view, hasFocus ->
                view.animate()
                    .scaleX(if (hasFocus) 1.02f else 1f)
                    .scaleY(if (hasFocus) 1.02f else 1f)
                    .translationZ(if (hasFocus) 8f else 0f)
                    .setDuration(120)
                    .start()
            }
            v.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(items[pos])
            }
            v.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) { onLongClick(items[pos]); true } else false
            }
        }
    }
}
