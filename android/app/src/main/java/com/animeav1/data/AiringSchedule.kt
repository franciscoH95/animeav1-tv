package com.animeav1.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Cuándo cae el **próximo episodio** de una serie en emisión.
 *
 * ⚠️ El sitio trae un campo `nextDate`, pero **NO es la fecha del próximo episodio**: comprobado
 * contra el horario real, en las tres series en emisión que se miraron valía exactamente lo mismo
 * que `startDate`, mientras el último episodio publicado era de esa misma semana. Lo que sí sirve es
 * usarlo como **ancla** de la cadencia (`waitDays`, 7 en la práctica) y contar desde ahí.
 *
 * Mitad **pura** y sin Android a propósito, como `StreamUrlParser` o `BackupCodec`: la fecha "hoy"
 * entra por parámetro en vez de leerse del reloj, que es lo que permite probarlo.
 *
 * Validado contra datos reales del sitio (21/08/2026): *Nige Jouzu 2nd* (ancla 17/07, 6 episodios)
 * → 28/08; *Mebius Dust* (ancla 09/07, 7 episodios) → 27/08; *Dogulwang* (ancla 09/07, 7 episodios)
 * → 27/08. En los tres, el número de emisiones que predice la cadencia coincide **exactamente** con
 * los episodios publicados.
 */
object AiringSchedule {

    /** Un día en milisegundos. Se trabaja en UTC, así que no hay horarios de verano que corrijan. */
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * Fecha del próximo episodio, en milisegundos (medianoche UTC), o `null` si no se puede
     * prometer ninguna.
     *
     * @param anchorDate fecha "yyyy-MM-dd" del primer episodio (`nextDate`, o `startDate`).
     * @param waitDays días entre episodios. 0 o negativo = sin cadencia conocida.
     * @param publishedEpisodes episodios que el sitio ya publica.
     * @param todayMillis instante actual.
     *
     * Devuelve null cuando:
     * - no hay ancla o cadencia utilizables;
     * - la serie va **atrasada más de un episodio** respecto de su propia cadencia. Eso es lo que
     *   distingue una serie viva de una parada, y sin esa comprobación la ficha de una serie
     *   abandonada prometería un episodio nuevo cada semana para siempre. Se tolera **uno** de
     *   retraso porque una semana de descanso o un recap es normal.
     */
    fun nextAirDate(
        anchorDate: String,
        waitDays: Int,
        publishedEpisodes: Int,
        todayMillis: Long
    ): Long? {
        if (waitDays <= 0) return null
        val anchor = parseDate(anchorDate) ?: return null
        val today = startOfDay(todayMillis)

        // Aún no ha empezado: la próxima emisión es el estreno.
        if (anchor > today) return anchor

        val elapsedDays = ((today - anchor) / DAY_MS).toInt()
        // Emisiones que debería haber contando hoy como día de emisión.
        val airedByToday = elapsedDays / waitDays + 1
        if (airedByToday - publishedEpisodes > 1) return null

        // Si el episodio de hoy ya está publicado, el siguiente es una cadencia más allá; si aún
        // falta, el siguiente es justo el de hoy.
        val steps = if (publishedEpisodes >= airedByToday) airedByToday else airedByToday - 1
        return anchor + steps.toLong() * waitDays * DAY_MS
    }

    /** "yyyy-MM-dd" (o un ISO más largo) a medianoche UTC. null si no hay fecha utilizable. */
    private fun parseDate(date: String): Long? {
        val d = date.take(10)
        if (d.length != 10) return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
                .parse(d)?.time
        }.getOrNull()
    }

    private fun startOfDay(millis: Long): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            time = Date(millis)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** Días de diferencia entre dos instantes ya normalizados a medianoche UTC. */
    fun daysBetween(fromMillis: Long, toMillis: Long): Int =
        ((startOfDay(toMillis) - startOfDay(fromMillis)) / DAY_MS).toInt()
}
