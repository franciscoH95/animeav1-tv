package com.animeav1.ui.mylist

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

class MyListAdapter(
    private val onClick: (FavoriteSeries) -> Unit
) : ListAdapter<FavoriteSeries, MyListAdapter.VH>(DIFF) {

    private var watchedCounts: Map<String, Int> = emptyMap()

    fun updateWatchedCounts(counts: Map<String, Int>) {
        watchedCounts = counts
        // Only the badge changed — partial bind so covers are NOT re-loaded (no crossfade flicker).
        notifyItemRangeChanged(0, itemCount, WATCHED_PAYLOAD)
    }

    inner class VH(val root: View) : RecyclerView.ViewHolder(root) {
        val cover: ImageView = root.findViewById(R.id.mylist_cover)
        val title: TextView  = root.findViewById(R.id.mylist_title)
        val meta:  TextView  = root.findViewById(R.id.mylist_meta)
        val badge: TextView  = root.findViewById(R.id.mylist_watched_badge)
        val newBadge: TextView = root.findViewById(R.id.mylist_new_badge)

        init {
            root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(getItem(pos))
            }
            root.setOnFocusChangeListener { v, focused ->
                v.animate()
                    .scaleX(if (focused) 1.10f else 1f)
                    .scaleY(if (focused) 1.10f else 1f)
                    .translationZ(if (focused) 8f else 0f)
                    .setDuration(120).start()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_mylist_card, parent, false))

    /** Partial bind: when only the watched count changed, touch just the badge. */
    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(WATCHED_PAYLOAD)) {
            bindBadge(holder, getItem(position))
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.title.text = item.title
        holder.cover.load(item.coverUrl) {
            crossfade(true)
            placeholder(R.drawable.placeholder_poster)
            error(R.drawable.placeholder_poster)
            transformations(RoundedCornersTransformation(8f))
        }

        // Year · status · type line
        val parts = buildList {
            if (item.year.isNotBlank()) add(item.year)
            if (item.statusName.isNotBlank()) add(item.statusName)
            if (item.category.isNotBlank()) add(item.category)
        }
        if (parts.isNotEmpty()) {
            holder.meta.text = parts.joinToString(" · ")
            holder.meta.visibility = View.VISIBLE
        } else {
            holder.meta.visibility = View.GONE
        }

        bindBadge(holder, item)
    }

    private fun bindBadge(holder: VH, item: FavoriteSeries) {
        val count = watchedCounts[item.slug] ?: 0
        val total = item.totalEpisodes
        if (count > 0) {
            holder.badge.text = if (total > 0) "▶ $count/$total" else "▶ $count ep"
            holder.badge.visibility = View.VISIBLE
        } else {
            holder.badge.visibility = View.GONE
        }
        // "NUEVO": airing series (status 2) you're watching that have unwatched episodes.
        val hasNew = item.status == 2 && count in 1 until total
        holder.newBadge.visibility = if (hasNew) View.VISIBLE else View.GONE
    }

    companion object {
        private val WATCHED_PAYLOAD = Any()

        val DIFF = object : DiffUtil.ItemCallback<FavoriteSeries>() {
            override fun areItemsTheSame(a: FavoriteSeries, b: FavoriteSeries) = a.slug == b.slug
            override fun areContentsTheSame(a: FavoriteSeries, b: FavoriteSeries) = a == b
        }
    }
}
