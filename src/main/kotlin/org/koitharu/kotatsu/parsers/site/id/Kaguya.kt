package org.koitharu.kotatsu.parsers.site.id

import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("KAGUYA", "Kaguya", "id")
internal class Kaguya(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.KAGUYA, 20) {

    override val configKeyDomain = ConfigKey.Domain("v1.kaguya.pro")
    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST, SortOrder.POPULARITY)
    override val filterCapabilities = MangaListFilterCapabilities(isSearchSupported = true)
    override suspend fun getFilterOptions() = MangaListFilterOptions()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = "https://$domain/all-series/${if (page > 1) "page/$page/" else ""}?m_orderby=${if (order == SortOrder.POPULARITY) "views" else "latest"}"
        val html = webClient.httpGet(url).bodyString()
        val document = Jsoup.parse(html)

        return document.select(".page-item-detail").map { element ->
            val link = element.selectFirst(".post-title a")!!
            val coverUrl = element.selectFirst("img")?.let { it.attr("abs:data-src").ifEmpty { it.attr("abs:src") } } ?: ""
            Manga(
                id = generateUid(link.attr("href")),
                url = link.attr("href").substringAfter(domain),
                publicUrl = link.attr("href"),
                title = link.text().trim(),
                altTitles = emptySet(),
                coverUrl = coverUrl,
                largeCoverUrl = coverUrl,
                authors = emptySet(),
                tags = emptySet(),
                state = null,
                description = null,
                contentRating = null,
                source = source,
                rating = RATING_UNKNOWN
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val html = webClient.httpGet(manga.publicUrl).bodyString()
        val document = Jsoup.parse(html)
        val chapters = mutableListOf<MangaChapter>()
        
        val ajaxHtml = webClient.httpPost("${manga.publicUrl}ajax/chapters/", emptyMap<String, String>()).bodyString()
        val ajaxDoc = Jsoup.parse(ajaxHtml)
        
        ajaxDoc.select(".wp-manga-chapter").forEach { element ->
            val link = element.selectFirst("a")!!
            chapters.add(MangaChapter(
                id = generateUid(link.attr("href")),
                title = link.text().trim(),
                url = link.attr("href").substringAfter(domain),
                number = link.text().filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: 0f,
                uploadDate = 0L,
                scanlator = null,
                branch = null,
                source = source,
                volume = 0
            ))
        }

        return manga.copy(
            description = document.select(".description-summary").text().trim(),
            state = MangaState.ONGOING,
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val html = webClient.httpGet("https://$domain${chapter.url}").bodyString()
        val document = Jsoup.parse(html)

        return document.select(".page-break img").map { element ->
            val imageUrl = if (element.hasAttr("data-aesir")) {
                try {
                    String(java.util.Base64.getDecoder().decode(element.attr("data-aesir").trim()))
                } catch (e: Exception) { element.attr("abs:src") }
            } else {
                element.attr("abs:src")
            }

            MangaPage(id = generateUid(imageUrl), url = imageUrl, preview = null, source = source)
        }
    }
}
