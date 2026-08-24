package com.animeav1.ui.series

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.animeav1.R
import com.animeav1.data.model.EpisodeRef

/**
 * Rejilla de episodios.
 *
 * Muestra tres cosas que antes no se veían sin abrir cada episodio: si está **visto**, si está **a
 * medias** (barra inferior con el progreso guardado) y cuál es el **siguiente a ver**.
 */
class EpisodeGridAdapter(
    private val episodes: List<EpisodeRef>,
    private var watchedSet: Set<Int> = emptySet(),
    /**
     * Números de **toda la serie**, no solo los del bloque visible. ⚠️ El "siguiente a ver" es una
     * propiedad de la serie: derivándolo de `episodes` (ya filtrado por bloque), cada bloque pintaba
     * su propio primer-no-visto como si fuera el siguiente, así que en One Piece había una marca
     * falsa en el bloque 101-200, otra en el 201-300…
     */
    private val allNumbers: List<Int> = episodes.map { it.number },
    private val onClick: (EpisodeRef) -> Unit,
    private val onWatchedToggle: (EpisodeRef) -> Unit,
    /**
     * Columnas de la rejilla, para saber qué tiles son de la ÚLTIMA fila. Ver [downFocusId].
     */
    private val spanCount: Int = 1,
    /**
     * A dónde va ABAJO desde la última fila de tiles, o [View.NO_ID] si no hay a dónde ir.
     *
     * ⚠️ Hay que decirlo a mano porque el algoritmo de foco de Android no llega solo: la rejilla
     * tiene `layout_weight="1"`, así que en una serie corta los tiles se quedan arriba y entre el
     * último y la fila de *Series relacionadas* hay un palmo de vacío. `FocusFinder` prefiere el
     * candidato alineado con la vista enfocada, **pero solo hasta cierta distancia** —si el que
     * está en el haz queda más lejos que el borde lejano de otro candidato, pasa a decidir por
     * distancia ponderada— y ahí ganaba el botón *Continuar* del panel izquierdo. Medido en el
     * emulador con 4 episodios: 954 px hasta la tarjeta relacionada contra 714 px hasta el botón.
     * El resultado era que a *Series relacionadas* **no se llegaba con el mando**.
     *
     * ⚠️ Solo se pone en la última fila. En las demás, ABAJO tiene que seguir bajando a la fila
     * siguiente de la rejilla, que es lo que hace el `RecyclerView` por su cuenta.
     *
     * Cuando la serie no tiene relacionadas, quien llama pasa el id del PROPIO tile: el buscador de
     * foco devuelve la vista que ya lo tiene y la pulsación se queda en nada, que es lo correcto
     * cuando debajo no hay nada en esta columna.
     */
    private val downFocusId: Int = View.NO_ID,
    /**
     * A dónde va ARRIBA desde la PRIMERA fila de tiles.
     *
     * Con la ficha en tres secciones apiladas, encima de la rejilla está la fila de acciones de la
     * portada, y hay una sección entera de por medio: `FocusFinder` decide por distancia y no tiene
     * por qué acertar cuál de los tres botones (o del rótulo) es el destino razonable. Se dice a
     * mano, igual que `ScheduleListAdapter.upFocusId` en el Horario.
     */
    private val upFocusId: Int = View.NO_ID
) : RecyclerView.Adapter<EpisodeGridAdapter.VH>() {

    /** número de episodio → fracción vista (0..1), solo de los que están a medias. */
    private var progress: Map<Int, Float> = emptyMap()

    /** Episodio que toca ver: el primero sin marcar. -1 si no hay ninguno (serie terminada). */
    private var nextNumber: Int = -1

    inner class VH(val root: View) : RecyclerView.ViewHolder(root) {
        val number: TextView  = root.findViewById(R.id.ep_number)
        val watched: TextView = root.findViewById(R.id.ep_watched)
        val progressBar: View = root.findViewById(R.id.ep_progress)
        val nextMark: TextView = root.findViewById(R.id.ep_next)

        init {
            root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(episodes[pos])
            }
            root.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onWatchedToggle(episodes[pos])
                true
            }
            root.setOnFocusChangeListener { v, focused ->
                v.animate().scaleX(if (focused) 1.10f else 1f)
                           .scaleY(if (focused) 1.10f else 1f)
                           .translationZ(if (focused) 8f else 0f)
                           .setDuration(120)
                           .start()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_episode_grid, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(WATCHED)) {
            bindWatched(holder, episodes[position])
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ep = episodes[position]
        holder.number.text = ep.number.toString()
        holder.root.nextFocusDownId =
            if (downFocusId != View.NO_ID && position >= itemCount - spanCount) downFocusId
            else View.NO_ID
        holder.root.nextFocusUpId =
            if (upFocusId != View.NO_ID && position < spanCount) upFocusId else View.NO_ID
        bindWatched(holder, ep)
    }

    private fun bindWatched(holder: VH, ep: EpisodeRef) {
        val isWatched = ep.number in watchedSet
        holder.watched.visibility = if (isWatched) View.VISIBLE else View.GONE
        // 0.8 y no 0.65: atenuar tanto lo visto hacía que el episodio ENFOCADO fuera el que menos se
        // veía si además estaba visto, que es justo al revés de lo que hace falta con un mando.
        holder.root.alpha = if (isWatched) 0.8f else 1f
        holder.nextMark.visibility = if (ep.number == nextNumber) View.VISIBLE else View.GONE
        bindProgress(holder, ep)
    }

    /** Barra inferior proporcional a lo visto. Se mide contra el ancho real del tile ya medido. */
    private fun bindProgress(holder: VH, ep: EpisodeRef) {
        val fraction = progress[ep.number]
        if (fraction == null || fraction <= 0f) {
            holder.progressBar.visibility = View.GONE
            return
        }
        holder.progressBar.visibility = View.VISIBLE
        val apply = {
            val w = holder.root.width
            if (w > 0) {
                holder.progressBar.layoutParams = holder.progressBar.layoutParams.apply {
                    width = (w * fraction.coerceIn(0f, 1f)).toInt().coerceAtLeast(2)
                }
                holder.progressBar.requestLayout()
            }
        }
        // En el primer bind el tile aún no está medido: se reintenta tras el layout.
        if (holder.root.width > 0) apply() else holder.root.post { apply() }
    }

    /** Progreso parcial por episodio. Rebindea solo lo que cambia, para no robar el foco. */
    fun updateProgress(newProgress: Map<Int, Float>) {
        val changed = (progress.keys + newProgress.keys).filter { progress[it] != newProgress[it] }
        progress = newProgress
        if (changed.isEmpty()) return
        episodes.forEachIndexed { i, ep -> if (ep.number in changed) notifyItemChanged(i, WATCHED) }
    }

    override fun getItemCount() = episodes.size

    /** Rebind only the episodes whose watched state actually changed (keeps D-pad focus). */
    fun updateWatched(newWatched: Set<Int>) {
        val changed = (watchedSet - newWatched) + (newWatched - watchedSet)
        val previousNext = nextNumber
        watchedSet = newWatched
        nextNumber = allNumbers.firstOrNull { it !in newWatched } ?: -1
        // La marca de "siguiente" se mueve al marcar/desmarcar, así que sus dos tiles también
        // necesitan rebind aunque su propio estado de visto no haya cambiado.
        val toRebind = changed + setOfNotNull(
            previousNext.takeIf { it >= 0 }, nextNumber.takeIf { it >= 0 }
        )
        if (toRebind.isEmpty()) return
        val changed2 = toRebind
        episodes.forEachIndexed { i, ep ->
            if (ep.number in changed2) notifyItemChanged(i, WATCHED)
        }
    }

    companion object {
        private val WATCHED = Any()
    }
}
