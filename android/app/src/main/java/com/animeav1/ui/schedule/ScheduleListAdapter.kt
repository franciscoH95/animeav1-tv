package com.animeav1.ui.schedule

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
import com.animeav1.data.model.ScheduleItem
import com.animeav1.ui.GRID_COLUMNS

class ScheduleListAdapter(
    private val onClick: (ScheduleItem) -> Unit
) : ListAdapter<ScheduleItem, ScheduleListAdapter.VH>(DIFF) {

    /** Id of the selected day button: UP from the grid's top row lands there, not on Monday. */
    var upFocusId: Int = View.NO_ID

    inner class VH(val root: View) : RecyclerView.ViewHolder(root) {
        val image:    ImageView = root.findViewById(R.id.sched_image)
        val title:    TextView  = root.findViewById(R.id.sched_title)
        val category: TextView  = root.findViewById(R.id.sched_category)
        val episode:  TextView  = root.findViewById(R.id.sched_episode)
        val date:     TextView  = root.findViewById(R.id.sched_date)

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
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_schedule_card, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        // Only the top row jumps straight to the selected day; lower rows navigate spatially.
        holder.root.nextFocusUpId = if (position < COLUMNS) upFocusId else View.NO_ID
        holder.title.text = item.title

        if (item.latestEpisodeNumber > 0) {
            holder.episode.text = "▶ Ep ${item.latestEpisodeNumber}"
            holder.episode.visibility = View.VISIBLE
        } else {
            holder.episode.visibility = View.GONE
        }

        if (item.category.isNotBlank()) {
            holder.category.text = item.category
            holder.category.visibility = View.VISIBLE
        } else {
            holder.category.visibility = View.GONE
        }

        if (item.latestEpisodeDate.isNotBlank()) {
            // Relativa: "hoy" / "ayer" / "hace 3 días", y fecha corta más allá de la semana.
            holder.date.text = RelativeDate.format(holder.date.context, item.latestEpisodeDate)
            holder.date.visibility = View.VISIBLE
        } else {
            holder.date.visibility = View.GONE
        }

        holder.image.load(item.coverUrl) {
            crossfade(true)
            placeholder(R.drawable.placeholder_poster)
            error(R.drawable.placeholder_poster)
            transformations(RoundedCornersTransformation(8f))
        }
    }

    companion object {
        const val COLUMNS = GRID_COLUMNS
        val DIFF = object : DiffUtil.ItemCallback<ScheduleItem>() {
            override fun areItemsTheSame(a: ScheduleItem, b: ScheduleItem) = a.id == b.id
            override fun areContentsTheSame(a: ScheduleItem, b: ScheduleItem) = a == b
        }
    }
}
