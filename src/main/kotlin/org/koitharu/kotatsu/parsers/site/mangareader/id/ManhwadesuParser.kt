package org.koitharu.kotatsu.parsers.site.mangareader.id

import androidx.collection.ArrayMap
import okhttp3.Headers
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.wpcomics.WpComicsParser
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("MANHWADESU", "ManhwaDesu", "id", ContentType.HENTAI)
internal class ManhwadesuParser(context: MangaLoaderContext) :
    WpComicsParser(context, MangaParserSource.MANHWADESU, "manhwadesu.art") {

    init {
        paginator.firstPage = 1
        searchPaginator.firstPage = 1
    }

    override val listUrl = "/komik"

    override val filterCapabilities = MangaListFilterCapabilities(
        isMultipleTagsSupported = false,
        isTagsExclusionSupported = false,
        isSearchSupported = true,
        isSearchWithFiltersSupported = true
    )

    // Override headers agar gambar tidak 403 Forbidden
    override fun getPagesHeaders(chapter: MangaChapter): Headers {
        return Headers.Builder()
            .add("Referer", "https://$domain/")
            .add("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .build()
    }

    override fun parseMangaList(doc: Document, tagMap: ArrayMap<String, MangaTag>): List<Manga> {
        return doc.select(".listupd .bs, .listupdate .bs").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val url = a.attrAsRelativeUrl("href") 
            val img = el.selectFirst("img")
            val coverUrl = img?.attr("data-src")?.takeIf { it.isNotEmpty() } 
                ?: img?.src().orEmpty()

            Manga(
                id = generateUid(url),
                title = el.select(".tt").text().trim(),
                altTitles = emptySet(),
                url = url,
                publicUrl = a.attrAsAbsoluteUrl("href"),
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = coverUrl,
                largeCoverUrl = coverUrl,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
                description = null
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl).parseHtml()
        
        val chapters = doc.select("#chapterlist li").mapIndexed { i, el ->
            val a = el.selectFirst("a") ?: return@mapIndexed null
            val href = a.attrAsRelativeUrl("href")
            val title = el.select(".chapternum").text().trim()
            
            MangaChapter(
                id = generateUid(href),
                title = title.ifEmpty { "Chapter ${i + 1}" },
                url = href,
                number = title.replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: (i + 1f),
                scanlator = null,
                branch = null,
                source = source,
                volume = 0,
                uploadDate = 0L
            )
        }.reversed()

        return manga.copy(
            description = doc.select(".entry-content p").text().trim(),
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain), getPagesHeaders(chapter)).parseHtml()
        
        return doc.select("#readerarea img").mapNotNull { img ->
            val url = img.attr("data-src").takeIf { it.isNotEmpty() }
                ?: img.attr("data-lazy-src").takeIf { it.isNotEmpty() }
                ?: img.src()
            
            if (url.isBlank() || url.contains("loader") || url.contains("ads")) return@mapNotNull null
            
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source
            )
        }.distinctBy { it.url }
    }
}
