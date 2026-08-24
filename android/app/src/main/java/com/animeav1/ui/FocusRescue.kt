package com.animeav1.ui

import android.view.View

/**
 * Esconder una vista que tiene el foco **lo expulsa de la sección**.
 *
 * Android no deja el foco en null: hace `clearFocus()` y lo reasigna al primer focusable de la
 * ventana, que en esta app es la pestaña INICIO. El síntoma que ve el usuario es que el aro de foco
 * aparece en una pestaña que no ha pulsado —con la barra marcando todavía otra sección— y un CENTRO
 * por reflejo cambia de sitio.
 *
 * Pasa en todos los sitios donde un Flow de Room puede vaciar algo que estaba enfocado: la fila
 * *Continuar viendo* de Inicio al terminar el último episodio pendiente, una sub-lista de Mi Lista al
 * quitar su única serie, o el propio botón "Reintentar" de una fila de descubrimiento, que se esconde
 * al pasar a "cargando".
 *
 * Esta es la ÚNICA implementación de la regla. La política de "a dónde va el foco" la pone cada
 * pantalla en [fallback]; el mecanismo —cuándo hay que rescatar— vive aquí.
 *
 * @param root raíz de la pantalla o del fragment. Se usa para saber si el foco sigue dentro.
 * @param fallback dónde poner el foco si se ha escapado. Puede devolver null (nada que enfocar).
 * @param change el cambio de visibilidad que puede expulsar el foco.
 * @return `false` **solo** si había que rescatar y no se pudo (quien llama puede reintentarlo más
 *         tarde); `true` si no hacía falta o si se consiguió.
 */
inline fun rescuingFocus(root: View?, fallback: () -> View?, change: () -> Unit): Boolean {
    val hadFocusInside = root?.findFocus() != null
    change()
    if (root == null || !hadFocusInside) return true
    // Sigue dentro: el cambio no ha tocado al foco y no hay nada que hacer.
    if (root.findFocus() != null) return true
    val target = fallback() ?: return false
    // `isShown` y no solo `visibility`: mira también los ancestros. Sin esto se puede "rescatar" el
    // foco hacia dentro de la propia sección que se acaba de esconder — `requestFocus()` no comprueba
    // la visibilidad de los padres, así que triunfaría y dejaría el foco invisible.
    if (!target.isShown) return false
    return target.requestFocus()
}
