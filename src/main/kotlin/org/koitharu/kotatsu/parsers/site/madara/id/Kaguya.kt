package org.koitharu.kotatsu.parsers.site.madara.id

import okhttp3.Headers.Companion.toHeaders
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("KAGUYA", "Kaguya", "id")
internal class Kaguya(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.KAGUYA, "v1.kaguya.pro") {

    override val sourceLocale: Locale = Locale("id")
    override val datePattern = "MMMM dd, yyyy"
    
    override val withoutAjax = false 
    override val listUrl = "all-series/"
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "https://$domain/"
    ).toHeaders()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://").append(domain).append("/").append(listUrl)
            if (page > 1) append("page/").append(page).append("/")
            append("?m_orderby=")
            append(if (order == SortOrder.POPULARITY) "views" else "latest")
        }
        return parseMangaList(webClient.httpGet(url, headers).parseHtml())
    }

    override fun parseMangaList(doc: Document): List<Manga> {
        return doc.select(".page-item-detail, .manga-item").mapNotNull { item ->
            val link = item.selectFirst(".post-title a, h3 a") ?: return@mapNotNull null
            val href = link.attrAsRelativeUrl("href")
            
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                coverUrl = item.selectFirst("img")?.src() ?: "",
                title = link.text().trim(),
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                source = source,
                contentRating = ContentRating.SAFE
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl, headers).parseHtml()
        
        val chapters = loadChapters(manga.url, doc)

        return manga.copy(
            description = doc.select(".description-summary, .manga-excerpt, .summary__content").text().trim(),
            tags = doc.select(".genres-content a").map {
                MangaTag(
                    key = it.attr("href").substringAfterLast("/").ifEmpty { "genre" },
                    title = it.text().trim(),
                    source = source
                )
            }.toSet(),
            state = if (doc.text().contains("Ongoing", true)) MangaState.ONGOING else MangaState.FINISHED,
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain), headers).parseHtml()
        
        return doc.select(".page-break img, .reading-content img").mapNotNull { element ->
            val imageUrl = if (element.hasAttr("data-aesir")) {
                try {
                    val decoded = java.util.Base64.getDecoder().decode(element.attr("data-aesir").trim())
                    String(decoded)
                } catch (e: Exception) { element.src() }
            } else {
                element.src()
            } ?: return@mapNotNull null

            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source
            )
        }
    }
}
