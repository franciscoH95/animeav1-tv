package com.animeav1.ui.series

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.animeav1.R
import com.animeav1.data.model.Relation

class RelationAdapter(
    private val items: List<Relation>,
    private val onClick: (Relation) -> Unit
) : RecyclerView.Adapter<RelationAdapter.VH>() {

    inner class VH(val root: View) : RecyclerView.ViewHolder(root) {
        val image: ImageView  = root.findViewById(R.id.rel_image)
        val type: TextView    = root.findViewById(R.id.rel_type)
        val title: TextView   = root.findViewById(R.id.rel_title)
        val year: TextView    = root.findViewById(R.id.rel_year)

        init {
            root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(items[pos])
            }
            root.setOnFocusChangeListener { v, focused ->
                v.animate().scaleX(if (focused) 1.10f else 1f)
                           .scaleY(if (focused) 1.10f else 1f)
                           .translationZ(if (focused) 8f else 0f)
                           .setDuration(120).start()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_relation_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val rel = items[position]
        holder.title.text = rel.title
        holder.type.text  = rel.typeName
        // El año es un distintivo sobre el póster: si no viene, se esconde en vez de dejar una
        // pastilla vacía flotando sobre el arte.
        holder.year.text = rel.startDate
        holder.year.visibility = if (rel.startDate.isBlank()) View.GONE else View.VISIBLE
        holder.image.load(rel.coverUrl) {
            crossfade(true)
            placeholder(R.drawable.placeholder_poster)
            error(R.drawable.placeholder_poster)
            transformations(RoundedCornersTransformation(8f))
        }
    }

    override fun getItemCount() = items.size
}
