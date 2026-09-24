package com.animeav1.data

import android.content.Context
import android.util.LruCache
import com.animeav1.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InterruptedIOException
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object AnimeRepository {

    const val BASE_URL   = "https://animeav1.com"
    const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:120.0) Gecko/20100101 Firefox/120.0"
    const  val PAGE_SIZE         = 20

    // TTL constants
    private const val SERIES_TTL   = 30 * 60 * 1000L   // 30 min
    private const val EMBED_TTL    = 10 * 60 * 1000L   // 10 min
    private const val STREAM_TTL   = 10 * 60 * 1000L   // 10 min
    private const val SCHEDULE_TTL = 60 * 60 * 1000L   //  1 hour

    /**
     * Tope TOTAL (DNS + conexión + TLS + respuesta) para comprobar la playlist de Zilla antes de
     * reproducir. ⚠️ Es más estricto que media3 a propósito, no igual: media3 da 8 s de conexión +
     * 8 s de lectura por intento y reintenta. Un Zilla vivo pero lentísimo (más de 8 s para una
     * playlist de pocos KB) se daría por caído, y lo que cuesta es solo el orden: HLS sigue en el
     * panel y en la cadena de fallback. Con Zilla caído, Cloudflare tarda ~20 s en dar el 522.
     */
    private const val PLAYLIST_PROBE_TIMEOUT_MS = 8_000L

    /**
     * Tope TOTAL para sacar la URL de la página de un embed, contando todos los saltos (Voe son
     * dos). Lo normal es ~1,3 s; sin tope, un nodo que acepta la conexión y no contesta se comía
     * 15 s de conexión o 20 s de lectura POR SALTO —y OkHttp reintenta con la otra IP— con el
     * usuario mirando "Probando Voe…" y sin watchdog que lo vigile (ese empieza con el player).
     */
    private const val RESOLVE_BUDGET_MS = 10_000L

    /** Saltos de redirección por JavaScript que se siguen en Voe (stub → página real, y margen). */
    private const val VOE_MAX_HOPS = 3

    /**
     * Cuánto se da por caído un proveedor que acaba de fallar: 10 min la primera vez y el doble
     * con cada fallo seguido, hasta 2 h; reproducir con él lo borra. Ver [markSourceFailed].
     */
    private const val FAILED_SOURCE_BASE_TTL = 10 * 60 * 1000L
    private const val FAILED_SOURCE_MAX_TTL  = 2 * 60 * 60 * 1000L

    // In-memory caches. LruCache (thread-safe) bounds memory by entry count; TTL is still
    // checked on read so stale entries are never served. Caps a never-revisited key from
    // living for the whole process lifetime.
    private const val CACHE_MAX = 128
    private val seriesCache = LruCache<String, Pair<Long, Series>>(CACHE_MAX)
    private val embedCache  = LruCache<String, Pair<Long, List<EmbedServer>>>(CACHE_MAX)
    private val streamCache = LruCache<String, Pair<Long, String>>(CACHE_MAX)
    @Volatile private var scheduleCache: Pair<Long, Map<String, List<ScheduleItem>>>? = null

    private lateinit var client: OkHttpClient

    /**
     * Para todo lo que lleva a una URL de vídeo: páginas de embed y la comprobación de la playlist
     * de Zilla. **Sin la caché de disco**, que en el cliente principal fuerza `max-age=300` sobre
     * toda respuesta buena: seguiría afirmando que un Zilla recién caído funciona, y serviría
     * durante cinco minutos páginas con URLs de un solo uso (YourUpload) o atadas a la IP y al ASN
     * (Voe) aunque ya no valgan. La reutilización ya la da [streamCache], en memoria. Cada llamada
     * lleva su propio tope ([execute]).
     */
    private val streamClient: OkHttpClient by lazy { client.newBuilder().cache(null).build() }

    /** Un fallo de un proveedor: cuándo fue y cuántos lleva seguidos. Ver [markSourceFailed]. */
    private data class SourceFailure(val at: Long, val streak: Int) {
        val ttl: Long get() =
            (FAILED_SOURCE_BASE_TTL shl (streak - 1).coerceIn(0, 10)).coerceAtMost(FAILED_SOURCE_MAX_TTL)
    }

    /**
     * host del embed → su último fallo. Ver [markSourceFailed]. Con `synchronized` y no un
     * `ConcurrentHashMap.compute`, que es de API 24 (minSdk 21).
     */
    private val failedSources = HashMap<String, SourceFailure>()

    /** Must be called once from Application.onCreate() before any network call. */
    fun init(context: Context) {
        val httpCacheDir = File(context.cacheDir, "http_cache")
        client = OkHttpClient.Builder()
            .cache(Cache(httpCacheDir, 50L * 1024 * 1024)) // 50 MB disk cache
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            // Attach User-Agent on all requests
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json,*/*")
                        .build()
                )
            }
            // Force OkHttp to cache successful responses for 5 min regardless of server headers
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.isSuccessful) {
                    response.newBuilder()
                        .removeHeader("Pragma")
                        .removeHeader("Cache-Control")
                        .header("Cache-Control", "public, max-age=300")
                        .build()
                } else response
            }
            .build()
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw Exception("HTTP ${res.code}")
            return res.body?.string() ?: throw Exception("Respuesta vacía")
        }
    }

    // ── Catálogo ──────────────────────────────────────────────────────────────

    suspend fun getCatalog(page: Int, search: String = "", filter: CatalogFilter = CatalogFilter()): CatalogPage = withContext(Dispatchers.IO) {
        val url = buildString {
            append("$BASE_URL/catalogo/__data.json?page=$page")
            if (search.isNotBlank())          append("&search=${URLEncoder.encode(search, "UTF-8")}")
            if (filter.category.isNotBlank()) append("&category=${filter.category}")
            if (filter.genre.isNotBlank())    append("&genre=${filter.genre}")
            if (filter.status.isNotBlank())   append("&status=${filter.status}")
            if (filter.order.isNotBlank())    append("&order=${filter.order}")
        }
        val data = SvelteKitDecoder.decode(get(url), "results")
            ?: throw Exception("No se pudo decodificar el catálogo")

        val results = mutableListOf<Anime>()
        val arr = data.optJSONArray("results") ?: return@withContext CatalogPage(results, 0)
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val cat  = item.optJSONObject("category")
            val title = item.optString("title")
            results.add(Anime(
                id       = item.optString("id"),
                title    = title,
                slug     = item.optString("slug"),
                // El catálogo NO trae duración ni nº de episodios, así que aquí solo puede
                // corregirse el tipo por el título; la ficha, que sí los trae, afina más. Ver
                // [MediaType].
                category = MediaType.refine(cat?.optString("name") ?: "", title),
                synopsis = item.optString("synopsis")
            ))
        }
        CatalogPage(results, data.optInt("total", 0))
    }

    // ── Serie ─────────────────────────────────────────────────────────────────

    suspend fun getSeries(slug: String): Series = withContext(Dispatchers.IO) {
        // Return cached entry if still fresh
        seriesCache.get(slug)?.let { (ts, series) ->
            if (System.currentTimeMillis() - ts < SERIES_TTL) return@withContext series
        }

        val data = SvelteKitDecoder.decode(get("$BASE_URL/media/$slug/__data.json"), "media")
            ?: throw Exception("No se pudo decodificar la serie")
        val m = data.optJSONObject("media")
            ?: throw Exception("Respuesta inválida")

        val episodes = mutableListOf<EpisodeRef>()
        val epArr = m.optJSONArray("episodes")
        if (epArr != null) {
            for (i in 0 until epArr.length()) {
                val ep = epArr.optJSONObject(i) ?: continue
                episodes.add(EpisodeRef(ep.optInt("number", i + 1), ep.optInt("id", 0)))
            }
        }
        episodes.sortBy { it.number }
        val cat    = m.optJSONObject("category")
        // Título en español: el sitio lo guarda en `aka` por idioma y a veces es el único sitio
        // donde dice que la obra es una película ("… la Película: Last Evolution Kizuna").
        val akaEs  = m.optJSONObject("aka")?.optString("es-419").orEmpty()
        val genres = mutableListOf<String>()
        m.optJSONArray("genres")?.let { arr ->
            for (i in 0 until arr.length()) genres.add(arr.optJSONObject(i)?.optString("name") ?: "")
        }
        val relations = mutableListOf<Relation>()
        m.optJSONArray("relations")?.let { arr ->
            for (i in 0 until arr.length()) {
                val rel  = arr.optJSONObject(i) ?: continue
                val dest = rel.optJSONObject("destination") ?: continue
                relations.add(Relation(
                    type      = rel.optInt("type", 10),
                    id        = dest.optInt("id", 0),
                    slug      = dest.optString("slug"),
                    title     = dest.optString("title"),
                    startDate = dest.optString("startDate").take(4)
                ))
            }
        }
        val series = Series(
            id            = m.optInt("id", 0),
            title         = m.optString("title", slug),
            slug          = m.optString("slug", slug),
            synopsis      = m.optString("synopsis"),
            // ⚠️ Tipo DEDUCIDO, no el que trae el sitio: ver [MediaType] (el sitio marca como
            // "TV Anime" hasta las películas).
            category      = MediaType.refine(
                category       = cat?.optString("name") ?: "",
                title          = m.optString("title", slug),
                aka            = akaEs,
                episodesCount  = m.optInt("episodesCount", episodes.size),
                runtimeMinutes = m.optInt("runtime", 0),
                status         = m.optInt("status", -1)
            ),
            genres        = genres.filter { it.isNotBlank() },
            status        = m.optInt("status", 0),
            score         = m.optDouble("score", 0.0),
            votes         = m.optInt("votes", 0),
            episodesCount = m.optInt("episodesCount", episodes.size),
            // Ancla + cadencia para calcular el próximo episodio. `nextDate` puede venir null en
            // las series terminadas, y entonces `startDate` sirve igual de ancla.
            airingAnchor  = (m.optString("nextDate").takeIf { it.isNotBlank() && it != "null" }
                ?: m.optString("startDate")).take(10),
            waitDays      = m.optInt("waitDays", 0),
            startDate     = (m.optString("startDate") ?: "").take(4),
            episodes      = episodes,
            relations     = relations
        )
        seriesCache.put(slug, System.currentTimeMillis() to series)
        series
    }

    // ── Embeds del episodio ───────────────────────────────────────────────────

    suspend fun getEmbeds(slug: String, number: Int): List<EmbedServer> = withContext(Dispatchers.IO) {
        val key = "$slug:$number"
        embedCache.get(key)?.let { (ts, data) ->
            if (System.currentTimeMillis() - ts < EMBED_TTL) return@withContext data
        }

        val data = SvelteKitDecoder.decode(
            get("$BASE_URL/media/$slug/$number/__data.json"), "episode"
        ) ?: return@withContext emptyList()

        // Ambas pistas (SUB y DUB), ya etiquetadas y sin los servidores no reproducibles.
        // El parseo vive en [EmbedParser] para poder testearlo con una captura real.
        val playable = EmbedParser.parse(data)
        embedCache.put(key, System.currentTimeMillis() to playable)
        playable
    }

    // ── Extracción de stream (Zilla, Voe, MP4Upload y similares) ──────────────

    suspend fun extractStreamUrl(embedUrl: String): String? = withContext(Dispatchers.IO) {
        streamCache.get(embedUrl)?.let { (ts, url) ->
            if (System.currentTimeMillis() - ts < STREAM_TTL) return@withContext url
        }

        val resolved = try {
            when {
                // Zilla "HLS": el .m3u8 es un transform directo del embed (sin JS ni token).
                "zilla-networks.com" in embedUrl -> resolveZilla(embedUrl)
                // ⚠️ Voe NUNCA cae al scraper genérico: su página real trae un mp4 señuelo que
                // `directUrlFrom` se tragaría (ver VoeParser).
                VoeParser.handles(embedUrl) -> resolveVoe(embedUrl)
                // El resto: scrapear la URL directa del HTML del embed.
                else -> scrapeStreamUrl(embedUrl)
            }
        } catch (e: Exception) {
            null
        }

        if (resolved != null) streamCache.put(embedUrl, System.currentTimeMillis() to resolved)
        resolved
    }

    /**
     * player.zilla-networks.com/play/<id>  →  /m3u8/<id>  (id de 32 chars), y solo si esa playlist
     * responde de verdad.
     *
     * ⚠️ Sin la comprobación, esto no fallaba NUNCA: es un cambio de texto en la URL. Con Zilla
     * caído (desde ~2026-09-21 su origen no contesta y Cloudflare da 522 a los 20 s) el reproductor
     * recibía una URL "buena", media3 se quedaba en BUFFERING cortando cada intento a los 8 s, y
     * solo el watchdog lo mataba a los 25 s — en cada episodio, porque HLS es lo primero de la
     * lista. Ahora falla aquí, como cualquier otra fuente, y el fallback salta enseguida.
     */
    private fun resolveZilla(embedUrl: String): String? {
        val playlist = StreamUrlParser.zillaM3u8(embedUrl) ?: return null
        return playlist.takeIf { playlistAnswers(it, StreamUrlParser.refererOf(embedUrl) ?: BASE_URL) }
    }

    /** GET corto a la playlist con las mismas cabeceras que mandará el reproductor. */
    private fun playlistAnswers(url: String, referer: String): Boolean {
        val req = Request.Builder().url(url)
            .header("Referer", referer)
            // El interceptor global pone `application/json,*/*`; media3 no lo manda.
            .header("Accept", "*/*")
            .header("Sec-Fetch-Site", StreamUrlParser.secFetchSite(url, referer))
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Dest", "empty")
            .build()
        return execute(req, deadlineAt = System.currentTimeMillis() + PLAYLIST_PROBE_TIMEOUT_MS) { res ->
            res.code == 200 && StreamUrlParser.looksLikePlaylist(res.body?.string().orEmpty())
        }
    }

    /**
     * Voe: el embed es un stub que redirige por JavaScript a un dominio rotatorio, y la URL del
     * vídeo va ofuscada dentro de la página real. Todo el parseo vive en [VoeParser].
     *
     * La URL que sale va atada a la IP y al ASN de quien la pidió y caduca a las 4 h (`e=14400`),
     * así que los 10 min de [STREAM_TTL] quedan muy por dentro.
     */
    private fun resolveVoe(embedUrl: String): String? {
        val deadlineAt = System.currentTimeMillis() + RESOLVE_BUDGET_MS   // para TODOS los saltos
        var url = embedUrl
        var referer = BASE_URL
        repeat(VOE_MAX_HOPS) {
            val html = fetchHtml(url, referer, deadlineAt)
            VoeParser.streamFrom(html)?.let { return it }
            val next = VoeParser.redirectFrom(html) ?: return null
            referer = url
            url = next
        }
        return null
    }

    /**
     * Descarga el HTML del embed y busca en él un .m3u8/.mp4 directo. El parseo vive en
     * [StreamUrlParser] (sin red ni Android) para poder testearlo con fixtures capturados:
     * los regex son la pieza que más veces se ha roto.
     */
    private fun scrapeStreamUrl(embedUrl: String): String? =
        StreamUrlParser.streamFromEmbedPage(
            fetchHtml(embedUrl, BASE_URL, System.currentTimeMillis() + RESOLVE_BUDGET_MS)
        )

    private fun fetchHtml(url: String, referer: String, deadlineAt: Long): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .build()
        return execute(req, deadlineAt) { it.body?.string() ?: "" }
    }

    /**
     * Ejecuta [req] en [streamClient] con un tope TOTAL que acaba en [deadlineAt]. Es el
     * `callTimeout` de OkHttp, que sí corta una llamada bloqueada (un `withTimeout` de corrutinas
     * no: el hilo de IO seguiría esperando al socket).
     */
    private fun <T> execute(req: Request, deadlineAt: Long, read: (okhttp3.Response) -> T): T {
        val left = deadlineAt - System.currentTimeMillis()
        if (left <= 0) throw InterruptedIOException("sin tiempo para ${req.url.host}")
        val call = streamClient.newCall(req)
        call.timeout().timeout(left, TimeUnit.MILLISECONDS)
        return call.execute().use(read)
    }

    // ── Fuentes que han fallado hace poco ────────────────────────────────────

    /**
     * Apunta que el proveedor de [embedUrl] acaba de fallar (no resolvió, se quedó callado o media3
     * dio error). Se guarda por HOST y no por embed: cuando Zilla se cae, se caen todos sus
     * episodios y las dos pistas a la vez.
     *
     * Solo sirve para ORDENAR: el reproductor deja esas fuentes para el final al elegir con qué
     * arrancar y a qué saltar, así que el episodio siguiente no vuelve a esperar a un servidor que
     * lleva minutos sin contestar. No esconde nada — siguen en el panel y en la cadena de fallback
     * —, no toca la preferencia guardada de la serie y se olvida sola.
     *
     * ⚠️ **Cuánto se olvida crece con cada fallo seguido** (10 min, 20, 40… hasta 2 h) y vuelve a
     * cero en cuanto reproduce ([markSourceWorking]). Con un plazo fijo de 10 min, más corto que un
     * episodio, en un maratón la marca ya había caducado al llegar el siguiente y cada episodio
     * volvía a esperar 8 s a un Zilla muerto. Un plazo fijo largo sería peor en el otro sentido: un
     * corte de red de un momento dejaría a Voe al final de la cola durante horas.
     *
     * Además olvida la URL ya resuelta de ESE embed: si no, [streamCache] la seguiría entregando
     * 10 min más (sin volver a comprobar la playlist de Zilla, o con la URL de Voe atada a una IP que
     * ya no es la nuestra) y "Reintentar" no podría salir de ahí.
     */
    fun markSourceFailed(embedUrl: String) {
        streamCache.remove(embedUrl)
        val host = hostOf(embedUrl) ?: return
        val now = System.currentTimeMillis()
        synchronized(failedSources) {
            failedSources[host] = SourceFailure(now, (failedSources[host]?.streak ?: 0) + 1)
        }
    }

    /** El proveedor de [embedUrl] acaba de reproducir: deja de contar como caído. */
    fun markSourceWorking(embedUrl: String) {
        val host = hostOf(embedUrl) ?: return
        synchronized(failedSources) { failedSources.remove(host) }
    }

    /**
     * Si el proveedor de [embedUrl] ha fallado dentro de su plazo. Al caducar NO se borra la
     * entrada: la racha tiene que sobrevivir para que el siguiente fallo pese el doble.
     */
    fun recentlyFailed(embedUrl: String): Boolean {
        val host = hostOf(embedUrl) ?: return false
        val f = synchronized(failedSources) { failedSources[host] } ?: return false
        return System.currentTimeMillis() - f.at < f.ttl
    }

    private fun hostOf(url: String): String? =
        runCatching { URI(url).host?.lowercase() }.getOrNull()

    // ── Horario ───────────────────────────────────────────────────────────────

    suspend fun getSchedule(): Map<String, List<ScheduleItem>> = withContext(Dispatchers.IO) {
        scheduleCache?.let { (ts, data) ->
            if (System.currentTimeMillis() - ts < SCHEDULE_TTL) return@withContext data
        }

        val data = SvelteKitDecoder.decode(get("$BASE_URL/horario/__data.json"), "media")
            ?: return@withContext emptyMap()

        val arr = data.optJSONArray("media") ?: return@withContext emptyMap()

        val dayOrder = listOf("Lunes","Martes","Miércoles","Jueves","Viernes","Sábado","Domingo")
        val grouped  = mutableMapOf<String, MutableList<ScheduleItem>>()
        dayOrder.forEach { grouped[it] = mutableListOf() }

        for (i in 0 until arr.length()) {
            val item  = arr.optJSONObject(i) ?: continue
            val ep    = item.optJSONObject("latestEpisode")
            val cat   = item.optJSONObject("category")
            val dateStr = ep?.optString("createdAt") ?: item.optString("startDate") ?: ""
            val day   = parseDayOfWeek(dateStr)
            val schedTitle = item.optString("title")
            val sched = ScheduleItem(
                id                  = item.optInt("id", 0),
                title               = schedTitle,
                slug                = item.optString("slug"),
                category            = MediaType.refine(cat?.optString("name") ?: "", schedTitle),
                latestEpisodeNumber = ep?.optInt("number", 0) ?: 0,
                latestEpisodeDate   = dateStr.take(10),
                dayOfWeek           = day
            )
            grouped.getOrPut(day) { mutableListOf() }.add(sched)
        }
        val result = dayOrder.filter { grouped[it]?.isNotEmpty() == true }
            .associateWith { grouped[it]!! }
        scheduleCache = System.currentTimeMillis() to result
        result
    }

    private val ES_DAYS = mapOf(
        "Monday"    to "Lunes",
        "Tuesday"   to "Martes",
        "Wednesday" to "Miércoles",
        "Thursday"  to "Jueves",
        "Friday"    to "Viernes",
        "Saturday"  to "Sábado",
        "Sunday"    to "Domingo"
    )

    private fun parseDayOfWeek(iso: String): String {
        return try {
            val date = iso.take(10)
            val (y, m, d) = date.split("-").map { it.toInt() }
            val t = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)
            val yr = if (m < 3) y - 1 else y
            val dow = (yr + yr/4 - yr/100 + yr/400 + t[m-1] + d) % 7
            val en = listOf("Sunday","Monday","Tuesday","Wednesday","Thursday","Friday","Saturday")[dow]
            ES_DAYS[en] ?: en
        } catch (e: Exception) { "Otros" }
    }
}
