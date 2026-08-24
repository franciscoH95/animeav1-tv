package com.animeav1.ui.profile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.animeav1.R
import com.animeav1.data.local.Profile

/**
 * Rejilla de perfiles, con una tarjeta final de "Añadir perfil".
 *
 * El botón de añadir es un **ítem del adapter** y no una vista aparte: así entra en la misma cadena
 * de foco horizontal que los perfiles y el D-pad llega a él sin reglas de `nextFocus` a mano.
 */
internal class ProfileAdapter(
    private val onPick: (Profile) -> Unit,
    private val onEdit: (Profile) -> Unit,
    private val onAdd: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<Profile>()
    private var activeId: Long = 0L

    /**
     * Perfil que debe recibir el foco en cuanto su tarjeta exista.
     *
     * ⚠️ No se puede pedir el foco desde fuera con un `post{}`: los datos llegan de un Flow y
     * `notifyDataSetChanged` solo *programa* el layout, así que en ese momento el RecyclerView aún no
     * tiene hijos — `findViewHolderForAdapterPosition` devuelve null y `requestFocus()` sobre la lista
     * vacía falla. El resultado era un selector SIN foco en ninguna tarjeta: con un mando no había
     * forma de elegir perfil. Se consume en [onBindViewHolder], que corre cuando la vista ya existe.
     */
    private var pendingFocusId: Long? = null

    /** Pide que el foco aterrice en este perfil cuando su tarjeta se bindee. */
    fun requestFocusOn(profileId: Long) {
        pendingFocusId = profileId
    }

    fun submit(list: List<Profile>, active: Long) {
        items.clear()
        items.addAll(list)
        activeId = active
        notifyDataSetChanged()
    }

    /** Posición del perfil con ese id, o -1. La usa el selector para enfocar el perfil ACTIVO en vez
     *  de la primera tarjeta: con la vía rápida (abrir + CENTRO) el foco por defecto metía al usuario
     *  en el perfil equivocado. */
    fun positionOf(profileId: Long): Int = items.indexOfFirst { it.id == profileId }

    /** El último ítem es siempre "Añadir". */
    override fun getItemCount() = items.size + 1

    override fun getItemViewType(position: Int) =
        if (position == items.size) TYPE_ADD else TYPE_PROFILE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_profile, parent, false)
        return if (viewType == TYPE_ADD) AddVH(v) else ProfileVH(v)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is ProfileVH -> {
                val profile = items[position]
                holder.bind(profile, profile.id == activeId)
                if (pendingFocusId == profile.id) {
                    pendingFocusId = null
                    holder.itemView.requestFocus()
                }
            }
            is AddVH -> holder.bind()
        }
    }

    private fun focusAnimation(v: View) {
        v.setOnFocusChangeListener { view, hasFocus ->
            view.animate()
                .scaleX(if (hasFocus) 1.10f else 1f)
                .scaleY(if (hasFocus) 1.10f else 1f)
                .translationZ(if (hasFocus) 8f else 0f)
                .setDuration(120)
                .start()
        }
    }

    inner class ProfileVH(v: View) : RecyclerView.ViewHolder(v) {
        private val avatar: TextView = v.findViewById(R.id.profile_avatar)
        private val name: TextView = v.findViewById(R.id.profile_name)
        private val active: TextView = v.findViewById(R.id.profile_active)

        init {
            focusAnimation(v)
            v.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos < items.size) onPick(items[pos])
            }
            // Pulsación larga = editar. En un mando es el gesto que queda libre; el rótulo de abajo
            // de la pantalla lo explica, porque no es descubrible por sí solo.
            v.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos < items.size) {
                    onEdit(items[pos]); true
                } else false
            }
        }

        fun bind(profile: Profile, isActive: Boolean) {
            ProfileAvatars.paint(avatar, profile.name, profile.colorIndex)
            name.text = profile.name
            // INVISIBLE y no GONE: el rótulo conserva su hueco para que todas las tarjetas de la fila
            // midan lo mismo. Ver el comentario de item_profile.xml.
            active.visibility = if (isActive) View.VISIBLE else View.INVISIBLE
        }
    }

    inner class AddVH(v: View) : RecyclerView.ViewHolder(v) {
        private val avatar: TextView = v.findViewById(R.id.profile_avatar)
        private val name: TextView = v.findViewById(R.id.profile_name)
        private val active: TextView = v.findViewById(R.id.profile_active)

        init {
            focusAnimation(v)
            v.setOnClickListener { onAdd() }
        }

        fun bind() {
            ProfileAvatars.paintAddAction(avatar)
            name.setText(R.string.profile_add)
            // Misma razón que en ProfileVH: reservar el hueco para que esta tarjeta mida igual que
            // las de perfil y la fila no salga escalonada.
            active.visibility = View.INVISIBLE
        }
    }

    private companion object {
        const val TYPE_PROFILE = 0
        const val TYPE_ADD = 1
    }
}
