package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers.Companion.toHeaders
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("THRIVE", "Thrive", "id")
internal class Thrive(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.THRIVE, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("thrive.moe")
    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST)
    override val filterCapabilities = MangaListFilterCapabilities(isSearchSupported = true)
    
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)",
        "Referer" to "https://$domain/"
    ).toHeaders()

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val doc = webClient.httpGet("https://$domain/", headers).parseHtml()
        val tags = doc.select("a[href^=/genre/]").mapNotNull {
            val key = it.attr("href").substringAfterLast("/")
            val title = it.selectFirst("span.mx-1")?.text()?.trim() ?: key
            if (key.isNotBlank()) MangaTag(title, key, source) else null
        }.toSet()
        return MangaListFilterOptions(availableTags = tags)
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = if (!filter.query.isNullOrEmpty()) {
            "https://$domain/search?q=${filter.query.urlEncoded()}"
        } else if (filter.tags.isNotEmpty()) {
            "https://$domain/genre/${filter.tags.first().key}" + if (page > 0) "?page=${page + 1}" else ""
        } else {
            if (page > 0) "https://$domain/?page=${page + 1}" else "https://$domain/"
        }

        val doc = webClient.httpGet(url, headers).parseHtml()
        val scriptData = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return emptyList()
        val pageProps = JSONObject(scriptData).optJSONObject("props")?.optJSONObject("pageProps") ?: return emptyList()

        val mangaArray = pageProps.optJSONArray("res") ?: pageProps.optJSONArray("data") ?: pageProps.optJSONArray("terbaru") ?: return emptyList()

        val mangaList = mutableListOf<Manga>()
        for (i in 0 until mangaArray.length()) {
            val jo = mangaArray.getJSONObject(i)
            val id = jo.optString("id", "")
            if (id.isEmpty()) continue

            var coverUrl = jo.optString("cover", "")
            if (coverUrl.isNotEmpty() && !coverUrl.startsWith("http")) {
                coverUrl = "https://cdn.thrive.moe/" + coverUrl.removePrefix("/")
            }

            mangaList.add(Manga(
                id = generateUid(id),
                title = jo.optString("title", "Untitled"),
                altTitles = emptySet(),
                url = "/title/$id",
                publicUrl = "https://$domain/title/$id",
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.SAFE,
                coverUrl = coverUrl,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source
            ))
        }
        return mangaList
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl, headers).parseHtml()
        val scriptData = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return manga
        val pageProps = JSONObject(scriptData).optJSONObject("props")?.optJSONObject("pageProps") ?: return manga

        val mangaObj = pageProps.optJSONObject("manga") ?: pageProps.optJSONObject("res") ?: pageProps
        val chaptersArray = pageProps.optJSONArray("chapterlist") ?: pageProps.optJSONArray("chapters")

        val chapters = mutableListOf<MangaChapter>()
        chaptersArray?.let { arr ->
            for (i in 0 until arr.length()) {
                val ch = arr.getJSONObject(i)
                val chId = ch.optString("chapter_id", "").ifEmpty { ch.optString("id", "") }
                if (chId.isEmpty()) continue
                
                val chTitleRaw = ch.optString("chapter_title", "").ifEmpty { ch.optString("title", "") }
                val chNum = ch.optString("chapter_number", "").ifEmpty { ch.optString("number", "") }
                
                chapters.add(MangaChapter(
                    id = generateUid(chId),
                    title = chTitleRaw.ifBlank { "Chapter $chNum" },
                    url = "/read/$chId",
                    number = chNum.toFloatOrNull() ?: 0f,
                    uploadDate = parseDate(ch.optString("created_at", "").ifEmpty { ch.optString("date", "") }),
                    source = source,
                    scanlator = ch.optString("scanlator", ""),
                    branch = null,
                    volume = 0
                ))
            }
        }

        // AMBIL COVER LANGSUNG DARI HTML BIAR GAK 404
        val htmlCover = doc.selectFirst("img[alt=cover], img.object-cover, .w-full img")?.attr("src")
        val finalCover = htmlCover?.takeIf { it.startsWith("http") } ?: manga.coverUrl ?: ""

        val desc = mangaObj.optString("description", "")
        val stateStr = mangaObj.optString("status", "")
        val state = if (stateStr.contains("completed", true) || stateStr.contains("tamat", true)) MangaState.FINISHED else MangaState.ONGOING

        return manga.copy(
            description = desc,
            state = state,
            coverUrl = finalCover,
            chapters = chapters.sortedByDescending { it.number } 
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet("https://$domain${chapter.url}", headers).parseHtml()
        val scriptData = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return emptyList()
        val nextData = JSONObject(scriptData)

        // FUNGSI SAPU JAGAT: Cari array gambar di seluruh pelosok JSON
        var prefix = ""
        val imagesList = mutableListOf<String>()

        fun extractImages(obj: JSONObject) {
            if (obj.has("prefix") && obj.optString("prefix").isNotEmpty()) prefix = obj.optString("prefix")
            
            listOf("image", "images", "data", "pages").forEach { key ->
                val arr = obj.optJSONArray(key)
                if (arr != null && arr.length() > 0 && arr.optString(0).contains(".")) {
                    for (i in 0 until arr.length()) imagesList.add(arr.getString(i))
                }
            }
            
            for (key in obj.keys()) {
                val value = obj.opt(key)
                if (value is JSONObject) extractImages(value)
                else if (value is JSONArray) {
                    for (i in 0 until value.length()) {
                        val item = value.opt(i)
                        if (item is JSONObject) extractImages(item)
                    }
                }
            }
        }

        extractImages(nextData)

        if (imagesList.isEmpty()) return emptyList()

        return imagesList.map { img ->
            val imageUrl = if (img.startsWith("http")) img else "https://cdn.thrive.moe/data/$prefix/$img"
            MangaPage(generateUid(imageUrl), imageUrl, null, source)
        }
    }

    private fun parseDate(dateStr: String): Long {
        if (dateStr.isEmpty()) return 0L
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            sdf.parse(dateStr.substringBefore("."))?.time ?: 0L
        } catch (e: Exception) { 0L }
    }
}
