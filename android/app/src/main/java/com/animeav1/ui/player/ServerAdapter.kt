package com.animeav1.ui.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.recyclerview.widget.RecyclerView
import com.animeav1.R
import com.animeav1.data.model.AudioTrack
import com.animeav1.data.model.EmbedServer

/**
 * Lista del panel de servidores, **agrupada por pista de audio** igual que el sitio: una cabecera
 * "SUBTITULADO" / "DOBLADO" y debajo sus servidores.
 *
 * La selección se guarda por [EmbedServer] y no por índice ni por nombre: el mismo servidor
 * ("HLS") aparece en las dos pistas, así que un nombre ya no identifica una fila, y los índices
 * de la lista de embeds dejaron de coincidir con las posiciones del adapter en cuanto se
 * intercalaron cabeceras.
 */
class ServerAdapter(private val onClick: (EmbedServer) -> Unit)
    : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        data class Header(val track: AudioTrack) : Row()
        data class Server(val embed: EmbedServer) : Row()
    }

    private val rows = mutableListOf<Row>()
    private var selected: EmbedServer? = null

    fun setServers(list: List<EmbedServer>) {
        rows.clear()
        // Recorre las pistas en el orden en que llegan (EmbedParser ya entrega SUB antes que DUB)
        // y abre una cabecera cada vez que cambia: mantiene el orden del sitio sin reordenar nada.
        var current: AudioTrack? = null
        for (embed in list) {
            if (embed.audio != current) {
                current = embed.audio
                rows.add(Row.Header(embed.audio))
            }
            rows.add(Row.Server(embed))
        }
        notifyDataSetChanged()
    }

    fun setSelected(embed: EmbedServer?) {
        val old = positionOf(selected)
        selected = embed
        val new = positionOf(embed)
        if (old >= 0) notifyItemChanged(old)
        if (new >= 0) notifyItemChanged(new)
    }

    /** Posición en el adapter de un embed, o -1. */
    fun positionOf(embed: EmbedServer?): Int {
        embed ?: return -1
        return rows.indexOfFirst { it is Row.Server && it.embed == embed }
    }

    /**
     * Posición de la **cabecera** del grupo al que pertenece [embed], o -1.
     *
     * Es a esto y no al servidor a lo que hace scroll el panel: `scrollToPosition` deja el ítem
     * pedido pegado al borde superior, así que apuntar al servidor seleccionado empujaba su
     * rótulo ("SUBTITULADO") justo fuera de la pantalla — el panel abría sin la separación que
     * es el sentido de todo esto.
     */
    fun groupStartOf(embed: EmbedServer?): Int {
        val at = positionOf(embed)
        if (at < 0) return -1
        for (i in at downTo 0) if (rows[i] is Row.Header) return i
        return 0
    }

    override fun getItemViewType(position: Int) =
        if (rows[position] is Row.Header) TYPE_HEADER else TYPE_SERVER

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(inflater.inflate(R.layout.item_server_header, parent, false))
        } else {
            ServerVH(inflater.inflate(R.layout.item_server, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderVH).label.setText(headerRes(row.track))
            is Row.Server -> bindServer(holder as ServerVH, row.embed)
        }
    }

    private fun bindServer(holder: ServerVH, embed: EmbedServer) {
        val isSelected = embed == selected
        holder.name.text = embed.server.replaceFirstChar { it.uppercase() }
        holder.type.text = serverTypeLabel(embed)
        holder.icon.text = serverIcon(embed)
        holder.selected.visibility = if (isSelected) View.VISIBLE else View.GONE
        holder.itemView.alpha = if (selected != null && !isSelected) 0.6f else 1f
        holder.itemView.setOnClickListener { onClick(embed) }
        holder.itemView.isFocusable = true
    }

    override fun getItemCount() = rows.size

    @StringRes
    private fun headerRes(track: AudioTrack) = when (track) {
        AudioTrack.SUB -> R.string.audio_sub_header
        AudioTrack.DUB -> R.string.audio_dub_header
    }

    /**
     * Los rótulos solo cubren los servidores que [com.animeav1.data.EmbedParser] deja pasar; los
     * que se filtran (StreamTape, UPNShare, DoodStream…) ya no llegan aquí y se les quitaron sus
     * ramas, que además mentían llamándolos "MP4 directo".
     */
    private fun serverTypeLabel(e: EmbedServer): String {
        val lower = e.url.lowercase() + e.server.lowercase()
        return when {
            lower.contains("m3u8") || lower.contains("hls") -> "HLS Stream"
            // Los dos progresivos: el .mp4 sale en texto plano del HTML del embed.
            lower.contains("mp4upload") || lower.contains("yourupload") -> "MP4 directo"
            else -> "Embed"
        }
    }

    private fun serverIcon(e: EmbedServer): String {
        val lower = e.url.lowercase() + e.server.lowercase()
        return when {
            lower.contains("m3u8") || lower.contains("hls") -> "📡"
            lower.contains("mp4upload") || lower.contains("yourupload") -> "▶"
            else -> "🌐"
        }
    }

    class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val label: TextView = v.findViewById(R.id.track_header)
    }

    class ServerVH(v: View) : RecyclerView.ViewHolder(v) {
        val name:     TextView = v.findViewById(R.id.server_name)
        val type:     TextView = v.findViewById(R.id.server_type)
        val icon:     TextView = v.findViewById(R.id.server_icon)
        val selected: TextView = v.findViewById(R.id.server_selected)
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_SERVER = 1
    }
}
