package com.animeav1.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.animeav1.R
import com.animeav1.data.local.ContinueItem

class ContinueAdapter(
    private val onClick: (ContinueItem) -> Unit,
    /** Mantener pulsado: quitar la serie de la fila. Mismo gesto que la rejilla de episodios de la
     *  ficha, que es donde la app ya esconde la acción secundaria de una tarjeta. */
    private val onLongClick: (ContinueItem) -> Unit
) : ListAdapter<ContinueItem, ContinueAdapter.VH>(DIFF) {

    inner class VH(val root: View) : RecyclerView.ViewHolder(root) {
        val cover:    ImageView   = root.findViewById(R.id.continue_cover)
        val progress: ProgressBar = root.findViewById(R.id.continue_progress)
        val title:    TextView    = root.findViewById(R.id.continue_title)
        val ep:       TextView    = root.findViewById(R.id.continue_ep)

        init {
            root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(getItem(pos))
            }
            root.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onLongClick(getItem(pos))
                true
            }
            root.setOnFocusChangeListener { v, focused ->
                v.animate().scaleX(if (focused) 1.10f else 1f)
                           .scaleY(if (focused) 1.10f else 1f)
                           .translationZ(if (focused) 8f else 0f)
                           .setDuration(120).start()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_continue_card, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.cover.load(item.coverUrl) {
            crossfade(true)
            placeholder(R.drawable.placeholder_poster)
            error(R.drawable.placeholder_poster)
            transformations(RoundedCornersTransformation(8f))
        }
        holder.title.text = item.title
        holder.ep.text = "Episodio ${item.episodeNumber}"
        holder.progress.progress =
            if (item.durationMs > 0) ((item.positionMs * 100) / item.durationMs).toInt().coerceIn(0, 100) else 0
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ContinueItem>() {
            override fun areItemsTheSame(a: ContinueItem, b: ContinueItem) = a.slug == b.slug
            override fun areContentsTheSame(a: ContinueItem, b: ContinueItem) = a == b
        }
    }
}
