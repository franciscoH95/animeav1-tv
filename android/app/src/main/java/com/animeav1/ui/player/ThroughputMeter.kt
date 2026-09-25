package com.animeav1.ui.player

/**
 * Caudal de red de un stream: bytes recibidos entre el tiempo que ha habido una transferencia
 * ABIERTA. Lo que tarda en abrirse cada conexión (DNS, TCP, el handshake TLS de hasta 42 s de un
 * nodo de MP4Upload en racha lenta) no cuenta: eso ya lo vigila el watchdog por silencio de red. Esto
 * mide otra cosa, si el nodo SIRVE a la velocidad que el vídeo necesita una vez que contesta.
 *
 * Lo alimenta el hilo de carga de media3 (un `TransferListener`) y lo lee el principal: por eso todo
 * va sincronizado. El reloj entra por parámetro para poder testearlo.
 */
internal class ThroughputMeter {

    /** Tamaño total del fichero, si las cabeceras lo han dicho (0 = no se sabe). */
    @Volatile var fileBytes = 0L

    /** Inicio de la transferencia en curso, o -1 si no hay ninguna abierta. */
    private var openSince = -1L
    private var closedMs = 0L
    private var bytes = 0L

    @Synchronized fun start(now: Long) {
        if (openSince < 0) openSince = now
    }

    @Synchronized fun add(count: Int) {
        bytes += count
    }

    @Synchronized fun end(now: Long) {
        if (openSince < 0) return
        closedMs += (now - openSince).coerceAtLeast(0)
        openSince = -1
    }

    /** Tiempo total con una transferencia abierta. */
    @Synchronized fun activeMs(now: Long): Long =
        closedMs + if (openSince >= 0) (now - openSince).coerceAtLeast(0) else 0

    @Synchronized fun bytesPerSecond(now: Long): Long {
        val ms = activeMs(now)
        return if (ms <= 0) 0 else bytes * 1000 / ms
    }
}
