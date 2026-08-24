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

    // In-memory caches. LruCache (thread-safe) bounds memory by entry count; TTL is still
    // checked on read so stale entries are never served. Caps a never-revisited key from
    // living for the whole process lifetime.
    private const val CACHE_MAX = 128
    private val seriesCache = LruCache<String, Pair<Long, Series>>(CACHE_MAX)
    private val embedCache  = LruCache<String, Pair<Long, List<EmbedServer>>>(CACHE_MAX)
    private val streamCache = LruCache<String, Pair<Long, String>>(CACHE_MAX)
    @Volatile private var scheduleCache: Pair<Long, Map<String, List<ScheduleItem>>>? = null

    private lateinit var client: OkHttpClient

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

    // ── Extracción de stream (MP4Upload y similares) ──────────────────────────

    suspend fun extractStreamUrl(embedUrl: String): String? = withContext(Dispatchers.IO) {
        streamCache.get(embedUrl)?.let { (ts, url) ->
            if (System.currentTimeMillis() - ts < STREAM_TTL) return@withContext url
        }

        val resolved = try {
            when {
                // Zilla "HLS": el .m3u8 es un transform directo del embed (sin JS ni token).
                "zilla-networks.com" in embedUrl -> resolveZilla(embedUrl)
                // El resto: scrapear la URL directa del HTML del embed.
                else -> scrapeStreamUrl(embedUrl)
            }
        } catch (e: Exception) {
            null
        }

        if (resolved != null) streamCache.put(embedUrl, System.currentTimeMillis() to resolved)
        resolved
    }

    /** player.zilla-networks.com/play/<id>  →  /m3u8/<id>  (id de 32 chars). */
    private fun resolveZilla(embedUrl: String): String? = StreamUrlParser.zillaM3u8(embedUrl)

    /**
     * Descarga el HTML del embed y busca en él un .m3u8/.mp4 directo. El parseo vive en
     * [StreamUrlParser] (sin red ni Android) para poder testearlo con fixtures capturados:
     * los regex son la pieza que más veces se ha roto.
     */
    private fun scrapeStreamUrl(embedUrl: String): String? {
        val req = Request.Builder().url(embedUrl)
            .header("User-Agent", USER_AGENT)
            .header("Referer", BASE_URL)
            .build()
        val html = client.newCall(req).execute().use { it.body?.string() ?: "" }
        return StreamUrlParser.directUrlFrom(html)
    }

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
