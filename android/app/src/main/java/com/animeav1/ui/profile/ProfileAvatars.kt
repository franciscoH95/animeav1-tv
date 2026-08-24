package com.animeav1.ui.profile

import android.graphics.PorterDuff
import android.widget.TextView
import com.animeav1.data.local.Profile

/**
 * Paleta y pintado del avatar de un perfil. En un solo sitio porque lo usan la rejilla de perfiles,
 * el selector de color del editor y el botón de la cabecera de Mi Lista: tres copias del mismo
 * `when(colorIndex)` es exactamente cómo se acaba con el mismo perfil de dos colores distintos.
 */
internal object ProfileAvatars {

    /** Derivados del acento morado de la app, pero distinguibles entre sí de lejos en una TV. */
    private val COLORS = intArrayOf(
        0xFF7B6CF6.toInt(),  // morado (el acento)
        0xFF3FA7D6.toInt(),  // azul
        0xFF49B675.toInt(),  // verde
        0xFFE0803C.toInt(),  // naranja
        0xFFD44D6E.toInt(),  // rosa
        0xFF8A8F98.toInt()   // gris
    )

    init {
        // La entidad declara cuántos colores hay para poder normalizar el índice sin depender de la
        // capa de UI; si las dos se separan, un perfil guardado apuntaría fuera de la paleta.
        require(COLORS.size == Profile.COLOR_COUNT)
    }

    fun colorAt(index: Int): Int = COLORS[normalize(index)]

    /** Módulo defensivo: un índice de un backup de otra versión puede caer fuera de la paleta. */
    fun normalize(index: Int): Int = ((index % Profile.COLOR_COUNT) + Profile.COLOR_COUNT) % Profile.COLOR_COUNT

    /** Inicial en mayúscula; vacío si el nombre no tiene ninguna letra utilizable. */
    fun initialOf(name: String): String =
        name.trim().firstOrNull()?.uppercase() ?: ""

    /** Gris neutro de la tarjeta "Añadir perfil": es una acción, no una persona, así que se queda
     *  fuera de la paleta de identidad a propósito. */
    private const val ADD_ACTION_COLOR = 0xFF3A3A3A.toInt()

    /**
     * Tiñe el círculo y pone la inicial. Se usa `PorterDuff.Mode.SRC_IN` sobre un shape blanco:
     * con `setColorFilter` por defecto el resultado sería una mezcla y no el color elegido.
     */
    fun paint(view: TextView, name: String, colorIndex: Int) {
        view.text = initialOf(name)
        tint(view, colorAt(colorIndex))
    }

    /** El "+" de la tarjeta de añadir. Vive aquí y no en el adapter para que TODO el pintado de
     *  avatares esté en un sitio: es la misma razón por la que la paleta no se duplica. */
    fun paintAddAction(view: TextView) {
        view.text = "+"
        tint(view, ADD_ACTION_COLOR)
    }

    private fun tint(view: TextView, color: Int) {
        view.background?.mutate()?.setColorFilter(color, PorterDuff.Mode.SRC_IN)
    }
}
