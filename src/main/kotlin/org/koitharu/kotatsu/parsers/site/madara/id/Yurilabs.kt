package org.koitharu.kotatsu.parsers.site.madara.id

import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("YURILABS", "YuriLabs", "id")
internal class Yurilabs(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.YURILABS, "yurilabs.my.id", pageSize = 20) {

    override val sourceLocale: Locale = Locale("id")

    // ================= DETAIL + CHAPTER (FIX UTAMA) =================
    override suspend fun getDetails(manga: Manga): Manga {
        val url = manga.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(url).parseHtml()

        // Ambil base dulu dari MadaraParser
        var result = super.getDetails(manga)

        // === DESKRIPSI ===
        val description = doc.selectFirst(".summary__content, .description-summary .summary__content")
            ?.text()?.trim() ?: result.description

        // === GENRE / TAGS ===
        val tags = doc.select(".genres-content a, .post-content_item.genres a")
            .mapNotNull { it.text().trim() }
            .map { MangaTag(it, it, source) }
            .toSet()

        // === AUTHOR & ARTIST ===
        val author = doc.selectFirst(".author-content a, .post-content_item.author a")?.text()?.trim()
        val artist = doc.selectFirst(".artist-content a, .post-content_item.artist a")?.text()?.trim()

        // === STATUS ===
        val statusText = doc.selectFirst(".post-content_item:contains(Status) .summary-content")?.text()?.trim()
        val status = when (statusText?.lowercase()) {
            "ongoing" -> MangaState.ONGOING
            "completed" -> MangaState.FINISHED
            else -> null
        }
        
        // === AMBIL SEMUA CHAPTER via AJAX + Pagination ===
        val mangaId = doc.selectFirst("#manga-chapters-holder")?.attr("data-id")
            ?: return result // fallback kalau tidak ketemu

        val allChapters = mutableListOf<MangaChapter>()

        // First AJAX call
        val formBody = mapOf(
            "action" to "manga_get_chapters",
            "manga" to mangaId
        )
        val ajaxHeaders = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to url,
            "Origin" to "https://$domain"
        ).toHeaders()

        val ajaxUrl = "https://$domain/wp-admin/admin-ajax.php".toHttpUrl()
        var ajaxDoc = webClient.httpPost(ajaxUrl, formBody, ajaxHeaders).parseHtml()

        allChapters.addAll(parseChapters(ajaxDoc))

        var currentPage = 2
        while (true) {
            val pagination = ajaxDoc.selectFirst("div.pagination, .wp-pagenavi, .page-numbers")
            val lastPage = pagination?.select("a.page-numbers:not(.next), a[data-page]")
                ?.mapNotNull { it.text().toIntOrNull() }
                ?.maxOrNull() ?: 1

            if (currentPage > lastPage) break

            val nextPageDoc = webClient.httpGet("$url?t=$currentPage").parseHtml()
            val moreChapters = parseChapters(nextPageDoc)
            if (moreChapters.isEmpty()) break

            allChapters.addAll(moreChapters)
            currentPage++
            ajaxDoc = nextPageDoc
        }

        val finalChapters = allChapters
            .distinctBy { it.url }
            .sortedBy { it.number }

        return result.copy(
            description = description,
            tags = tags,
            authors = setOfNotNull(author, artist?.takeIf { it != author }),
            state = status,
            chapters = finalChapters
        )
    }

    private fun parseChapters(doc: Document): List<MangaChapter> {
        return doc.select("li.wp-manga-chapter, .listing-chapters_wrap .wp-manga-chapter").mapNotNull { node ->
            val a = node.selectFirst("a") ?: return@mapNotNull null
            val url = a.attrAsRelativeUrl("href")
            val title = a.text().trim()
            val dateText = node.selectFirst("span.chapter-release-date, .chapter-release-date")?.text()?.trim() ?: ""
            val numMatch = Regex("""[0-9]+(\.[0-9]+)?""").findAll(title).lastOrNull()?.value

            MangaChapter(
                id = generateUid(url),
                title = title,
                url = url,
                number = numMatch?.toFloatOrNull() ?: 0f,
                uploadDate = parseDate(dateText),
                source = source,
                scanlator = "",
                branch = null,
                volume = 0
            )
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        return doc.select(".reading-content .page-break img").mapNotNull { img ->
            val src = img.attr("data-src").ifBlank { img.attr("src") }.trim()
            if (src.isNotBlank()) {
                MangaPage(
                    id = generateUid(src),
                    url = src.toRelativeUrl(domain),
                    preview = null,
                    source = source
                )
            } else null
        }
    }

    private fun parseDate(dateStr: String): Long {
        if (dateStr.isEmpty()) return 0L
        return try {
            val sdf = SimpleDateFormat("MMMM d, yyyy", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            sdf.parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
