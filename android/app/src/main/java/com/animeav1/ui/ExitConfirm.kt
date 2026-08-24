package com.animeav1.ui

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.animeav1.R

/**
 * "¿Cerrar la app?" antes de que BACK cierre de verdad. Una sola implementación para las dos
 * pantallas desde las que se puede salir: `MainActivity` y el selector de perfiles.
 *
 * @param overlay el `view_exit_confirm` ya incluido en el layout.
 * @param content el contenido que queda DEBAJO, para poder sacarlo de la búsqueda de foco.
 * @param onExit  qué significa salir en esa pantalla (cerrar la Activity, la tarea entera…).
 *
 * Uso: [onBack] desde `dispatchKeyEvent`. Devuelve siempre `true` porque en las dos ramas —abrir y
 * cerrar la pregunta— el BACK queda consumido.
 */
internal class ExitConfirm(
    private val overlay: View,
    private val content: ViewGroup,
    private val onExit: () -> Unit
) {

    private val btnExit: Button = overlay.findViewById(R.id.btn_exit_confirm)
    private val btnCancel: Button = overlay.findViewById(R.id.btn_exit_cancel)

    /** Dónde estaba el foco antes de preguntar, para devolverlo al cancelar. */
    private var focusBefore: View? = null

    val isVisible: Boolean get() = overlay.visibility == View.VISIBLE

    init {
        btnExit.setOnClickListener { onExit() }
        btnCancel.setOnClickListener { hide() }
    }

    /**
     * @return true — el BACK siempre se consume: la primera pulsación pregunta y la segunda cierra
     *         la pregunta. Que BACK cancele es lo que hacen todos los modales de esta app, y aquí
     *         además es lo que evita que machacar BACK acabe cerrando la app, que es justo el
     *         accidente que esta pantalla existe para prevenir.
     */
    fun onBack(): Boolean {
        if (isVisible) hide() else show()
        return true
    }

    private fun show() {
        focusBefore = content.findFocus()
        overlay.visibility = View.VISIBLE
        // ⚠️ Sacar el contenido de la búsqueda de foco. Un overlay VISIBLE no aísla nada: FocusFinder
        // no sabe de oclusión, así que las pestañas y las tarjetas de debajo seguirían siendo
        // alcanzables con el D-pad por detrás del scrim (el mismo fallo que tuvo el editor de perfil).
        content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        // El foco arranca en "Salir": quien pulsa BACK quiere irse, así que la salida deliberada son
        // dos pulsaciones (BACK + CENTRO). El accidente que se quiere evitar es machacar BACK, y de
        // ese protege que BACK cancele.
        btnExit.post { btnExit.requestFocus() }
    }

    private fun hide() {
        overlay.visibility = View.GONE
        content.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        val previous = focusBefore
        focusBefore = null
        // Devolver el foco donde estaba; si esa vista ya no existe, que lo resuelva el contenido.
        content.post { if (previous?.requestFocus() != true) content.requestFocus() }
    }
}
