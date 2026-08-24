package com.animeav1.ui.backup

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Formateo compartido por la Activity y su adapter. Locale fijo: los nombres de fichero no deben
 *  cambiar de forma según el idioma del aparato. */
internal object BackupFormat {

    private val FILE_STAMP = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
    private val DISPLAY    = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    fun fileStamp(ms: Long): String = FILE_STAMP.format(Date(ms))

    fun dateTime(ms: Long): String = DISPLAY.format(Date(ms))

    fun size(bytes: Long): String = when {
        bytes < 1024        -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else                -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
