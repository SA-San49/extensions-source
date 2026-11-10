package eu.kanade.tachiyomi.animeextension.en.anv

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap

/**
 * Anv (https://anv.to) — AniList-backed anime catalog with its own HLS edge.
 *
 * Metadata comes from `POST /api/catalog` with named operations
 * (`BrowseAnime`, `SearchAnime`, `AnimeDetail`), newest episode releases from
 * `GET /api/recent-episodes` and streams from `POST /api/episode`.
 *
 * Playback is gated by the `shiro_watch` cookie that only watch pages issue, and
 * every segment is served behind a fake image header, so all player-facing URLs
 * are routed through [AnvProxy], which replays the headers and cleans the
 * segments.
 */
class Anv : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "Anv"

    override val lang = "en"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN, PREF_DOMAIN_DEFAULT)!!
            .trim()
            .trimEnd('/')
            .ifEmpty { PREF_DOMAIN_DEFAULT }

    private val proxy by lazy { AnvProxy(client) }

    /** Details/episodes are fetched separately by the app; keep them for the player. */
    private val mediaCache = ConcurrentHashMap<String, Media>()

    private var cachedCookie: String? = null
    private var cookieExpiresAt = 0L

    // ============================== Popular ==============================

    override fun popularAnimeRequest(page: Int): Request = catalogRequest(
        "BrowseAnime",
        buildJsonObject {
            put("page", page)
            put("perPage", PAGE_SIZE)
            putJsonArray("sort") { add(JsonPrimitive("POPULARITY_DESC")) }
        },
    )

    override fun popularAnimeParse(response: Response): AnimesPage = parsePage(response)

    // ============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/recent-episodes", apiHeaders())

    override fun latestUpdatesParse(response: Response): AnimesPage = parseRecent(response)

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        // The recently released feed is a single, unpaginated list.
        if (page > 1) return AnimesPage(emptyList(), false)
        return super.getLatestUpdates(page)
    }

    // ============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        var sort: String? = null
        var format: String? = null
        var status: String? = null
        var season: String? = null
        var year: Int? = null
        val genres = mutableListOf<String>()

        filters.forEach { filter ->
            when (filter) {
                is AnvFilters.SortFilter -> sort = filter.selected
                is AnvFilters.FormatFilter -> format = filter.selected
                is AnvFilters.StatusFilter -> status = filter.selected
                is AnvFilters.SeasonFilter -> season = filter.selected
                is AnvFilters.YearFilter -> year = filter.selected
                is AnvFilters.GenreFilter -> genres += filter.selected
                else -> Unit
            }
        }

        val search = query.trim()
        val variables = buildJsonObject {
            put("page", page)
            put("perPage", PAGE_SIZE)
            if (search.isNotEmpty()) put("search", search)
            if (sort != null) putJsonArray("sort") { add(JsonPrimitive(sort!!)) }
            if (format != null) put("format", format)
            if (status != null) put("status", status)
            if (season != null) put("season", season)
            if (year != null) put("seasonYear", year)
            if (genres.isNotEmpty()) putJsonArray("genres") { genres.forEach { add(JsonPrimitive(it)) } }
        }

        return catalogRequest("BrowseAnime", variables)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parsePage(response)

    override fun getFilterList(): AnimeFilterList = AnvFilters.getFilterList()

    // ========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = detailRequest(slugOf(anime.url))

    override fun animeDetailsParse(response: Response): SAnime {
        val media = parseDetail(response)
        media.slug?.let { mediaCache[it] = media }

        return mediaToSAnime(media).apply {
            description = media.description?.let { Jsoup.parse(it).text().trim() }?.takeIf { it.isNotEmpty() }
            genre = media.genres?.joinToString(", ")?.takeIf { it.isNotEmpty() }
            status = media.status.toSAnimeStatus()
            author = media.studios?.nodes?.mapNotNull { it.name }?.joinToString(", ")?.takeIf { it.isNotEmpty() }
        }
    }

    override fun getAnimeUrl(anime: SAnime): String = absolutize(anime.url) ?: anime.url

    // ============================== Episodes =============================

    override fun episodeListRequest(anime: SAnime): Request = detailRequest(slugOf(anime.url))

    override fun episodeListParse(response: Response): List<SEpisode> {
        val media = parseDetail(response)
        val slug = media.slug ?: return emptyList()
        mediaCache[slug] = media
        return buildEpisodeList(media, slug)
    }

    override fun getEpisodeUrl(episode: SEpisode): String = absolutize(episode.url) ?: episode.url

    // ============================ Video Links ============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
        val parsed = parseEpisodeUrl(episode.url)
            ?: throw Exception("Could not read the episode from its url: ${episode.url}")
        val slug = parsed.first
        val number = parsed.second

        val media = mediaCache[slug] ?: fetchMedia(slug)
        val catalogId = media.id ?: throw Exception("Anime id is missing")

        val watchUrl = "$baseUrl/anime/$slug-episode-$number"
        var cookie = watchCookie(watchUrl)
        var result = requestEpisode(catalogId, media.idMal, number, watchUrl, cookie)
        if (result.first == 403) {
            // The cached session can be revoked server-side before it expires.
            cookie = watchCookie(watchUrl, force = true)
            result = requestEpisode(catalogId, media.idMal, number, watchUrl, cookie)
        }

        val payload = result.second ?: throw Exception("Episode API error (HTTP ${result.first})")
        if (payload.status != "ready") {
            throw Exception("Episode $number is ${payload.reason ?: payload.status ?: "unavailable"}")
        }

        val variants = payload.variants.orEmpty().filter { !it.sources.isNullOrEmpty() }
        if (variants.isEmpty()) throw Exception("No servers found for episode $number")

        val streamHeaders = linkedMapOf(
            "User-Agent" to userAgent,
            "Referer" to watchUrl,
            "Cookie" to cookie,
            "Accept" to "*/*",
        )
        val useProxy = preferences.getBoolean(PREF_PROXY, PREF_PROXY_DEFAULT)
        val sessionId = if (useProxy) proxy.newSession(streamHeaders) else null
        val directHeaders = Headers.Builder().apply {
            streamHeaders.forEach { (key, value) -> add(key, value) }
        }.build()

        val videos = mutableListOf<Video>()
        val seenPlaylists = mutableSetOf<String>()

        variants.forEach { variant ->
            val audioLabel = variant.label ?: variant.id?.uppercase() ?: "Video"

            val subtitleTracks = variant.sources.orEmpty()
                .flatMap { it.tracks.orEmpty() }
                .filter { it.type == null || it.type.equals("vtt", true) }
                .mapNotNull { track ->
                    val trackUrl = absolutize(track.src) ?: return@mapNotNull null
                    val label = track.label?.trim()?.takeIf { it.isNotEmpty() }
                        ?: track.language?.trim()?.takeIf { it.isNotEmpty() }
                        ?: "Unknown"
                    Track(
                        url = sessionId?.let { proxy.proxyUrl(trackUrl, it) } ?: trackUrl,
                        lang = label,
                    )
                }
                .distinctBy { it.lang }

            variant.sources.orEmpty().forEach { source ->
                val playlistUrl = absolutize(source.url) ?: return@forEach
                if (!seenPlaylists.add(playlistUrl)) return@forEach

                val serverLabel = source.label ?: "Server"
                val qualities = if (sessionId != null) {
                    fetchResolutions(playlistUrl, directHeaders)
                } else {
                    emptyList()
                }

                (qualities.ifEmpty { listOf(0 to playlistUrl) }).forEach { (height, url) ->
                    val quality = buildString {
                        append(audioLabel)
                        append(" | ")
                        append(serverLabel)
                        append(" | ")
                        append(if (height > 0) "${height}p" else "Auto")
                    }
                    videos += Video(
                        url = sessionId?.let { proxy.proxyUrl(url, it) } ?: url,
                        quality = quality,
                        videoUrl = null,
                        headers = if (sessionId == null) directHeaders else null,
                        subtitleTracks = subtitleTracks,
                    )
                }
            }
        }

        if (videos.isEmpty()) throw Exception("No playable sources found for episode $number")
        videos
    }

    override fun List<Video>.sort(): List<Video> {
        val audio = preferences.getString(PREF_AUDIO, PREF_AUDIO_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER, PREF_SERVER_DEFAULT)!!
        val quality = preferences.getString(PREF_QUALITY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy(
                { it.quality.contains(audio, ignoreCase = true) },
                { server == "Any" || it.quality.contains(server, ignoreCase = true) },
                { it.quality.contains(quality, ignoreCase = true) },
            ),
        ).reversed()
    }

    // ============================== Requests ==============================

    private fun apiHeaders(referer: String? = null): Headers = headers.newBuilder()
        .add("Accept", "application/json")
        .add("Origin", baseUrl)
        .add("Referer", referer ?: "$baseUrl/")
        .build()

    private fun catalogRequest(
        operation: String,
        variables: JsonObject,
        referer: String? = null,
    ): Request {
        val body = buildJsonObject {
            put("operation", operation)
            put("variables", variables)
        }
        return POST(
            "$baseUrl/api/catalog",
            apiHeaders(referer),
            body.toString().toRequestBody(JSON_MEDIA_TYPE),
        )
    }

    private fun detailRequest(slug: String): Request = catalogRequest(
        "AnimeDetail",
        buildJsonObject { put("slug", slug) },
        referer = "$baseUrl/anime/$slug",
    )

    private fun requestEpisode(
        catalogId: Long,
        malId: Long?,
        number: Int,
        watchUrl: String,
        cookie: String,
    ): Pair<Int, EpisodeEnvelope?> {
        val body = buildJsonObject {
            put("anilistId", catalogId)
            if (malId != null) put("malId", malId)
            put("episode", number)
            put("first", true)
            put("prefer", preferences.getString(PREF_AUDIO, PREF_AUDIO_DEFAULT)!!.lowercase())
        }.toString().toRequestBody(JSON_MEDIA_TYPE)

        val requestHeaders = Headers.Builder()
            .add("Accept", "application/json")
            .add("Content-Type", "application/json")
            .add("Origin", baseUrl)
            .add("Referer", watchUrl)
            .add("Cookie", cookie)
            .add("User-Agent", userAgent)
            .build()

        return client.newCall(POST("$baseUrl/api/episode", requestHeaders, body)).execute().use { response ->
            val text = response.body.string()
            response.code to runCatching { json.decodeFromString<EpisodeEnvelope>(text) }.getOrNull()
        }
    }

    /** Watch pages are the only place that issues the `shiro_watch` session cookie. */
    private fun watchCookie(watchUrl: String, force: Boolean = false): String {
        val now = System.currentTimeMillis()
        cachedCookie?.let { cookie ->
            if (!force && now < cookieExpiresAt) return cookie
        }

        val cookie = client.newCall(GET(watchUrl, headers)).execute().use { response ->
            response.headers.values("Set-Cookie")
                .firstOrNull { it.startsWith("$WATCH_COOKIE=") }
                ?.substringBefore(';')
                ?: throw Exception("Could not start a playback session (HTTP ${response.code})")
        }

        cachedCookie = cookie
        cookieExpiresAt = now + COOKIE_TTL_MS
        return cookie
    }

    private fun fetchMedia(slug: String): Media {
        val response = client.newCall(detailRequest(slug)).execute()
        return parseDetail(response).also { media ->
            media.slug?.let { mediaCache[it] = media }
        }
    }

    /** `EXT-X-STREAM-INF` entries of a master playlist, as `height to url` pairs. */
    private fun fetchResolutions(
        masterUrl: String,
        requestHeaders: Headers,
    ): List<Pair<Int, String>> = try {
        client.newCall(GET(masterUrl, requestHeaders)).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful || !text.trimStart().startsWith("#EXTM3U")) {
                emptyList()
            } else {
                parseMasterPlaylist(text, response.request.url)
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun parseMasterPlaylist(text: String, base: HttpUrl): List<Pair<Int, String>> {
        val qualities = mutableListOf<Pair<Int, String>>()
        var pendingHeight: Int? = null

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF") ->
                    pendingHeight = RESOLUTION_REGEX.find(line)
                        ?.groupValues
                        ?.getOrNull(2)
                        ?.toIntOrNull() ?: 0

                line.isEmpty() || line.startsWith("#") -> Unit

                else -> {
                    val height = pendingHeight ?: return@forEach
                    val resolved = base.resolve(line)?.toString() ?: return@forEach
                    qualities += height to resolved
                    pendingHeight = null
                }
            }
        }

        return qualities.distinctBy { it.second }
    }

    // ============================== Parsing ==============================

    private fun parsePage(response: Response): AnimesPage {
        val page = json.decodeFromString<PageEnvelope>(response.body.string()).page()
        val animes = page.media.orEmpty()
            .filter { showAdult() || it.isAdult != true }
            .mapNotNull { media -> media.slug?.let { mediaToSAnime(media) } }

        return AnimesPage(
            animes = animes,
            hasNextPage = page.pageInfo?.hasNextPage ?: (page.media.orEmpty().size >= PAGE_SIZE),
        )
    }

    private fun parseDetail(response: Response): Media {
        val media = json.decodeFromString<DetailEnvelope>(response.body.string()).media()
        if (media.slug.isNullOrBlank()) throw Exception("Anime not found")
        return media
    }

    private fun parseRecent(response: Response): AnimesPage {
        val schedules = json.decodeFromString<ScheduleEnvelope>(response.body.string()).schedules.orEmpty()

        val animes = mutableListOf<SAnime>()
        val seen = mutableSetOf<Long>()

        schedules.forEach { schedule ->
            val media = schedule.media ?: return@forEach
            val id = media.id ?: return@forEach
            if (!seen.add(id)) return@forEach
            if (!showAdult() && media.isAdult == true) return@forEach
            if (media.slug.isNullOrBlank()) return@forEach
            animes += mediaToSAnime(media)
        }

        return AnimesPage(animes, false)
    }

    private fun mediaToSAnime(media: Media): SAnime = SAnime.create().apply {
        setUrlWithoutDomain("/anime/${media.slug}")
        title = media.displayTitle()
        thumbnail_url = media.coverImage?.extraLarge ?: media.coverImage?.large ?: media.bannerImage
    }

    private fun buildEpisodeList(media: Media, slug: String): List<SEpisode> {
        val streaming = linkedMapOf<Int, StreamingEpisode>()
        media.streamingEpisodes.orEmpty().forEachIndexed { index, episode ->
            val number = episode.title
                ?.let { EPISODE_NUMBER_REGEX.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
                ?: (index + 1)
            streaming.putIfAbsent(number, episode)
        }

        val fromStreaming = streaming.keys.maxOrNull() ?: 0
        val fromAiring = (media.nextAiringEpisode?.episode ?: 0).minus(1).coerceAtLeast(0)
        val fromStatus = if (media.status.equals("FINISHED", true)) media.episodes ?: 0 else 0

        var count = maxOf(fromStreaming, fromAiring, fromStatus)
        if (count <= 0) {
            count = if (media.format.equals("MOVIE", true)) 1 else media.episodes ?: 0
        }
        if (count <= 0) throw Exception("No episodes found for this anime")

        return (1..count).map { number ->
            SEpisode.create().apply {
                setUrlWithoutDomain("/anime/$slug-episode-$number")
                episode_number = number.toFloat()
                date_upload = 0L
                name = episodeTitle(streaming[number]?.title, number)
            }
        }
    }

    private fun episodeTitle(raw: String?, number: Int): String {
        val fallback = "Episode $number"
        val cleaned = raw?.replace(EPISODE_PREFIX_REGEX, "")?.trim().orEmpty()
        return when {
            cleaned.isEmpty() -> fallback
            cleaned.equals(fallback, ignoreCase = true) -> fallback
            else -> "$fallback: $cleaned"
        }
    }

    // ============================== Helpers ==============================

    private fun Media.displayTitle(): String {
        val language = preferences.getString(PREF_TITLE_LANG, PREF_TITLE_LANG_DEFAULT)
        val titles = title
        val value = when (language) {
            "romaji" -> titles?.romaji ?: titles?.english ?: titles?.native
            "native" -> titles?.native ?: titles?.english ?: titles?.romaji
            else -> titles?.english ?: titles?.romaji ?: titles?.native
        }
        return value?.trim()?.takeIf { it.isNotEmpty() } ?: slug ?: "Unknown"
    }

    private fun String?.toSAnimeStatus(): Int = when (this?.uppercase()) {
        "RELEASING" -> SAnime.ONGOING
        "FINISHED" -> SAnime.COMPLETED
        "NOT_YET_RELEASED" -> SAnime.ONGOING
        "CANCELLED" -> SAnime.CANCELLED
        "HIATUS" -> SAnime.ON_HIATUS
        else -> SAnime.UNKNOWN
    }

    private fun showAdult(): Boolean = preferences.getBoolean(PREF_ADULT, PREF_ADULT_DEFAULT)

    private val userAgent: String
        get() = headers["User-Agent"] ?: DEFAULT_USER_AGENT

    private fun slugOf(url: String): String = url.trimEnd('/').substringAfterLast('/')

    private fun parseEpisodeUrl(url: String): Pair<String, Int>? {
        val match = EPISODE_URL_REGEX.find(url) ?: return null
        val slug = match.groupValues[1]
        val number = match.groupValues[2].toIntOrNull() ?: return null
        return slug to number
    }

    private fun absolutize(url: String?): String? = when {
        url.isNullOrBlank() -> null
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "$baseUrl$url"
        else -> "$baseUrl/$url"
    }

    // ============================ Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN
            title = "Domain"
            dialogTitle = "Domain"
            setDefaultValue(PREF_DOMAIN_DEFAULT)
            summary = preferences.getString(key, PREF_DOMAIN_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                val value = (newValue as String).trim().trimEnd('/')
                summary = value.ifEmpty { PREF_DOMAIN_DEFAULT }
                preferences.edit().putString(key, value.ifEmpty { PREF_DOMAIN_DEFAULT }).apply()
                true
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_TITLE_LANG
            title = "Title language"
            entries = arrayOf("English", "Romaji", "Native")
            entryValues = arrayOf("english", "romaji", "native")
            setDefaultValue(PREF_TITLE_LANG_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).apply()
                true
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_AUDIO
            title = "Preferred audio"
            entries = arrayOf("Sub", "Dub")
            entryValues = arrayOf("Sub", "Dub")
            setDefaultValue(PREF_AUDIO_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).apply()
                true
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER
            title = "Preferred server"
            entries = arrayOf("Any", "Orange", "Pear", "Banana")
            entryValues = arrayOf("Any", "Orange", "Pear", "Banana")
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).apply()
                true
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080p", "720p", "480p", "360p")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).apply()
                true
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_PROXY
            title = "Stream through the local proxy"
            summary = "Required: anv only plays when the session cookie and referer are sent for " +
                "playlists, segments and subtitles. Disable only for troubleshooting."
            setDefaultValue(PREF_PROXY_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).apply()
                true
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ADULT
            title = "Show adult titles"
            summary = "Include 18+ entries in popular, latest and search results"
            setDefaultValue(PREF_ADULT_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).apply()
                true
            }
        }.also(screen::addPreference)
    }

    companion object {
        private const val PAGE_SIZE = 30
        private const val WATCH_COOKIE = "shiro_watch"
        private const val COOKIE_TTL_MS = 12 * 60 * 60 * 1000L
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        private val EPISODE_URL_REGEX = Regex("""/anime/(.+)-episode-(\d+)(?:[/?#].*)?$""")
        private val EPISODE_NUMBER_REGEX = Regex("""(?i)\b(?:episode|ep\.?)\s*(\d+)""")
        private val EPISODE_PREFIX_REGEX = Regex("""(?i)^episode\s*\d+\s*[—–:-]\s*""")
        private val RESOLUTION_REGEX = Regex("""RESOLUTION=(\d+)x(\d+)""")

        private const val PREF_DOMAIN = "domain"
        private const val PREF_DOMAIN_DEFAULT = "https://anv.to"

        private const val PREF_TITLE_LANG = "title_language"
        private const val PREF_TITLE_LANG_DEFAULT = "english"

        private const val PREF_AUDIO = "preferred_audio"
        private const val PREF_AUDIO_DEFAULT = "Sub"

        private const val PREF_SERVER = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Any"

        private const val PREF_QUALITY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"

        private const val PREF_PROXY = "local_proxy"
        private const val PREF_PROXY_DEFAULT = true

        private const val PREF_ADULT = "show_adult"
        private const val PREF_ADULT_DEFAULT = false
    }
}
