package org.koitharu.kotatsu.parsers.site.madara.id

import okhttp3.Headers.Companion.toHeaders
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("YURILABS", "YuriLabs", "id")
internal class Yurilabs(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.YURILABS, "yurilabs.my.id", pageSize = 20) {

    override val sourceLocale: Locale = Locale("id")

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val doc = webClient.httpGet("https://$domain").parseHtml()
        val tags = doc.select(".genres__collapse ul.list-unstyled a").mapNotNull {
            val name = it.text().replace(Regex("""\(\d+\)"""), "").trim()
            val value = it.attrAsRelativeUrl("href").removeSuffix("/").substringAfterLast("/")
            if (name.isNotBlank() && value.isNotBlank()) MangaTag(title = name, key = value, source = source) else null
        }.toSet()
        return MangaListFilterOptions(availableTags = tags, availableStates = emptySet(), availableContentTypes = emptySet())
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://").append(domain)
            if (!filter.query.isNullOrEmpty()) {
                append("/page/").append(page).append("/?s=").append(filter.query.urlEncoded()).append("&post_type=wp-manga")
            } else if (filter.tags.isNotEmpty()) {
                val tag = filter.tags.first()
                append("/series-genre/").append(tag.key).append("/page/").append(page).append("/")
            } else {
                append("/series/page/").append(page).append("/")
            }
        }
        val docs = webClient.httpGet(url).parseHtml()
        return docs.select("div.manga__item").mapNotNull {
            val a = it.selectFirst("h2 a, .post-title a") ?: return@mapNotNull null
            Manga(
                id = generateUid(a.attrAsRelativeUrl("href")),
                url = a.attrAsRelativeUrl("href"),
                title = a.text().trim(),
                altTitles = emptySet(),
                publicUrl = a.attrAsAbsoluteUrl("href"),
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                coverUrl = it.selectFirst("div.manga__thumb img, .summary_image img")?.src(),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val baseManga = super.getDetails(manga)
        val publicUrl = manga.url.toAbsoluteUrl(domain)
        val docs = webClient.httpGet(publicUrl).parseHtml()
        
        val allChapters = mutableListOf<MangaChapter>()
        val mangaId = docs.selectFirst("#manga-chapters-holder")?.attr("data-id")
        
        if (!mangaId.isNullOrEmpty()) {
            val formBody = okhttp3.FormBody.Builder().add("action", "manga_get_chapters").add("manga", mangaId).build()
            
            val xhrHeaders = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to publicUrl
            ).toHeaders()
            
            val ajaxDocs = webClient.httpPost("https://$domain/wp-admin/admin-ajax.php", formBody, xhrHeaders).parseHtml()
            allChapters.addAll(parseChapters(ajaxDocs))
            
            val pagination = ajaxDocs.selectFirst("div.pagination")
            if (pagination != null) {
                val lastPage = pagination.select("a[data-page]").mapNotNull { it.attr("data-page").toIntOrNull() }.maxOrNull() ?: 1
                for (p in 2..lastPage) {
                    val pageDocs = webClient.httpGet("$publicUrl?t=$p").parseHtml()
                    allChapters.addAll(parseChapters(pageDocs))
                }
            }
        }

        if (allChapters.isEmpty()) allChapters.addAll(parseChapters(docs))

        return baseManga.copy(chapters = allChapters.distinctBy { it.url }.sortedByDescending { it.number })
    }

    private fun parseChapters(doc: org.jsoup.nodes.Document): List<MangaChapter> {
        return doc.select("li.wp-manga-chapter").mapNotNull { node ->
            val a = node.selectFirst("a") ?: return@mapNotNull null
            val url = a.attrAsRelativeUrl("href")
            val title = a.text().trim()
            val dateText = node.selectFirst("span.chapter-release-date i")?.text()?.trim() ?: ""
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
        val docs = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        return docs.select(".reading-content .page-break img").mapNotNull { img ->
            val src = img.attr("data-src").ifBlank { img.attr("src") }.trim()
            if (src.isNotBlank()) MangaPage(generateUid(src), src.toRelativeUrl(domain), null, source) else null
        }
    }

    private fun parseDate(dateStr: String): Long {
        if (dateStr.isEmpty()) return 0L
        return try {
            val sdf = SimpleDateFormat("MMMM d, yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            sdf.parse(dateStr)?.time ?: 0L
        } catch (e: Exception) { 0L }
    }
}
