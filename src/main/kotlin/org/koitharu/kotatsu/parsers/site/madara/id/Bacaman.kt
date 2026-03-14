package org.koitharu.kotatsu.parsers.site.madara.id

import okhttp3.Headers.Companion.toHeaders
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("BACAMAN", "Bacaman", "id")
internal class Bacaman(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.BACAMAN, "bacaman.id") {

    override val sourceLocale: Locale = Locale("id")
    override val datePattern = "MMMM dd, yyyy"
    override val withoutAjax = false
    override val listUrl = "manga/"

    override fun getRequestHeaders(): okhttp3.Headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Referer" to "https://$domain/"
    ).toHeaders()

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
        val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()
        val chapters = loadChapters(manga.url, doc)

        return manga.copy(
            description = doc.select(".description-summary, .summary__content").text().trim(),
            tags = doc.select(".genres-content a").map {
                MangaTag(
                    key = it.attr("href").substringAfterLast("/").removeSuffix("/"), 
                    title = it.text().trim(), 
                    source = source
                )
            }.toSet(),
            state = if (doc.text().contains("Ongoing", true)) MangaState.ONGOING else MangaState.FINISHED,
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain), getRequestHeaders()).parseHtml()
        return doc.select(".reading-content img, .page-break img, .read-container img").mapNotNull { img ->
            val url = img.src() ?: return@mapNotNull null
            MangaPage(id = generateUid(url), url = url, preview = null, source = source)
        }
    }

    override fun onCreateFilters(): List<MangaListFilter> {
        val genres = listOf(
            "Action" to "3",
            "Adult" to "106",
            "Adventure" to "8",
            "Comedy" to "4",
            "Dark Battle" to "357",
            "Drama" to "13",
            "Ecchi" to "9",
            "Elf" to "347",
            "Fantasy" to "10",
            "Harem" to "14",
            "Historical" to "101",
            "Horror" to "60",
            "Isekai" to "345",
            "Magic" to "346",
            "Martial Arts" to "303",
            "Mature" to "5",
            "Monster" to "351",
            "Mystery" to "61",
            "Overpowered Protagonist" to "348",
            "Psychological" to "122",
            "Romance" to "11",
            "School Life" to "31",
            "Sci-fi" to "40",
            "Seinen" to "6",
            "Shoujo" to "223",
            "Shounen" to "16",
            "Slice of Life" to "57",
            "Spin-off" to "358",
            "Spirit Realm" to "354",
            "Sports" to "160",
            "Supernatural" to "25",
            "Tragedy" to "7",
            "Yaoi" to "237"
        )

        return listOf(
            MangaListFilter.Select(
                "Genre",
                listOf(MangaListFilter.Option("All Genres", "")) + 
                genres.map { MangaListFilter.Option(it.first, it.second) }
            )
        )
    }
}
