package com.animeav1.ui.browse

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.animeav1.R

data class FilterChip(
    val label: String,
    val currentValue: String,
    val isActive: Boolean = false
)

class FilterChipAdapter(
    initialChips: List<FilterChip>,
    private val onClick: (index: Int) -> Unit
) : RecyclerView.Adapter<FilterChipAdapter.VH>() {

    private val chips: MutableList<FilterChip> = initialChips.toMutableList()

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.chip_label)
        val value: TextView = view.findViewById(R.id.chip_value)

        init {
            view.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(pos)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_filter_chip, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val chip = chips[position]
        holder.label.text = chip.label
        holder.value.text = chip.currentValue
        holder.itemView.isSelected = chip.isActive
    }

    override fun getItemCount() = chips.size

    /** Replace all chips in-place without recreating views */
    fun updateChips(newChips: List<FilterChip>) {
        chips.clear()
        chips.addAll(newChips)
        notifyDataSetChanged()
    }
}
