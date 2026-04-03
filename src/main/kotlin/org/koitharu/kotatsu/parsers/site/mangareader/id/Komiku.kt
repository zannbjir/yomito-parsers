package org.koitharu.kotatsu.parsers.site.mangareader.id

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("KOMIKU", "Komiku", "id")
internal class Komiku(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.KOMIKU, "komiku.org", pageSize = 20, searchPageSize = 10) {

    private val apiDomain = "api.komiku.id"

    override val datePattern = "dd/MM/yyyy"
    override val selectPage = "#Baca_Komik img"
    override val listUrl = "/manga/"

    override val filterCapabilities: MangaListFilterCapabilities
        get() = super.filterCapabilities.copy(
            isTagsExclusionSupported = false,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
        ),
    )

    // Header + Interceptor untuk mengurangi 522 & Cloudflare
    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36")
        .add("Referer", "https://komiku.org/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
        .build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host.contains("komiku")) {
            val newRequest = request.newBuilder()
                .addHeader("Referer", "https://komiku.org/")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36")
                .addHeader("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                .build()
            return chain.proceed(newRequest)
        }
        return chain.proceed(request)
    }

    // ==================== LIST PAGE ====================
    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        // Search tetap pakai API (lebih bagus hasilnya)
        if (!filter.query.isNullOrEmpty()) {
            val url = "https://$apiDomain/page/$page/?post_type=manga&s=${filter.query.urlEncoded()}"
            return parseMangaList(webClient.httpGet(url).parseHtml())
        }

        // List biasa pakai web komiku.org (lebih stabil, kurangi 522)
        val sortParam = when (order) {
            SortOrder.POPULARITY -> "popular"
            SortOrder.UPDATED -> "update"
            SortOrder.NEWEST -> "new"
            else -> "update"
        }

        var url = "https://$domain/manga/?orderby=$sortParam"

        filter.tags.oneOrThrowIfMany()?.let {
            url += "&genre=${it.key}"
        }
        filter.types.oneOrThrowIfMany()?.let {
            url += "&tipe=${when (it) {
                ContentType.MANHWA -> "manhwa"
                ContentType.MANHUA -> "manhua"
                else -> "manga"
            }}"
        }
        filter.states.oneOrThrowIfMany()?.let {
            url += "&status=${when (it) {
                MangaState.ONGOING -> "ongoing"
                MangaState.FINISHED -> "end"
                else -> ""
            }}"
        }

        if (page > 1) url += "&page=$page"

        val doc = webClient.httpGet(url).parseHtml()
        return parseMangaList(doc)
    }

    override fun parseMangaList(doc: Document): List<Manga> {
        return doc.select("div.bge").mapNotNull { element ->
            val a = element.selectFirst("a:has(h3)") ?: return@mapNotNull null
            val relativeUrl = a.attrAsRelativeUrl("href")
            val thumbnailUrl = element.selectFirst("img")?.src()?.let { url ->
                url.substringBeforeLast("?")
                    .replace("/Manga-", "/Komik-")
                    .replace("/Manhua-", "/Komik-")
                    .replace("/Manhwa-", "/Komik-")
            }

            Manga(
                id = generateUid(relativeUrl),
                url = relativeUrl,
                title = element.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null,
                altTitles = emptySet(),
                publicUrl = a.attrAsAbsoluteUrl("href"),
                rating = RATING_UNKNOWN,
                contentRating = null,           // nanti di getDetails kalau perlu
                coverUrl = thumbnailUrl,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    // ==================== DETAIL + CHAPTER ====================
    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

        val chapters = doc.select("#Daftar_Chapter tr:has(td.judulseries)").mapChapters(reversed = true) { index, element ->
            val a = element.selectFirst("td.judulseries a") ?: return@mapChapters null
            val url = a.attrAsRelativeUrl("href")
            val dateText = element.selectFirst("td.tanggalseries")?.text()

            MangaChapter(
                id = generateUid(url),
                title = a.selectFirst("span")?.text()?.trim() ?: a.text().trim(),
                url = url,
                number = index + 1f,
                volume = 0,
                scanlator = null,
                uploadDate = SimpleDateFormat(datePattern, Locale("id")).parseSafe(dateText),
                branch = null,
                source = source,
            )
        }

        return parseInfo(doc, manga, chapters)
    }

    override suspend fun parseInfo(doc: Document, manga: Manga, chapters: List<MangaChapter>): Manga {
        val tags = doc.select("ul.genre li.genre a").mapNotNullToSet { element ->
            val href = element.attr("href")
            val genreKey = href.substringAfter("/genre/").substringBefore("/")
            val genreTitle = element.text().trim()
            MangaTag(key = genreKey, title = genreTitle, source = source)
        }

        val statusText = doc.selectFirst("table.inftable tr > td:contains(Status) + td")?.text()
        val state = when {
            statusText?.contains("Ongoing", ignoreCase = true) == true -> MangaState.ONGOING
            statusText?.contains("Completed", ignoreCase = true) == true -> MangaState.FINISHED
            statusText?.contains("Tamat", ignoreCase = true) == true -> MangaState.FINISHED
            else -> null
        }

        val author = doc.selectFirst("table.inftable tr:has(td:contains(Pengarang)) td:last-child")?.text()?.trim()
        val altTitle = doc.selectFirst("table.inftable tr:has(td:contains(Judul Indonesia)) td:last-child")?.text()?.trim()

        val thumbnail = doc.selectFirst("div.ims > img")?.attr("src")?.substringBeforeLast("?")

        return manga.copy(
            altTitles = if (!altTitle.isNullOrBlank()) setOf(altTitle) else emptySet(),
            description = doc.selectFirst("#Sinopsis > p")?.text()?.trim(),
            state = state,
            authors = setOfNotNull(author),
            tags = tags,
            chapters = chapters,
            coverUrl = thumbnail ?: manga.coverUrl,
            contentRating = ContentRating.SAFE,   // Komiku mostly safe, ubah jadi ADULT kalau perlu
        )
    }

    // Fetch genre dari API
    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        return try {
            val doc = webClient.httpGet("https://$apiDomain/").parseHtml()
            doc.select("select[name='genre'] option").mapNotNull { option ->
                val value = option.attr("value")
                val title = option.text().trim()
                if (value.isNotBlank() && !title.contains("Genre", ignoreCase = true)) {
                    MangaTag(key = value, title = title, source = source)
                } else null
            }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }
}
