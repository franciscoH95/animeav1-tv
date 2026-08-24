package com.animeav1.ui

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Fila horizontal de Inicio con la tarjeta enfocada **anclada al principio**.
 *
 * Un `LinearLayoutManager` normal hace el scroll MÍNIMO para que la tarjeta enfocada quepa, así que
 * al avanzar con DERECHA la tarjeta se queda pegada al borde derecho de la pantalla: el aro de foco
 * toca el margen de seguridad, no se ve nada de lo que viene después y el usuario no sabe si la fila
 * sigue. Aquí, en cambio, la fila se desliza para dejar la tarjeta enfocada siempre en el mismo
 * sitio —el inicio del contenido— y lo que aún no se ha visto entra por la derecha.
 *
 * ⚠️ Esto también arregla el movimiento VERTICAL, que es de donde venía la sensación de foco
 * caprichoso: `FocusFinder` elige por geometría, así que bajar de una fila ya desplazada a otra sin
 * desplazar aterrizaba en una tarjeta cualquiera, distinta cada vez. Con todas las filas alineando
 * su tarjeta enfocada en la misma X, ARRIBA/ABAJO cae siempre en la tarjeta que la fila destino
 * tenía seleccionada (la primera, si nunca se ha entrado en ella).
 *
 * Se mide con [getDecoratedLeft] y no con `child.left` para que el ancla sea la misma que ocupa la
 * primera tarjeta en reposo: con el espaciado de la fila metido por `ItemDecoration`, usar el borde
 * de la vista movería todas las filas unos píxeles respecto de su posición inicial.
 */
class RowLayoutManager(
    context: Context,
    /**
     * Si IZQUIERDA en la primera tarjeta se queda dentro de la fila.
     *
     * En Inicio sí: a la izquierda de una fila no hay nada, así que salir solo puede llevar el foco
     * a una tarjeta de otra fila. En *Series relacionadas* **no**, porque a la izquierda está el
     * panel de la serie (Reproducir, listas, favorito) y llegar ahí con IZQUIERDA es justo lo que
     * se espera: bloquearla dejaría al usuario encerrado en la fila, con ARRIBA como única salida.
     */
    private val blockStartEdge: Boolean = true
) : LinearLayoutManager(context, HORIZONTAL, false) {

    override fun requestChildRectangleOnScreen(
        parent: RecyclerView,
        child: View,
        rect: Rect,
        immediate: Boolean,
        focusedChildVisible: Boolean
    ): Boolean {
        val dx = getDecoratedLeft(child) - parent.paddingLeft
        if (dx == 0) return false
        // RecyclerView recorta solo al principio y al final de la lista, así que en una fila corta
        // —o en las últimas tarjetas— esto degrada al comportamiento de siempre en vez de dejar un
        // hueco vacío al final.
        if (immediate) parent.scrollBy(dx, 0) else parent.smoothScrollBy(dx, 0)
        return true
    }

    /**
     * En el extremo de la fila, IZQUIERDA/DERECHA **no salen de ella**.
     *
     * Cuando `RecyclerView` no encuentra a dónde ir, delega la búsqueda al padre y `FocusFinder`
     * —que solo mira geometría— se lleva el foco a la tarjeta más cercana de OTRA fila: en la fila
     * *Películas*, con una sola tarjeta, una pulsación de DERECHA saltaba a *Mejor valorados* y
     * arrastraba el scroll con ella. Cambiar de fila es cosa de ARRIBA/ABAJO, así que en el extremo
     * se devuelve la tarjeta que ya tiene el foco y la pulsación se queda en nada.
     *
     * ⚠️ Va en `onInterceptFocusSearch` y no en `onFocusSearchFailed`, que es donde parecía tocar:
     * lo que devuelve `onFocusSearchFailed` pasa por `isPreferredNextFocus`, que descarta
     * explícitamente `next == focused` y cae en el `super.focusSearch` del padre — o sea, el salto
     * de fila seguía ocurriendo. `onInterceptFocusSearch` se resuelve antes de esa comprobación.
     *
     * ⚠️ Solo LEFT y RIGHT, y solo en el extremo. En cualquier otro caso devuelve null para no
     * pisar la búsqueda normal: interceptar ARRIBA/ABAJO dejaría Inicio con una sola fila
     * alcanzable, e interceptar en mitad de la fila rompería el avance entre tarjetas.
     */
    override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
        if (direction != View.FOCUS_LEFT && direction != View.FOCUS_RIGHT) return null
        val item = itemViewOf(focused) ?: return null
        val position = getPosition(item)
        if (position == RecyclerView.NO_POSITION) return null
        val atEdge =
            if (direction == View.FOCUS_RIGHT) position == itemCount - 1
            else blockStartEdge && position == 0
        return if (atEdge) focused else null
    }

    /** La vista de ítem que contiene a [focused] — `getPosition` solo entiende de hijos directos. */
    private fun itemViewOf(focused: View): View? {
        var v: View? = focused
        while (v != null) {
            if (v.parent is RecyclerView) return v
            v = v.parent as? View
        }
        return null
    }
}
