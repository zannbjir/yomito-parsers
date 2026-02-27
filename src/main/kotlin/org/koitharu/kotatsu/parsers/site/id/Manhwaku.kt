package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.util.*

@MangaSourceParser("MANHWAKU", "Manhwaku", "id")
internal class Manhwaku(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANHWAKU, 20) {

    override val configKeyDomain = ConfigKey.Domain("www.manhwaku.biz.id")

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST)

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchTags(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
        availableContentTypes = EnumSet.of(ContentType.MANHWA, ContentType.MANHUA)
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true
        )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)
            append("/api/series?page=")
            append(page)
            if (!filter.query.isNullOrEmpty()) {
                append("&search=")
                append(filter.query.urlEncoded())
            }
        }

        val extraHeaders = Headers.Builder()
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        val json = webClient.httpGet(url, extraHeaders).parseJson()
        val data = json.optJSONArray("data") ?: return emptyList()

        return data.mapJSON { jo ->
            val slug = jo.getString("slug")
            Manga(
                id = generateUid(slug),
                title = jo.getString("title"),
                altTitles = emptySet(),
                url = "/detail/$slug",
                publicUrl = "https://$domain/detail/$slug",
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = jo.optString("cover_url").takeIf { it.isNotEmpty() },
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source
            )
        }
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
                volume = 0,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source
            )
        }.reversed()

        return manga.copy(
            description = doc.selectFirst(".text-gray-400, p, #desk-content")?.text()?.trim(),
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
        "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror", "Martial Arts", "Romance", 
        "Historical", "School Life", "Isekai", "Adult", "Psychological", "Seinen", "Shoujo", "Shounen"
    ).map { MangaTag(it.lowercase().replace(" ", "-"), it, source) }.toSet()
}
