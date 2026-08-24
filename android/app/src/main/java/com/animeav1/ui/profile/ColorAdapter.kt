package com.animeav1.ui.profile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.animeav1.R
import com.animeav1.data.local.Profile

/** Selector de color del editor de perfil: la paleta de [ProfileAvatars] en una fila. */
internal class ColorAdapter(private val onPick: (Int) -> Unit)
    : RecyclerView.Adapter<ColorAdapter.VH>() {

    private var selected = 0

    fun setSelected(index: Int) {
        val old = selected
        selected = ProfileAvatars.normalize(index)
        notifyItemChanged(old)
        notifyItemChanged(selected)
    }

    override fun getItemCount() = Profile.COLOR_COUNT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_profile_color, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.swatch.background?.mutate()?.setColorFilter(
            ProfileAvatars.colorAt(position), android.graphics.PorterDuff.Mode.SRC_IN
        )
        // El check marca lo elegido; el foco es un estado distinto (lo pinta card_focus_bg), así que
        // se ven las dos cosas a la vez sin confundirlas.
        holder.swatch.text = if (position == selected) "✓" else ""
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val swatch: TextView = v.findViewById(R.id.color_swatch)

        init {
            v.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) { setSelected(pos); onPick(pos) }
            }
        }
    }
}
