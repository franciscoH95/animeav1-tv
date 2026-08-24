package com.animeav1.ui

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * ImageView cuyo ALTO lo manda su ancho, con una proporción fija (16:9 por defecto).
 *
 * Existe para que el tile de episodio enseñe el fotograma **entero**: las miniaturas del CDN son
 * 16:9, así que con el hueco en esa misma proporción no hay nada que recortar.
 *
 * ⚠️ No vale `adjustViewBounds`, que sería lo obvio: ese calcula el alto a partir del **drawable ya
 * cargado**, así que mientras la imagen viaja el tile mide 0 y la rejilla entera pega un salto
 * cuando llegan las respuestas — y los tiles cuya imagen no existe (el CDN devuelve 403) se
 * quedarían planos para siempre. Aquí el alto se sabe desde el primer `onMeasure`, haya imagen o no.
 */
class RatioImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : AppCompatImageView(context, attrs, defStyle) {

    /** ancho / alto. */
    var ratio: Float = 16f / 9f
        set(value) { field = value; requestLayout() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = measuredWidth
        if (w > 0 && ratio > 0f) setMeasuredDimension(w, (w / ratio).toInt())
    }
}
