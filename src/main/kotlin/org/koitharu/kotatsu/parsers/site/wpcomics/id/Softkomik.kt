package org.koitharu.kotatsu.parsers.site.wpcomics.id

import androidx.collection.ArrayMap
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.wpcomics.WpComicsParser
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("SOFTKOMIK", "Softkomik", "id")
internal class Softkomik(context: MangaLoaderContext) :
    WpComicsParser(context, MangaParserSource.SOFTKOMIK, "softkomik.co") {

    init {
        paginator.firstPage = 1
        searchPaginator.firstPage = 1
    }

    override val listUrl = "/komik/library"

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
        SortOrder.UPDATED
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = if (!filter.query.isNullOrEmpty()) {
            "https://$domain/komik/list?name=${filter.query.urlEncoded()}"
        } else {
            buildString {
                append("https://")
                append(domain)
                append(listUrl)
                append("?page=")
                append(page)
                
                filter.tags.firstOrNull()?.let {
                    append("&genre=")
                    append(it.key)
                }

                filter.states.oneOrThrowIfMany()?.let {
                    append("&status=")
                    append(if (it == MangaState.ONGOING) "ongoing" else "completed")
                }

                append("&sortBy=")
                append(when (order) {
                    SortOrder.POPULARITY -> "view"
                    SortOrder.NEWEST -> "newKomik"
                    else -> "update"
                })
            }
        }

        val doc = webClient.httpGet(url).parseHtml()
        return parseMangaList(doc, ArrayMap())
    }

    override fun parseMangaList(doc: Document, tagMap: ArrayMap<String, MangaTag>): List<Manga> {
        return doc.select(".listupd .bs, .grid-container .bs").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val mangaUrl = a.attrAsRelativeUrl("href")
            
            Manga(
                id = generateUid(mangaUrl),
                title = el.select(".tt, .title, h3").text().trim(),
                altTitles = emptySet(),
                url = mangaUrl,
                publicUrl = a.attrAsAbsoluteUrl("href"),
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = el.selectFirst("img")?.src().orEmpty(),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source
            )
        }.distinctBy { it.id }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl).parseHtml()
        val chapters = doc.select("#chapterlist li, .clist li").mapIndexed { i, el ->
            val a = el.selectFirst("a") ?: return@mapIndexed null
            val href = a.attrAsRelativeUrl("href")
            MangaChapter(
                id = generateUid(href),
                title = a.text().trim(),
                url = href,
                number = a.text().replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: (i + 1f),
                volume = 0,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source
            )
        }.filterNotNull().reversed()

        return manga.copy(
            description = doc.select(".entry-content p, .synopsis").text().trim(),
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        
        return doc.select("#readerarea img").mapNotNull { img ->
            val url = img.attr("data-src").takeIf { it.isNotEmpty() } 
                      ?: img.attr("src") 
                      ?: return@mapNotNull null
            
            if (url.contains("logo") || url.isBlank()) return@mapNotNull null
            
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source
            )
        }
    }

    override suspend fun getOrCreateTagMap(): ArrayMap<String, MangaTag> {
        val genres = listOf(
            "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror", 
            "Martial Arts", "Mystery", "Romance", "Sci-fi", "Slice of Life", 
            "Sports", "Supernatural", "Tragedy", "School", "Isekai", "Gore", 
            "Medical", "Thriller", "Reincarnation", "Historical", "Ecchi"
        )
        val result = ArrayMap<String, MangaTag>(genres.size)
        for (genre in genres) {
            val key = genre.lowercase().replace(" ", "-")
            result[genre] = MangaTag(title = genre, key = key, source = source)
        }
        return result
    }
}
