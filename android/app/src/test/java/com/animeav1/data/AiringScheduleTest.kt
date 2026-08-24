package com.animeav1.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * La fecha del próximo episodio se CALCULA (el `nextDate` del sitio no es lo que su nombre dice),
 * así que la aritmética se prueba aquí. Los tres primeros casos son datos **reales** capturados del
 * sitio el 21/08/2026, con su horario cruzado para saber la respuesta correcta.
 */
class AiringScheduleTest {

    private fun day(date: String): Long =
        SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(date)!!.time

    private fun next(anchor: String, wait: Int, eps: Int, today: String): String? =
        AiringSchedule.nextAirDate(anchor, wait, eps, day(today))?.let {
            SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(java.util.Date(it))
        }

    // ── Casos reales ──────────────────────────────────────────────────────────

    @Test fun `nige jouzu 2nd - el episodio de hoy ya salio, toca el de la semana que viene`() {
        // 6 episodios, ancla 17/07 semanal: el 6º cae justo hoy y el sitio ya lo publica.
        assertEquals("2026-08-28", next("2026-07-17", 7, 6, "2026-08-21"))
    }

    @Test fun `mebius dust`() {
        assertEquals("2026-08-27", next("2026-07-09", 7, 7, "2026-08-21"))
    }

    @Test fun `dogulwang`() {
        assertEquals("2026-08-27", next("2026-07-09", 7, 7, "2026-08-21"))
    }

    // ── Reglas ────────────────────────────────────────────────────────────────

    @Test fun `si el episodio de hoy aun no esta publicado, el proximo es hoy`() {
        // Mismo caso que Nige pero con 5 episodios: el de hoy todavía no ha salido.
        assertEquals("2026-08-21", next("2026-07-17", 7, 5, "2026-08-21"))
    }

    @Test fun `una serie atrasada mas de un episodio no promete fecha`() {
        // La cadencia dice 6 emisiones y solo hay 3: está parada, no se inventa un estreno.
        assertNull(next("2026-07-17", 7, 3, "2026-08-21"))
    }

    @Test fun `se tolera un episodio de retraso - una semana de descanso es normal`() {
        assertEquals("2026-08-21", next("2026-07-17", 7, 5, "2026-08-21"))
    }

    @Test fun `una serie que aun no ha empezado devuelve su estreno`() {
        assertEquals("2026-09-04", next("2026-09-04", 7, 0, "2026-08-21"))
    }

    @Test fun `sin cadencia conocida no hay fecha`() {
        assertNull(next("2026-07-17", 0, 6, "2026-08-21"))
    }

    @Test fun `sin ancla utilizable no hay fecha`() {
        assertNull(next("", 7, 6, "2026-08-21"))
        assertNull(next("no-es-una-fecha", 7, 6, "2026-08-21"))
    }

    @Test fun `acepta una fecha ISO larga y se queda con el dia`() {
        assertEquals("2026-08-28", next("2026-07-17T16:16:09.768858+00:00", 7, 6, "2026-08-21"))
    }

    @Test fun `la hora del dia no altera el resultado`() {
        val manana = day("2026-08-21") + 9 * 60 * 60 * 1000   // 09:00 UTC
        val noche  = day("2026-08-21") + 23 * 60 * 60 * 1000  // 23:00 UTC
        assertEquals(
            AiringSchedule.nextAirDate("2026-07-17", 7, 6, manana),
            AiringSchedule.nextAirDate("2026-07-17", 7, 6, noche)
        )
    }

    @Test fun `cadencia no semanal`() {
        // Quincenal: ancla 01/08, 2 episodios (01 y 15), hoy 21 → el siguiente es el 29.
        assertEquals("2026-08-29", next("2026-08-01", 14, 2, "2026-08-21"))
    }

    @Test fun `daysBetween ignora la hora`() {
        assertEquals(1, AiringSchedule.daysBetween(day("2026-08-21") + 82_800_000, day("2026-08-22")))
        assertEquals(0, AiringSchedule.daysBetween(day("2026-08-21"), day("2026-08-21") + 3_600_000))
    }
}
