package com.animeav1.ui.player

import com.animeav1.data.model.AudioTrack
import com.animeav1.data.model.EmbedServer

/**
 * Mitad PURA de la selección de fuentes de un episodio: con cuál se arranca, cuál sigue cuando una
 * falla, quién la ha elegido y qué viaja al episodio siguiente. Sin red, sin Room y sin Android:
 * [PlayerActivity] le cuenta lo que pasa y ejecuta los efectos (resolver, marcar fallos, guardar).
 *
 * @param penaltyOf lo que le queda a la marca de fallo de un embed, por su URL (0 = no ha fallado).
 * @param av1Hardware si este aparato decodifica AV1 Main10 por hardware (ver [PlaybackPolicy.rank]).
 */
class SourceSelector(
    private val penaltyOf: (embedUrl: String) -> Long,
    private val av1Hardware: () -> Boolean
) {

    /**
     * Una preferencia del usuario (pista + servidor) que `series_prefs` puede no tener aún.
     * `server == null`: solo la pista — el servidor elegido falló al probarlo (ver [onFailed]).
     */
    data class CarriedPref(val server: String?, val audio: AudioTrack)

    /** Los servidores del episodio, en el orden del sitio. */
    var embeds: List<EmbedServer> = emptyList()

    /** La última fuente pedida; puede ir por delante de lo que suena (un cambio que aún resuelve). */
    var selected: EmbedServer? = null
        private set

    /**
     * La elección del usuario más nueva que conoce este episodio y que `series_prefs` puede no tener
     * todavía: la del panel de un episodio ANTERIOR (se guarda al sonar, y el usuario le dio a ⏭
     * antes), o la que se acaba de guardar aquí ([onSourceWorking]; así el episodio siguiente no
     * depende de que la escritura en Room haya terminado). ⚠️ Vale exactamente como si estuviera
     * guardada: manda sobre la tabla, y sigue viajando de episodio en episodio aunque aquí no se
     * pueda poner —este episodio no tiene ese servidor o su host acaba de fallar—, igual que una
     * preferencia guardada no se borra porque un episodio no la tenga.
     * ⚠️ Si se PRUEBA y falla, pierde el servidor y se queda en la pista ([onFailed]): el doblaje que
     * eligió el usuario sigue, pero un servidor que no ha sonado nunca no se fija para el resto del
     * maratón (con Zilla caído, un toque en HLS + ⏭ lo dejaba esperándose en cada episodio).
     * Sobrevive a la recreación de la Activity por `onSaveInstanceState`: releerla del intent
     * pisaría una elección más nueva guardada desde entonces.
     */
    var carried: CarriedPref? = null
        private set

    private var preferredServer: String? = null
    private var preferredAudio: AudioTrack? = null

    /** Preferencia guardada de esta serie; null = no hay fila en `series_prefs`. */
    private var savedAudio: String? = null
    private var savedServer: String? = null

    /**
     * Fuentes ya intentadas para ESTE episodio, para que el fallback automático no vuelva a probar la
     * que acaba de fallar ni entre en bucle. Se vacía al cambiar de episodio (la Activity se recrea).
     */
    private val tried = mutableSetOf<EmbedServer>()

    /**
     * Fuente elegida en el panel que todavía está resolviendo. Cuando su URL llega, pasa al stream
     * ([pickedStream]) y se guarda en `series_prefs` al llegar a READY ([onSourceWorking]), no al
     * pulsarla: guardarla al pulsar fijaba para toda la serie un servidor que quizá ni contestaba —
     * un solo toque en HLS con Zilla caído y cada episodio de esa serie arrancaba esperando a HLS.
     */
    private var pending: EmbedServer? = null

    /**
     * El stream actual si es la elección del usuario y aún no se ha guardado: se guarda en su primer
     * READY. Va CON el stream y no en un hueco suelto, porque la elección y el READY no llegan en
     * orden: el player viejo sigue vivo mientras la nueva resuelve (rebuffers, seeks), y el usuario
     * puede elegir otra encima antes de que la primera arranque. Con un hueco suelto, que la segunda
     * fallara borraba la primera —que sí sonó— y no se guardaba.
     */
    private var pickedStream: EmbedServer? = null

    /**
     * Última fuente que llegó a READY en este episodio. Si el usuario cambia a otra desde el panel y
     * esa no arranca, el fallback vuelve AQUÍ antes que a una desconocida: está en [tried], así que
     * sin esto la cadena la saltaba y podía acabar en "Ninguna fuente responde" con una fuente que
     * funcionaba hacía un momento.
     */
    private var lastWorking: EmbedServer? = null

    /**
     * La fuente que el usuario ha pulsado en el panel en ESTE episodio. Con ella (y con [retried]) se
     * tiene paciencia de verdad; una preferencia guardada o traída no basta —ver [insisted]—.
     */
    private var clicked: EmbedServer? = null

    /**
     * La fuente que el usuario ha mandado reintentar ("Reintentar" en la pantalla de error): cuenta
     * como elegida por él —paciencia de 50 s, y no se salta aunque su nodo esté marcado como lento—,
     * pero NO se guarda como preferencia de la serie (reintentar no es elegir).
     */
    private var retried: EmbedServer? = null

    /** Hay una elección del usuario resolviendo todavía. */
    val hasPendingPick: Boolean get() = pending != null

    // ── Arranque del episodio ─────────────────────────────────────────────────────────────────

    /** Lo que trae el intent. Solo cuenta como elección traída si viene marcado ([fromUser]). */
    fun startFromIntent(server: String?, audio: String?, fromUser: Boolean) {
        preferredServer = server
        preferredAudio = trackOf(audio)
        carried = if (fromUser) carriedOf(server, preferredAudio?.name) else null
    }

    /** Al recrearse la Activity: pisa a los extras, que pueden ser más viejos que lo elegido desde entonces. */
    fun restoreCarried(server: String?, audio: String?) {
        carried = carriedOf(server, audio)
    }

    /**
     * Aplica la preferencia guardada de la serie (`null` si no hay fila).
     *
     * ⚠️ La preferencia MANDA sobre los extras del intent, no al revés. Los extras solo saben del
     * episodio anterior y pueden traer lo que dejó un fallback o un pick automático, que NO es lo que
     * el usuario eligió: leyéndolos primero, un stall de 25 s en YourUpload dejaba el resto del
     * maratón en HLS (AV1), y un episodio publicado solo en SUB dejaba en subtitulado una serie que
     * se veía DOBLADA.
     * ⚠️ Salvo que haya una elección del usuario que la tabla aún no tiene ([carried]): esa es más
     * nueva. Elegir "Voe (Doblado)" y darle a ⏭ mientras cargaba abría el episodio siguiente en el
     * subtitulado de siempre.
     */
    fun applySaved(audio: String?, server: String?) {
        savedAudio = audio
        savedServer = server
        val c = carried
        if (c != null) {
            preferredAudio = c.audio
            // Sin servidor (el traído falló al probarlo), el de la tabla vale si es de esa pista.
            preferredServer = c.server ?: server?.takeIf { audio == c.audio.name && it.isNotBlank() }
        } else {
            server?.takeIf { it.isNotBlank() }?.let { preferredServer = it }
            trackOf(audio)?.let { preferredAudio = it }
        }
    }

    /**
     * Servidor con el que arranca el episodio. Respeta lo que el usuario venía usando en el
     * episodio anterior — servidor **y** pista de audio: si estaba viendo el doblaje, el
     * siguiente episodio no debe saltar al subtitulado solo porque SUB va primero en la lista.
     */
    fun pickDefault(embeds: List<EmbedServer>): EmbedServer {
        val byName = { e: EmbedServer -> preferredServer?.equals(e.server, ignoreCase = true) == true }
        // ⚠️ La pista se decide ANTES de mirar qué ha fallado: la preferida si el episodio la trae y,
        // si no, la primera (SUB). Ordenando todo junto, sin preferencia de pista y con los hosts
        // de SUB marcados como caídos, el primer "vivo" podía ser uno de DOBLADO: el episodio
        // arrancaba doblado sin que nadie lo pidiera.
        val track = preferredAudio?.takeIf { a -> embeds.any { it.audio == a } } ?: embeds.first().audio
        // Dentro de la pista, lo que ha fallado hace poco va al final (ver
        // AnimeRepository.markSourceFailed): con un proveedor caído, cada episodio empezaba
        // esperando a que volviera a fallar. Solo reordena, y entre las que fallaron va última la
        // que lleva más tiempo muerta.
        val penalty = embeds.associateWith { penaltyOf(it.url) }
        // Y a igualdad de fallos, MP4Upload delante si este aparato hace AV1 por hardware
        // (PlaybackPolicy.rank); si no, el orden del sitio. Estable.
        val inTrack = embeds.filter { it.audio == track }
            .sortedWith(PlaybackPolicy.order({ penalty.getValue(it) }, av1Hardware()))
        return inTrack.firstOrNull { byName(it) && penalty.getValue(it) == 0L }
            ?: inTrack.firstOrNull { penalty.getValue(it) == 0L }
            // Si han fallado TODAS (un corte de red las marca a la vez), la guardada sigue siendo
            // la mejor apuesta; en el orden del sitio iría primero el Zilla que lleva días caído.
            ?: inTrack.firstOrNull { byName(it) }
            ?: inTrack.first()
    }

    // ── Lo que va pasando ─────────────────────────────────────────────────────────────────────

    /**
     * Se ha pedido [embed]. También cuenta como elección del usuario la traída de otro episodio
     * ([carried]), llegue por el pick por defecto, por el fallback o por "Reintentar": si suena, se
     * guarda. Si este episodio no la tiene, lo que se ponga no la iguala y no se guarda nada.
     *
     * @param fromUser true SOLO si la ha elegido el usuario en el panel.
     */
    fun onSelected(embed: EmbedServer, fromUser: Boolean) {
        selected = embed
        if (fromUser) clicked = embed
        if (fromUser || matchesCarried(embed)) pending = embed
        tried += embed
    }

    /**
     * Ha llegado la URL de [embed]. Una URL solo llega para la última fuente pedida (cada petición
     * cancela la anterior), así que si coincide con la elección pendiente, ESTE stream es la elección.
     */
    fun onStreamResolved(embed: EmbedServer) {
        pickedStream = embed.takeIf { it == pending }
        if (pickedStream != null) pending = null
    }

    /** Se ha olvidado el stream actual: la marca de "es la elección" va con él. */
    fun onStreamForgotten() {
        pickedStream = null
    }

    /**
     * [embed] acaba de llegar a READY: pasa a ser a la que volver si la siguiente no arranca.
     *
     * @return true si es la elección del usuario y hay que guardarla en `series_prefs`. Solo si ESTE
     *   stream es la elección: un READY del stream viejo mientras la elección resuelve (rebuffer,
     *   seek) no la toca, y lo que trajo el fallback no es una preferencia del usuario.
     */
    fun onSourceWorking(embed: EmbedServer): Boolean {
        lastWorking = embed
        if (pickedStream == null) return false
        pickedStream = null
        // Pasa a ser lo que viaja: es lo mismo que acaba de ir a la tabla, pero no depende de que
        // esa escritura (fire-and-forget) termine antes de que el episodio siguiente la lea.
        carried = CarriedPref(embed.server, embed.audio)
        return true
    }

    /** [embed] no ha resuelto, se ha quedado callada o media3 ha dado error. */
    fun onFailed(embed: EmbedServer) {
        if (pending == embed) pending = null
        // La elección traída se ha probado y no ha sonado: se queda la pista, se va el servidor.
        if (matchesCarried(embed)) carried = carried?.copy(server = null)
        // La que acaba de fallar ya no es "la que funcionaba": volver a ella costaría otros 25 s
        // de watchdog antes de probar las que quedan sin probar.
        if (lastWorking == embed) lastWorking = null
    }

    /**
     * Volver a pedir la fuente que ESTÁ sonando ha fallado (un corte de un momento): no demuestra que
     * esté caída, así que no cuenta como fallo; solo deja de estar pendiente.
     */
    fun abandonPick(embed: EmbedServer) {
        if (pending == embed) pending = null
    }

    /** Un cambio a mitad de reproducción ha fallado: la selección vuelve a la que sigue sonando. */
    fun restoreSelection(embed: EmbedServer?) {
        selected = embed
    }

    /**
     * "Reintentar" a mano es "vuelve a intentarlo todo": se olvida lo ya probado para que el fallback
     * pueda recorrer la pista otra vez. Sin esto, tras agotar la cadena por un corte de red pasajero,
     * cada pulsación reintentaba SOLO la última fuente que falló.
     */
    fun onRetry(embed: EmbedServer) {
        tried.clear()
        retried = embed
    }

    // ── Decisiones ────────────────────────────────────────────────────────────────────────────

    /**
     * Siguiente fuente sin probar **de la misma pista de audio** que [failed].
     *
     * De la misma pista a propósito: caer del doblaje al subtitulado por un CDN caído cambiaría el
     * idioma sin avisar, que es peor que el error. Si esa pista se agota, se rinde y pregunta.
     *
     * Antes que ninguna desconocida, la que estaba reproduciendo ([lastWorking]); y entre las
     * no probadas, las que han fallado hace poco van al final.
     */
    fun nextUntried(failed: EmbedServer): EmbedServer? {
        lastWorking?.takeIf { it != failed && it.audio == failed.audio }?.let { return it }
        return embeds
            .filter { it.audio == failed.audio && it !in tried }
            .sortedWith(PlaybackPolicy.order({ penaltyOf(it.url) }, av1Hardware()))
            .firstOrNull()
    }

    /** Como [nextUntried], pero para pasar a ella de verdad: a la que funcionaba se vuelve UNA vez. */
    fun takeNext(failed: EmbedServer): EmbedServer? {
        val next = nextUntried(failed) ?: return null
        if (next == lastWorking) lastWorking = null
        return next
    }

    /**
     * La elección del panel que aún resuelve y ante la que debe ceder el fallo de [failed], si la hay:
     * un fallo automático del stream VIEJO no puede pisar lo que el usuario acaba de pedir.
     */
    fun pickToDeferTo(failed: EmbedServer?): EmbedServer? {
        val pick = pending ?: return null
        return pick.takeIf { selected == it && failed != it }
    }

    /**
     * La elección del usuario que `series_prefs` aún no tiene, si la hay: la que está resolviendo,
     * la que suena sin haber llegado a guardarse, o la traída de un episodio anterior.
     * ⚠️ Se decide AQUÍ, al cambiar de episodio, y no con un "última elección" apuntado al elegir:
     * así una elección sustituida por otra no viaja, una que falló tampoco ([onFailed] ya la quitó)
     * y la que sigue sonando tras fallar la siguiente sí.
     */
    fun unsavedChoice(): CarriedPref? =
        (pending ?: pickedStream)?.let { CarriedPref(it.server, it.audio) } ?: carried

    /**
     * [embed] lo ha pedido el usuario en ESTE episodio: pulsándolo en el panel o con "Reintentar".
     * Solo eso compra paciencia con un nodo de MP4Upload lento (50 s de silencio, no saltarlo aunque
     * esté marcado, no cambiar de fuente por falta de caudal). Una preferencia —guardada o traída del
     * episodio anterior— dice "MP4Upload cuando funcione": con ella un fichero en `a3` en plena racha
     * dejaba "Cargando vídeo desde MP4Upload" 40 s en pantalla (medido en una Philips OLED706).
     */
    fun insisted(embed: EmbedServer): Boolean = embed == clicked || embed == retried

    /** [embed] lo eligió el usuario: en el panel, o es su preferencia (guardada o traída). */
    fun chosenByUser(embed: EmbedServer?): Boolean {
        embed ?: return false
        if (pickedStream != null || matchesCarried(embed) || embed == retried) return true
        return savedAudio == embed.audio.name && savedServer.equals(embed.server, ignoreCase = true)
    }

    /** [embed] es exactamente la elección traída: mismo servidor Y misma pista. */
    private fun matchesCarried(embed: EmbedServer): Boolean {
        val c = carried ?: return false
        return c.audio == embed.audio && c.server?.equals(embed.server, ignoreCase = true) == true
    }

    /** La pista es obligatoria; el servidor no (una elección que falló viaja solo como pista). */
    private fun carriedOf(server: String?, audio: String?): CarriedPref? {
        val track = trackOf(audio) ?: return null
        return CarriedPref(server?.takeIf { it.isNotBlank() }, track)
    }

    private fun trackOf(name: String?): AudioTrack? = AudioTrack.values().firstOrNull { it.name == name }
}
