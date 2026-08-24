package com.animeav1.ui.browse

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DimenRes
import androidx.recyclerview.widget.RecyclerView
import com.animeav1.R

/**
 * Placeholder grid shown while the catalog loads (the container pulses; see BrowseFragment).
 *
 * @param widthRes ancho fijo de cada hueco, para las filas HORIZONTALES de Inicio.
 *   `item_skeleton_card` es `match_parent`, que en una rejilla es lo correcto pero en un
 *   `LinearLayoutManager` horizontal da un único hueco del ancho de la pantalla. 0 = se respeta el
 *   layout (rejillas).
 */
class SkeletonAdapter(
    private val count: Int = 15,
    @DimenRes private val widthRes: Int = 0
) : RecyclerView.Adapter<SkeletonAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_skeleton_card, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        if (widthRes == 0) return
        val px = holder.itemView.resources.getDimensionPixelSize(widthRes)
        holder.itemView.layoutParams = holder.itemView.layoutParams.apply { width = px }
    }

    override fun getItemCount() = count
}
