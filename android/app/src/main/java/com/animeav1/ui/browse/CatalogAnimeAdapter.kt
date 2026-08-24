package com.animeav1.ui.browse

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.animeav1.R
import com.animeav1.data.model.Anime

class CatalogAnimeAdapter(
    private val onClick: (Anime) -> Unit
) : ListAdapter<Anime, CatalogAnimeAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Anime>() {
            override fun areItemsTheSame(a: Anime, b: Anime) = a.id == b.id
            override fun areContentsTheSame(a: Anime, b: Anime) = a == b
        }
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView = view.findViewById(R.id.anime_cover)
        val title: TextView  = view.findViewById(R.id.anime_title)
        val type:  TextView  = view.findViewById(R.id.anime_type)

        init {
            view.isFocusable = true
            view.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(getItem(pos))
            }
            view.setOnFocusChangeListener { v, hasFocus ->
                v.animate()
                    .scaleX(if (hasFocus) 1.10f else 1f)
                    .scaleY(if (hasFocus) 1.10f else 1f)
                    .translationZ(if (hasFocus) 8f else 0f)
                    .setDuration(120).start()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_catalog_anime, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val anime = getItem(position)
        holder.title.text = anime.title
        if (anime.category.isNotBlank()) {
            holder.type.text = anime.category
            holder.type.visibility = View.VISIBLE
        } else {
            holder.type.visibility = View.GONE
        }
        val coverUrl = "https://cdn.animeav1.com/covers/${anime.id}.jpg"
        holder.cover.load(coverUrl) {
            crossfade(true)
            placeholder(R.color.placeholder_color)
            error(R.color.placeholder_color)
            transformations(RoundedCornersTransformation(8f))
        }
    }
}
