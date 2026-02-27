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

@MangaSourceParser("THRIVE", "Thrive", "id")
internal class Thrive(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.THRIVE, 20) {

    override val configKeyDomain = ConfigKey.Domain("thrive.moe")

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .add("Accept", "application/json")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST)

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = fetchTags(),
            availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
            availableContentTypes = EnumSet.of(ContentType.MANHWA, ContentType.MANGA)
        )
    }

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true
        )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)
            append("/api/titles")
            append("?page=")
            append(page)
            if (!filter.query.isNullOrEmpty()) {
                append("&q=")
                append(filter.query.urlEncoded())
            }
        }

        val json = webClient.httpGet(url).parseJson()
        
        val data = json.optJSONArray("data") 
        if (data == null || data.length() == 0) {
            return emptyList()
        }

        return data.mapJSON { jo ->
            val id = jo.getString("id")
            Manga(
                id = generateUid(id),
                title = jo.getString("title"),
                altTitles = emptySet(),
                url = "/title/$id",
                publicUrl = "https://$domain/title/$id",
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = jo.optString("cover_url"),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
    val id = manga.url.substringAfterLast("/")
    val doc = webClient.httpGet("https://$domain/title/$id").parseHtml()
    val description = doc.select("p.text-sm").first()?.text()
    val chapters = doc.select("a[href*='/read/']").mapIndexed { i, el ->
        val chId = el.attr("href").substringAfterLast("/")
        MangaChapter(
            id = generateUid(chId),
            title = el.text(),
            url = "/read/$chId",
            number = el.text().filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: (i + 1f),
            volume = 0,
            scanlator = null,
            uploadDate = 0L,
            branch = null,
            source = source
        )
    }.reversed()

    return manga.copy(
        description = description,
        chapters = chapters
    )
}

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val id = chapter.url.substringAfterLast("/")
        val apiUrl = "https://$domain/api/chapters/$id"
        val json = webClient.httpGet(apiUrl).parseJson()
        val data = json.getJSONObject("data")
        val pages = data.getJSONArray("pages")

        return pages.mapJSON { p ->
            val imageUrl = p.getString("url")
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source
            )
        }
    }

    private fun fetchTags(): Set<MangaTag> {
        return setOf(
            "Action", "Adventure", "Boys' Love", "Comedy", "Crime", "Drama", 
            "Fantasy", "Girls' Love", "Historical", "Horror", "Isekai", "Mecha", 
            "Medical", "Mystery", "Psychological", "Romance", "Sci-Fi", 
            "Slice of Life", "Sports", "Superhero", "Thriller", "Tragedy"
        ).map { title ->
            MangaTag(
                key = title.lowercase().replace(" ", "-"),
                title = title,
                source = source
            )
        }.toSet()
    }
}
