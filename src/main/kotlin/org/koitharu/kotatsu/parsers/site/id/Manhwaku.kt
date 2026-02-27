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

@MangaSourceParser("MANHWAKU", "ManhwaKu", "id")
internal class Manhwaku(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANHWAKU, 20) {

    override val configKeyDomain = ConfigKey.Domain("www.manhwaku.biz.id")

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
            "https://$domain/?search=${filter.query.urlEncoded()}"
        } else {
            "https://$domain/jelajahi?page=$page"
        }

        val doc = webClient.httpGet(url).parseHtml() 
        val scriptContent = doc.select("script").map { it.data() }
            .find { it.contains("self.__next_f.push") && it.contains("slug") } ?: return emptyList()
        val jsonParts = Regex("""\[.*\]""").findAll(scriptContent).map { it.value }
        
        val mangaList = mutableListOf<Manga>()
        jsonParts.forEach { part ->
            try {
                val arr = org.json.JSONArray(part)
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    if (item.has("slug") && item.has("title")) {
                        val slug = item.getString("slug")
                        mangaList.add(Manga(
                            id = generateUid(slug),
                            title = item.getString("title"),
                            altTitles = emptySet(),
                            url = "/detail/$slug",
                            publicUrl = "https://$domain/detail/$slug",
                            rating = RATING_UNKNOWN,
                            contentRating = null,
                            coverUrl = item.optString("cover_url"),
                            tags = emptySet(),
                            state = null,
                            authors = emptySet(),
                            source = source
                        ))
                    }
                }
            } catch (e: Exception) { }
        }
        return mangaList.distinctBy { it.id }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl).parseHtml()
        val chapters = doc.select("a[href*='/read/']").mapIndexed { i, el ->
            val href = el.attr("href").removePrefix("https://$domain").removePrefix("/")
            MangaChapter(
                id = generateUid(href),
                title = el.text().trim(),
                url = href,
                number = el.text().replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: (i + 1f),
                volume = 0, scanlator = null, uploadDate = 0L, branch = null, source = source
            )
        }.reversed()

        return manga.copy(
            description = doc.selectFirst(".text-gray-400, p")?.text(),
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = if (chapter.url.startsWith("http")) chapter.url else "https://$domain/${chapter.url.removePrefix("/")}"
        val doc = webClient.httpGet(fullUrl).parseHtml()
        return doc.select(".container img, .reader-area img, img[class*='w-full']").mapNotNull { img ->
            val imageUrl = img.src() ?: img.attr("data-src") ?: return@mapNotNull null
            if (imageUrl.contains(Regex("logo|ad|banner", RegexOption.IGNORE_CASE))) return@mapNotNull null
            MangaPage(id = generateUid(imageUrl), url = imageUrl, preview = null, source = source)
        }
    }

    private fun fetchTags(): Set<MangaTag> = setOf(
        "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror", "Martial Arts", "Romance"
    ).map { MangaTag(it.lowercase().replace(" ", "-"), it, source) }.toSet()
}
