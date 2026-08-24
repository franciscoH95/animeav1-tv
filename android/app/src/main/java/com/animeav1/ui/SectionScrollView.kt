package com.animeav1.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.core.widget.NestedScrollView

/**
 * Scroll vertical con la **sección enfocada anclada arriba**. Lo usan Inicio (una sección por fila)
 * y la ficha de serie (portada / episodios / relacionadas).
 *
 * `NestedScrollView` desplaza lo MÍNIMO para que la vista enfocada quepa. En una pantalla por secciones
 * eso deja la fila recién enfocada pegada al borde inferior: el título de la tarjeta queda cortado
 * por el borde de la pantalla, la fila de la que vienes sigue ocupando media pantalla y no se ve
 * nada de la fila siguiente, así que no hay forma de saber si queda algo más abajo.
 *
 * Aquí, al enfocar cualquier fila, se desplaza para dejar SU rótulo justo debajo de la barra de
 * pestañas. Todas las filas se ven igual al enfocarse, y siempre asoma la siguiente.
 *
 * Las secciones se registran con [registerRow] desde la pantalla en vez de deducirlas de la
 * jerarquía: las locales son hijas directas del contenido y las de descubrimiento cuelgan de otro
 * contenedor, así que cualquier regla estructural acabaría tratando *todas* las de descubrimiento
 * como una sola fila.
 */
class SectionScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {

    private val rows = ArrayList<View>()

    private companion object {
        /** Respiro que se deja alrededor del foco al desplazar dentro de una sección alta. */
        const val FOCUS_MARGIN_DP = 56
    }

    fun registerRow(row: View) {
        if (rows.none { it === row }) rows.add(row)
    }

    /**
     * ⚠️ El desplazamiento propio se anula devolviendo 0 **solo** cuando el foco está dentro de una
     * fila registrada. Si no, esta pantalla perdería el comportamiento normal para todo lo demás
     * (el botón del estado vacío, o cualquier hijo que pida hacerse visible por su cuenta).
     */
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        if (anchorable(rowOf(findFocus())) != null) return 0
        // ⚠️ En una sección que no cabe (una rejilla de episodios larga) manda el desplazamiento
        // normal, que es el MÍNIMO para que la vista enfocada quepa: deja el tile pegado al borde
        // de abajo, sin margen y sin dejar ver que hay otra fila detrás. Se le "engorda" el
        // rectángulo antes de calcular para que el scroll deje un respiro alrededor del foco.
        val margin = (FOCUS_MARGIN_DP * resources.displayMetrics.density).toInt()
        val padded = Rect(rect).apply { inset(0, -margin) }
        return super.computeScrollDeltaToGetChildRectOnScreen(padded)
    }

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        val row = anchorable(rowOf(focused)) ?: return
        // Durante un layout pendiente los `top` todavía no son los definitivos: alinear ahí deja la
        // fila a medio camino. Se reintenta cuando el layout ha corrido, y solo si el foco sigue
        // donde estaba (si el usuario ya se ha movido, manda su última pulsación).
        if (isLayoutRequested) post { if (rowOf(findFocus()) === row) alignTo(row) } else alignTo(row)
    }

    private fun alignTo(row: View) {
        val target = (topInContent(row) - paddingTop).coerceIn(0, scrollRange())
        // smoothScrollTo cae a un salto seco si la anterior fue hace menos de 250 ms, así que
        // machacar ABAJO sigue siendo inmediato y no se acumulan animaciones.
        if (target != scrollY) smoothScrollTo(0, target)
    }

    /**
     * La sección, **solo si se puede anclar**: es decir, si cabe entera en la pantalla.
     *
     * ⚠️ Una sección más alta que la ventana no se ancla, y además se le devuelve el desplazamiento
     * normal del `NestedScrollView`. Anclarla dejaría el scroll clavado en su borde superior, así
     * que al bajar dentro de ella —por ejemplo por una rejilla de episodios de ocho filas— el foco
     * se saldría por debajo del borde de la pantalla y desaparecería de la vista. Con el
     * comportamiento normal, el scroll sigue al foco.
     */
    private fun anchorable(row: View?): View? =
        row?.takeIf { it.height in 1..height }

    /** La fila registrada que contiene a [view], o null si no está dentro de ninguna. */
    private fun rowOf(view: View?): View? {
        var v: View? = view
        while (v != null && v !== this) {
            if (rows.any { it === v }) return v
            v = v.parent as? View
        }
        return null
    }

    /** Posición de [row] dentro del contenido, es decir SIN restar el scroll actual. */
    private fun topInContent(row: View): Int {
        var top = 0
        var v: View = row
        while (true) {
            top += v.top
            val parent = v.parent
            if (parent !is View || parent === this) return top
            v = parent
        }
    }

    /** Lo mismo que calcula NestedScrollView para no dejar hacer scroll más allá del contenido. */
    private fun scrollRange(): Int {
        val child = getChildAt(0) ?: return 0
        return (child.height - (height - paddingTop - paddingBottom)).coerceAtLeast(0)
    }
}
