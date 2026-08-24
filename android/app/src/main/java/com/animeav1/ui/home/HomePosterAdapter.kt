package com.animeav1.ui.home

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
import com.animeav1.data.local.FavoriteSeries

/** Fixed-width poster cards for the Home horizontal rows (e.g. "Mi lista · Viendo"). */
class HomePosterAdapter(
    private val onClick: (FavoriteSeries) -> Unit
) : ListAdapter<FavoriteSeries, HomePosterAdapter.VH>(DIFF) {

    inner class VH(val root: View) : RecyclerView.ViewHolder(root) {
        val poster: ImageView = root.findViewById(R.id.home_poster)
        val title:  TextView  = root.findViewById(R.id.home_title)

        init {
            root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(getItem(pos))
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
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_home_poster, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.title.text = item.title
        holder.poster.load(item.coverUrl) {
            crossfade(true)
            placeholder(R.drawable.placeholder_poster)
            error(R.drawable.placeholder_poster)
            transformations(RoundedCornersTransformation(8f))
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<FavoriteSeries>() {
            override fun areItemsTheSame(a: FavoriteSeries, b: FavoriteSeries) = a.slug == b.slug
            override fun areContentsTheSame(a: FavoriteSeries, b: FavoriteSeries) = a == b
        }
    }
}
