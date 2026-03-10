package org.koitharu.kotatsu.parsers.site.madara.id

import okhttp3.Headers.Companion.toHeaders
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("ROSEVEIL", "Roseveil", "id")
internal class Roseveil(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.ROSEVEIL, "roseveil.org") {

    override val sourceLocale: Locale = Locale("id")
    override val datePattern = "MMMM dd, yyyy"
    override val withoutAjax = true
    override val listUrl = "comic/"

    // User-Agent browser asli untuk menghindari blokir 403
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    override fun parseMangaList(doc: Document): List<Manga> {
        // Selector diperbaiki untuk mengambil item komik saja
        return doc.select(".manga-item, article, .page-item-detail").mapNotNull { item ->
            val link = item.selectFirst("h3 a, .post-title a") ?: return@mapNotNull null
            val href = link.attrAsRelativeUrl("href")
            
            // Mengambil text langsung dari anchor tag agar judul tidak berubah jadi angka
            val title = link.text().trim()
            val cover = item.selectFirst("img")?.src() ?: ""

            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                coverUrl = cover,
                title = title,
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
        // Menggunakan Headers OkHttp untuk membungkus User-Agent
        val customHeaders = mapOf("User-Agent" to userAgent).toHeaders()
        val response = webClient.httpGet(manga.publicUrl, customHeaders)
        val doc = response.parseHtml()

        val chapters = mutableListOf<MangaChapter>()
        // Selector spesifik untuk daftar chapter Roseveil
        doc.select("#lone-ch-list li.wp-manga-chapter, .wp-manga-chapter").forEachIndexed { i, element ->
            val link = element.selectFirst("a")
            if (link != null) {
                val href = link.attrAsRelativeUrl("href")
                val name = link.text().trim()
                chapters.add(MangaChapter(
                    id = generateUid(href),
                    title = name,
                    url = href,
                    number = name.filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: (i + 1f),
                    uploadDate = 0L,
                    source = source,
                    scanlator = null,
                    branch = null,
                    volume = 0
                ))
            }
        }

        return manga.copy(
            title = doc.selectFirst("h1.text-4xl, .post-title h1")?.text()?.trim() ?: manga.title,
            description = doc.select(".tab-panel#panel-synopsis .prose, .description-summary").text().trim(),
            tags = doc.select(".genres-content a, .tags-content a").map { 
                MangaTag(
                    key = it.attr("href").substringAfterLast("/").ifEmpty { "genre" }, 
                    title = it.text().trim(), 
                    source = source
                ) 
            }.toSet(),
            chapters = chapters.reversed(), // Chapter biasanya terbalik di HTML
            state = if (doc.text().contains("Ongoing", true)) MangaState.ONGOING else MangaState.FINISHED
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val customHeaders = mapOf("User-Agent" to userAgent).toHeaders()
        val response = webClient.httpGet(chapter.url.toAbsoluteUrl(domain), customHeaders)
        val doc = response.parseHtml()
        
        // Selector untuk mengambil semua gambar di halaman baca
        return doc.select(".reading-content img, .page-break img").mapNotNull { img ->
            val url = img.src() ?: return@mapNotNull null
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source
            )
        }
    }
}

