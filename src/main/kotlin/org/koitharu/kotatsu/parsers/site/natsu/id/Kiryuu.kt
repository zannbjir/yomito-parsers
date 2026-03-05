package org.dokiteam.doki.parsers.site.natsu.id

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.natsu.NatsuParser
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.urlEncoded

@MangaSourceParser("KIRYUU", "Kiryuu", "id")
internal class Kiryuu(context: MangaLoaderContext) :
    NatsuParser(context, MangaParserSource.KIRYUU, pageSize = 24) {

    override val configKeyDomain = ConfigKey.Domain("kiryuu.online")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
    }

    override suspend fun getTags(): List<MangaTag> {
        val doc = webClient.httpGet("https://$domain/manga/").parseHtml()
        return doc.select("ul.genre li, .genrelists li").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            MangaTag(
                id = generateUid(a.attr("href")),
                title = a.text().trim(),
                source = source
            )
        }.distinctBy { it.title }.sortedBy { it.title }
    }

    override suspend fun getListPage(page: Int, filter: MangaListFilter?): List<Manga> {
        val url = buildUrl(page, filter)
        val doc = webClient.httpGet(url).parseHtml()
        return extractMangaList(doc)
    }

    private fun buildUrl(page: Int, filter: MangaListFilter?): String {
        val query = filter?.query
        if (!query.isNullOrBlank()) {
            return "https://$domain/page/$page/?s=${query.urlEncoded()}"
        }

        val tag = filter?.tags?.firstOrNull()?.title?.lowercase()?.replace(" ", "-")
        if (tag != null) {
            return "https://$domain/genres/$tag/page/$page/"
        }

        return if (page == 1) "https://$domain/manga/" else "https://$domain/manga/page/$page/"
    }

    private fun extractMangaList(doc: Document): List<Manga> {
        return doc.select(".utao, .bs, .listupd .bsx").map { el ->
            val a = el.selectFirst("a")!!
            val title = a.attr("title").ifEmpty { el.selectFirst("h2, .tt")?.text() ?: "" }
            val url = a.absUrl("href")
            
            Manga(
                id = generateUid(url),
                title = title.trim(),
                url = url,
                coverUrl = el.selectFirst("img")?.absUrl("src") ?: "",
                tags = emptySet(),
                status = if (el.select(".status").text().contains("On", true)) MangaStatus.ONGOING else MangaStatus.FINISHED,
                rating = RATING_UNKNOWN,
                author = null,
                description = null,
                source = source,
                state = MangaState.OK
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url).parseHtml()
        val desc = doc.select(".entry-content, .sinopsis").text().trim()
        val author = doc.select(".infotable tr:contains(Author) td:last-child, .spe span:contains(Author)").text().trim()
        
        return manga.copy(
            description = desc,
            author = author.ifEmpty { null },
            tags = doc.select(".genwrap a, .mgen a").map { 
                MangaTag(generateUid(it.text()), it.text(), source) 
            }.toSet()
        )
    }

    override suspend fun loadChapters(manga: Manga): List<MangaChapter> {
        val doc = webClient.httpGet(manga.url).parseHtml()
        return doc.select("#chapterlist li, .cl-item").map { el ->
            val a = el.selectFirst("a")!!
            val title = el.select(".chapternum, .chapter-label").text().trim().ifEmpty { a.text() }
            val dateText = el.select(".chapterdate").text().trim()
            
            MangaChapter(
                id = generateUid(a.absUrl("href")),
                title = title,
                url = a.absUrl("href"),
                number = title.replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: -1f,
                uploadDate = parseDate(dateText),
                source = source
            )
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url).parseHtml()
        val images = doc.select("#readerarea img").filter { 
            val src = it.attr("src")
            src.isNotBlank() && !src.contains("loading") 
        }

        return images.mapIndexed { index, img ->
            val url = img.absUrl("src").ifEmpty { img.absUrl("data-src") }
            MangaPage(
                id = generateUid(url),
                url = url,
                number = index + 1,
                source = source
            )
        }
    }
}
