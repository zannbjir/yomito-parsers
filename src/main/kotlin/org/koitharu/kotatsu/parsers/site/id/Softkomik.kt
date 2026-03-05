package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.mapJSON
import java.util.*

@MangaSourceParser("SOFTKOMIK", "Softkomik", "id")
internal class Softkomik(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.SOFTKOMIK, 20) {

    override val configKeyDomain = ConfigKey.Domain("softkomik.co")
    private val apiUrl = "https://v2.softdevices.my.id"
    private val coverUrl = "https://cover.softdevices.my.id/softkomik-cover"

    private var cachedToken: String? = null
    private var cachedSign: String? = null
    private var expiry: Long = 0

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.NEWEST,
        SortOrder.POPULARITY
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isMultipleTagsSupported = false,
        isTagsExclusionSupported = false,
        isSearchSupported = true,
        isSearchWithFiltersSupported = false
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val genres = listOf(
            "Action", "Adventure", "Comedy", "Cooking", "Crime", "Drama", "Ecchi", 
            "Fantasy", "Game", "Gore", "Harem", "Historical", "Horror", "Isekai", 
            "Josei", "Martial Arts", "Mature", "Mecha", "Medical", "Military", 
            "Mystery", "Psychological", "Romance", "School Life", "Sci-fi", 
            "Seinen", "Shoujo", "Shounen", "Slice of Life", "Sports", 
            "Supernatural", "Thriller", "Tragedy", "Webtoons"
        )
        return MangaListFilterOptions() 
    }

    private suspend fun updateSession() {
        if (cachedToken != null && System.currentTimeMillis() < expiry) return
        try {
            webClient.httpPost("https://$domain/api/me", emptyMap())
            val response = webClient.httpGet("https://$domain/api/sessions")
            val json = JSONObject(response.body?.string() ?: "")
            cachedToken = json.optString("token")
            cachedSign = json.optString("sign")
            expiry = json.optLong("ex", 0)
        } catch (e: Exception) {
            // Error handling
        }
    }

    private fun getApiHeaders(): Headers {
        return Headers.Builder()
            .add("X-Token", cachedToken ?: "")
            .add("X-Sign", cachedSign ?: "")
            .add("Referer", "https://$domain/")
            .add("Origin", "https://$domain")
            .build()
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        updateSession()
        
        val url = if (!filter.query.isNullOrEmpty()) {
            "$apiUrl/komik?name=${filter.query.urlEncoded()}&search=true&limit=20&page=$page"
        } else {
            val sortBy = when (order) {
                SortOrder.POPULARITY -> "popular"
                else -> "newKomik"
            }
            var requestUrl = "$apiUrl/komik?limit=20&page=$page&sortBy=$sortBy"
            
            filter.tags.firstOrNull()?.let {
                requestUrl += "&genre=${it.key.urlEncoded()}"
            }
            
            requestUrl
        }

        val response = webClient.httpGet(url, getApiHeaders())
        val bodyString = response.body?.string() ?: return emptyList()
        val data = JSONObject(bodyString).optJSONArray("data") ?: return emptyList()

        return data.mapJSON { jo ->
            val slug = jo.getString("title_slug")
            val img = jo.getString("gambar").removePrefix("/")
            Manga(
                id = generateUid(slug),
                title = jo.getString("title"),
                url = slug,
                publicUrl = "https://$domain/$slug",
                coverUrl = "$coverUrl/$img",
                source = source,
                rating = RATING_UNKNOWN,
                state = if (jo.optString("status") == "tamat") MangaState.FINISHED else MangaState.ONGOING,
                authors = emptySet(),
                tags = emptySet()
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        updateSession()
        val response = webClient.httpGet("$apiUrl/${manga.url}/chapter?limit=9999", getApiHeaders())
        val bodyString = response.body?.string() ?: ""
        val chaptersArray = JSONObject(bodyString).getJSONArray("chapter")

        val chapters = chaptersArray.mapJSON { ch ->
            val chNum = ch.getString("chapter")
            val chUrl = "/${manga.url}/chapter/$chNum"
            MangaChapter(
                id = generateUid(chUrl),
                title = "Chapter $chNum",
                url = chUrl,
                number = chNum.toFloatOrNull() ?: 0f,
                scanlator = null,
                branch = null,
                source = source,
                volume = 0,
                uploadDate = 0L
            )
        }

        return manga.copy(chapters = chapters.sortedByDescending { it.number })
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        updateSession()
        val response = webClient.httpGet("https://$domain${chapter.url}")
        val html = response.body?.string() ?: ""
        
        val nextDataStr = html.substringAfter("<script id=\"__NEXT_DATA__\" type=\"application/json\">")
                                  .substringBefore("</script>")
        val props = JSONObject(nextDataStr).getJSONObject("props").getJSONObject("pageProps").getJSONObject("data")
        
        val images = props.getJSONArray("imageSrc")
        val storage2 = props.optBoolean("storageInter2", false)
        val host = if (storage2) "https://image.softkomik.com/softkomik" else "https://f1.softkomik.com/file/softkomik-image"

        return List(images.length()) { i ->
            val imgPath = images.getString(i).removePrefix("/")
            val fullUrl = "$host/$imgPath"
            MangaPage(
                id = generateUid(fullUrl),
                url = fullUrl,
                preview = null,
                source = source
            )
        }
    }
}
