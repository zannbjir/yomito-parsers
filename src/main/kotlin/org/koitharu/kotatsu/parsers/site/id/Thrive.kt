package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.util.*

@MangaSourceParser("THRIVE", "Thrive", "id")
internal class Thrive(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.THRIVE, 20) {

    override val configKeyDomain = ConfigKey.Domain("thrive.moe")

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST)

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchTags(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(isSearchSupported = true)

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = if (!filter.query.isNullOrEmpty()) {
            "https://$domain/search?q=${filter.query.urlEncoded()}"
        } else {
            "https://$domain/"
        }

        val doc = webClient.httpGet(url).parseHtml()
        val scriptData = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return emptyList()
        val json = JSONObject(scriptData)
        val props = json.getJSONObject("props").getJSONObject("pageProps")
        val dataArray = props.optJSONArray("terbaru") 
            ?: props.optJSONArray("results") 
            ?: props.optJSONArray("thrive")
            ?: return emptyList()

        return dataArray.mapJSON { jo ->
            val id = jo.getString("id")
            Manga(
                id = generateUid(id),
                title = jo.getString("title"),
                altTitles = emptySet(),
                url = "/title/$id",
                publicUrl = "https://$domain/title/$id",
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = jo.optString("cover"),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl).parseHtml()
        val scriptData = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return manga
        
        val json = JSONObject(scriptData)
        val titleData = json.getJSONObject("props").getJSONObject("pageProps").getJSONObject("title")

        val chapters = titleData.optJSONArray("chapters")?.mapJSON { ch ->
            val chId = ch.getString("id")
            MangaChapter(
                id = generateUid(chId),
                title = ch.optString("title").ifEmpty { "Chapter ${ch.optString("number")}" },
                url = "/read/$chId",
                number = ch.optString("number").toFloatOrNull() ?: 0f,
                volume = 0, scanlator = null, uploadDate = 0L, branch = null, source = source
            )
        } ?: emptyList()

        return manga.copy(
            description = titleData.optString("description"),
            chapters = chapters.reversed()
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet("https://$domain${chapter.url}").parseHtml()
        val scriptData = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return emptyList()
        
        val json = JSONObject(scriptData)
        val chapterData = json.getJSONObject("props").getJSONObject("pageProps").getJSONObject("chapter")
        val pages = chapterData.getJSONArray("pages")

        return pages.mapJSON { p ->
            val imageUrl = p.getString("url")
            MangaPage(id = generateUid(imageUrl), url = imageUrl, preview = null, source = source)
        }
    }

    private fun fetchTags(): Set<MangaTag> = setOf(
        "Action", "Adventure", "Boys' Love", "Comedy", "Crime", "Drama", "Fantasy", 
        "Girls' Love", "Historical", "Horror", "Isekai", "Mecha", "Medical", 
        "Mystery", "Psychological", "Romance", "Sci-Fi", "Slice of Life", "Sports", 
        "Superhero", "Thriller", "Tragedy"
    ).map { MangaTag(it.lowercase().replace(" ", "-"), it, source) }.toSet()
}
