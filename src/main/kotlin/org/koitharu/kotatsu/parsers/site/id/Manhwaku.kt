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
        .add("referer", "https://$domain/")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST)

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = emptySet(),
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
            append("/api/series")
            append("?page=")
            append(page)
            if (!filter.query.isNullOrEmpty()) {
                append("&search=")
                append(filter.query.urlEncoded())
            }
        }

        val json = webClient.httpGet(url).parseJson()
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
                coverUrl = jo.getString("cover_url"),
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
            val chUrl = el.attr("href").removePrefix("https://$domain").removePrefix("/")
            val title = el.text()
            MangaChapter(
                id = generateUid(chUrl),
                title = title,
                url = chUrl,
                number = title.filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: (i + 1f),
                volume = 0,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source
            )
        }.reversed()

        return manga.copy(
            description = doc.select(".text-gray-400").first()?.text(),
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet("https://$domain/${chapter.url}").parseHtml()
        
        return doc.select(".container img, .reader-area img").map { img ->
            val imageUrl = img.src() ?: img.attr("data-src")
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source
            )
        }
    }
}
