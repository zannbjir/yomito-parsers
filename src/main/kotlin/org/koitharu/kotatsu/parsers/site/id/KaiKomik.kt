package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("KAIKOMIK", "Kaikomik", "id")
internal class Kaikomik(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.KAIKOMIK, 20) {

    override val configKeyDomain = ConfigKey.Domain("kaikomik.my.id")

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36")
        .add("Referer", "https://kaikomik.my.id/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED, SortOrder.NEWEST, SortOrder.POPULARITY
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isMultipleTagsSupported = false,
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = emptySet(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
        availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildListUrl(page, order, filter)
        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()

        val rawList = doc.select("div.manga-item, .komik-card, article, .list-manga").mapNotNull { el ->
            val a = el.selectFirst("a[href*='/komik/'], a[href*='/manga/']")
            val title = el.selectFirst("h2, h3, .title")?.text()?.trim()

            // Pakai logika IF-ELSE biasa, JitPack dijamin nggak mabok
            if (a != null && title != null) {
                val href = a.attrAsRelativeUrl("href")
                val cover = el.selectFirst("img")?.attr("data-src")?.ifBlank { null } ?: el.selectFirst("img")?.src()
                Manga(
                    id = generateUid(href),
                    title = title,
                    url = href,
                    publicUrl = a.attrAsAbsoluteUrl("href"),
                    coverUrl = cover,
                    largeCoverUrl = cover,
                    rating = RATING_UNKNOWN,
                    contentRating = ContentRating.ADULT,
                    tags = emptySet(),
                    state = null,
                    authors = emptySet(),
                    source = source,
                )
            } else {
                null
            }
        }

        return rawList.distinctBy { it.id }
    }

    private fun buildListUrl(page: Int, order: SortOrder, filter: MangaListFilter): String {
        val base = "https://kaikomik.my.id"
        if (!filter.query.isNullOrEmpty()) {
            return "$base/search?q=${filter.query.urlEncoded()}&page=$page"
        }
        val sort = when (order) {
            SortOrder.POPULARITY -> "popular"
            SortOrder.NEWEST -> "latest"
            else -> "update"
        }
        return "$base/list?page=$page&order=$sort"
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()

        val title = doc.selectFirst("h1, .title, .manga-title")?.text()?.trim() ?: manga.title
        val description = doc.selectFirst(".synopsis, .description, .summary")?.text()?.trim().orEmpty()

        val chapters = doc.select("a.chapter-link, li.chapter a, .episode a").map { a ->
            val url = a.attrAsRelativeUrl("href")
            val chTitle = a.text().trim()
            MangaChapter(
                id = generateUid(url),
                title = chTitle,
                url = url,
                number = chTitle.parseChapterNumber() ?: 0f,
                volume = 0,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source,
            )
        }.sortedByDescending { it.number }

        return manga.copy(
            title = title,
            description = description,
            chapters = chapters,
            state = if (doc.text().contains("tamat", ignoreCase = true)) MangaState.FINISHED else MangaState.ONGOING
        )
    }

    private fun String.parseChapterNumber(): Float? =
        Regex("""[0-9]+(\.[0-9]+)?""").find(this)?.value?.toFloatOrNull()

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain), getRequestHeaders()).parseHtml()

        return doc.select("img.reader-img, .chapter-image img, img[data-src], img[src*='storage']")
            .mapNotNull { img ->
                val url = img.attr("data-src").ifBlank { img.attr("src") }.trim()
                if (url.isNotBlank() && !url.contains("placeholder")) {
                    MangaPage(
                        id = generateUid(url),
                        url = url.toAbsoluteUrl(domain),
                        preview = null,
                        source = source
                    )
                } else {
                    null
                }
            }
    }
}
