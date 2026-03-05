package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("MANHWAKU", "ManhwaKu", "id")
internal class Manhwaku(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANHWAKU, 20) {

    override val configKeyDomain = ConfigKey.Domain("www.manhwaku.biz.id")

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST)

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(isSearchSupported = true)

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = emptySet(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = if (!filter.query.isNullOrEmpty()) {
            "https://$domain/jelajahi?search=${filter.query.urlEncoded()}"
        } else {
            "https://$domain/jelajahi?page=$page"
        }

        val doc = webClient.httpGet(url).parseHtml()
        return parseMangaList(doc)
    }

    private fun parseMangaList(doc: Document): List<Manga> {
        return doc.select("a[href^='/detail/']").mapNotNull { el ->
            val href = el.attr("href")
            val slug = href.substringAfter("/detail/")
            val title = el.select("h1, h2, h3, p.font-bold, .text-white").firstOrNull()?.text() ?: return@mapNotNull null
            val img = el.selectFirst("img")?.src() ?: ""

            Manga(
                id = generateUid(slug),
                title = title.trim(),
                altTitles = emptySet(),
                url = href,
                publicUrl = "https://$domain$href",
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = img,
                largeCoverUrl = img,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source
            )
        }.distinctBy { it.id }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl).parseHtml()
        val chapters = doc.select("a[href*='/read/']").mapNotNull { el ->
            val href = el.attrAsRelativeUrl("href")
            val title = el.text().trim()
            
            MangaChapter(
                id = generateUid(href),
                title = title,
                url = href,
                number = title.replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: 0f,
                volume = 0,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source
            )
        }.distinctBy { it.url }.sortedByDescending { it.number }

        return manga.copy(
            description = doc.select("p.text-gray-400, .mt-4.text-sm").firstOrNull()?.text()?.trim(),
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        return doc.select("main img, .flex-col img").mapNotNull { img ->
            val url = img.attr("data-src").ifEmpty { img.src() } ?: return@mapNotNull null
            if (url.isBlank() || url.contains("logo") || url.contains("icon") || url.length < 10) {
                return@mapNotNull null
            }

            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source
            )
        }
    }
}
