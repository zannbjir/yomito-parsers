package org.koitharu.kotatsu.parsers.site.id

import org.json.JSONArray
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("BACAMI", "Bacami", "id")
internal class Bacami(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.BACAMI, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("v1.bacami.site")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities = MangaListFilterCapabilities(isSearchSupported = true)

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val doc = webClient.httpGet("https://$domain/custom-search/").parseHtml()
        val tags = doc.select("select#genre option").mapNotNull { option ->
            val key = option.attr("value").trim()
            val title = option.text().trim()
            if (key.isNotBlank() && !key.equals("GENRE ALL", ignoreCase = true)) {
                MangaTag(title, key, source)
            } else {
                null
            }
        }.toSet()
        return MangaListFilterOptions(availableTags = tags)
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://").append(domain).append("/custom-search/")

            if (filter.tags.isNotEmpty()) {
                append("genre/").append(filter.tags.first().key).append('/')
            }

            append("orderby/")
            when (order) {
                SortOrder.POPULARITY -> append("score/")
                SortOrder.ALPHABETICAL -> append("name/")
                else -> append("latest/")
            }

            append("page/").append(page).append('/')

            if (!filter.query.isNullOrEmpty()) {
                append("?s=").append(filter.query.urlEncoded())
            }
        }

        val doc = webClient.httpGet(url).parseHtml()
        return doc.select("article.genre-card").mapNotNull { element ->
            val link = element.selectFirst("div.genre-info > a.genre-title")
                ?: element.selectFirst("div.genre-info > a")
                ?: return@mapNotNull null
            val href = link.attr("href").trim()
            val slugPath = href.substringAfter(domain).ifEmpty { href }
            val cover = element.selectFirst("img.lazy-image, div.genre-cover img")
            val coverUrl = cover?.attr("data-src").orEmpty()
                .ifEmpty { cover?.attr("src").orEmpty() }

            Manga(
                id = generateUid(href),
                url = slugPath,
                publicUrl = href.toAbsoluteUrl(domain),
                title = link.text().trim(),
                altTitles = emptySet(),
                coverUrl = coverUrl,
                largeCoverUrl = coverUrl,
                authors = emptySet(),
                tags = emptySet(),
                state = null,
                description = null,
                contentRating = ContentRating.SAFE,
                source = source,
                rating = RATING_UNKNOWN,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        val title = doc.selectFirst("h1.manga-title")?.text()?.trim()
            ?.removeSuffix(" Bahasa Indonesia")
            ?.takeIf { it.isNotBlank() }
            ?: manga.title
        val description = doc.selectFirst("p.manga-description")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
        val cover = doc.selectFirst(".manga-cover img, .manga-thumbnail img, #komik img[alt]")
            ?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
            ?.takeIf { it.isNotBlank() }
            ?: manga.coverUrl

        val chapters = doc.select("ol.chapter-list li.chapter-item, ul.chapter-list li, .chapter-list li")
            .mapNotNull { element ->
                val link = element.selectFirst("a.ch-link, a") ?: return@mapNotNull null
                val href = link.attr("href").trim()
                if (href.isBlank()) return@mapNotNull null
                val titleText = link.text().trim()
                val numberText = Regex("(?i)\\bchapter\\s+([0-9]+(?:\\.[0-9]+)?)")
                    .find(titleText)?.groupValues?.getOrNull(1)
                    ?: Regex("[0-9]+(?:\\.[0-9]+)?").findAll(titleText).lastOrNull()?.value
                val number = numberText?.toFloatOrNull() ?: return@mapNotNull null
                val path = href.substringAfter(domain).ifEmpty { href }
                val dateText = element.selectFirst(".ch-date, .chapterdate, .date")?.text().orEmpty().trim()

                MangaChapter(
                    id = generateUid(href),
                    title = titleText,
                    url = path,
                    number = number,
                    uploadDate = parseDate(dateText),
                    source = source,
                    scanlator = null,
                    branch = null,
                    volume = 0,
                )
            }
            .sortedBy { it.number }

        val tags = doc.select("nav.manga-genres a[href*='/genre/'], nav > span > a[href*='/genre/']")
            .mapNotNull { tag ->
                val key = tag.attr("href")
                    .substringAfter("/genre/")
                    .substringBefore('?')
                    .trim('/')
                val titleText = tag.text().trim()
                if (key.isNotBlank() && titleText.isNotBlank()) MangaTag(titleText, key, source) else null
            }
            .toSet()

        val author = doc.select(".manga-info-grid .info-item")
            .firstOrNull { it.selectFirst(".info-label")?.text()?.contains("Author", true) == true }
            ?.selectFirst(".info-value")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val bodyText = doc.text()
        val state = when {
            bodyText.contains("tamat", ignoreCase = true) ||
                bodyText.contains("completed", ignoreCase = true) -> MangaState.FINISHED
            bodyText.contains("ongoing", ignoreCase = true) -> MangaState.ONGOING
            else -> null
        }

        return manga.copy(
            title = title,
            coverUrl = cover,
            largeCoverUrl = cover,
            description = description,
            authors = setOfNotNull(author),
            tags = tags,
            state = state,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        val html = doc.html()
        val pages = parseImageUrls(html)
        if (pages.isNotEmpty()) return pages

        return doc.select("#readerarea img, .reader-area img, .entry-content img")
            .mapNotNull { image ->
                val url = image.attr("data-src").ifEmpty { image.attr("src") }.trim()
                if (url.isBlank() || url.contains("404_image", ignoreCase = true)) {
                    null
                } else {
                    MangaPage(
                        id = generateUid(url),
                        url = url.toAbsoluteUrl(domain),
                        preview = null,
                        source = source,
                    )
                }
            }
    }

    private fun parseImageUrls(html: String): List<MangaPage> {
        val raw = Regex("imageUrls\\s*:\\s*(\\[.*?])", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyList()

        return runCatching {
            val array = JSONArray(raw.replace("\\/", "/"))
            (0 until array.length()).mapNotNull { i ->
                val url = array.optString(i, "").trim()
                if (url.isBlank()) return@mapNotNull null
                MangaPage(
                    id = generateUid(url),
                    url = url.toAbsoluteUrl(domain),
                    preview = null,
                    source = source,
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun parseDate(dateStr: String): Long {
        if (dateStr.isBlank()) return 0L
        val patterns = arrayOf("d MMMM, yyyy", "MMMM d, yyyy", "d MMMM yyyy")
        for (pattern in patterns) {
            runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(dateStr)?.time
            }.getOrNull()?.let { return it }
        }
        return 0L
    }
}
