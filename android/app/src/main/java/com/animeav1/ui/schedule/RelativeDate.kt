package com.animeav1.ui.schedule

import android.content.Context
import com.animeav1.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Convierte la fecha ISO del último episodio (`yyyy-MM-dd`) en algo que responda la única pregunta
 * que se hace en el Horario: **¿ya salió el de esta semana?**
 *
 * Antes se pintaba el ISO crudo (`2026-08-08`), así que el usuario tenía que restar fechas de cabeza
 * mirando la tele.
 *
 * `SimpleDateFormat`/`Calendar` y no `java.time`: `minSdk 21` y el proyecto no tiene desugaring, así
 * que las clases de `java.time` no existen en los aparatos viejos.
 */
internal object RelativeDate {

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** Formato de entrada del sitio. `Locale.US` porque el patrón es numérico y fijo, no localizado. */
    private val ISO = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** Salida para fechas lejanas: sí localizada, que es lo que lee el usuario. */
    private val SHORT = SimpleDateFormat("d MMM", Locale.getDefault())

    /**
     * @param iso fecha en `yyyy-MM-dd`; si no se puede leer se devuelve tal cual, que es mejor que
     *            no mostrar nada.
     * @param nowMs "ahora", inyectable para poder testear sin depender del reloj.
     */
    fun format(context: Context, iso: String, nowMs: Long = System.currentTimeMillis()): String {
        val date = try { ISO.parse(iso) } catch (e: Exception) { null } ?: return iso
        val days = daysBetween(date, nowMs)
        return when {
            days == 0L  -> context.getString(R.string.date_today)
            days == 1L  -> context.getString(R.string.date_yesterday)
            // Dentro de la semana es cuando "hace N días" dice más que una fecha.
            days in 2..6 -> context.getString(R.string.date_days_ago, days.toInt())
            days < 0L   -> SHORT.format(date)   // el sitio a veces publica con fecha futura
            else        -> SHORT.format(date)
        }
    }

    /**
     * Días completos de diferencia comparando por **día natural**, no por milisegundos: un episodio
     * de anoche a las 23:00 tiene que decir "ayer", no "hace 0 días".
     */
    private fun daysBetween(date: Date, nowMs: Long): Long {
        val then = midnight(date.time)
        val now = midnight(nowMs)
        return (now - then) / DAY_MS
    }

    private fun midnight(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
