package com.animeav1.ui.series

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.animeav1.R

/**
 * Chips de bloque de episodios ("1-50", "51-100"…) para series largas.
 *
 * Reutiliza el layout del chip de filtro del catálogo para que se vean iguales; el propio proyecto ya
 * usa ese fondo para "una opción de una barra horizontal".
 */
internal class EpisodeBlockAdapter(
    /**
     * Primer y último episodio de cada bloque, **en el orden en que se muestran**.
     *
     * ⚠️ Un par y no un `IntRange`: con el orden descendente el bloque va de 1172 a 1123 y un
     * `IntRange` con `first > last` está VACÍO en Kotlin, así que ni se etiquetaba ni casaba con
     * ningún episodio.
     */
    private val blocks: List<Pair<Int, Int>>,
    /**
     * A dónde va ARRIBA desde un chip. Se dice a mano porque encima está el bloque de la portada,
     * cuya columna de texto empieza más a la derecha que la fila de chips: por geometría,
     * `FocusFinder` no tiene por qué acertar con el botón razonable.
     */
    private val upFocusId: Int = View.NO_ID,
    private val onPick: (Int) -> Unit
) : RecyclerView.Adapter<EpisodeBlockAdapter.VH>() {

    private var selected = 0

    override fun getItemCount() = blocks.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_episode_block, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (from, to) = blocks[position]
        holder.label.text = if (from == to) "$from" else "$from-$to"
        holder.itemView.isSelected = position == selected
        holder.itemView.nextFocusUpId = upFocusId
        holder.itemView.nextFocusDownId = R.id.episodes_recycler
    }

    fun select(position: Int) {
        val old = selected
        selected = position
        notifyItemChanged(old)
        notifyItemChanged(position)
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val label: TextView = v.findViewById(R.id.block_label)

        init {
            v.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) { select(pos); onPick(pos) }
            }
        }
    }
}
