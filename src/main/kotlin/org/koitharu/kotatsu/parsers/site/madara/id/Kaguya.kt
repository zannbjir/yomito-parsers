package org.koitharu.kotatsu.parsers.site.madara.id

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("KAGUYA", "Kaguya", "id")
internal class Kaguya(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.KAGUYA, pageSize = 20, searchPageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("01.kaguya.pro")

    override val sourceLocale: Locale = Locale("id")

    override val availableSortOrders: Set<SortOrder>
        get() = EnumSet.of(
            SortOrder.UPDATED,
            SortOrder.NEWEST,
            SortOrder.POPULARITY,
            SortOrder.ALPHABETICAL,
        )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false,
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val doc = webClient.httpGet("https://$domain/series/").parseHtml()
        val tags = doc.select(".genres__collapse ul.list-unstyled a").mapNotNull {
            val name = it.ownText().trim()
            val href = it.attrAsRelativeUrl("href").removeSuffix("/")
            val key = href.substringAfterLast("/")
            if (name.isNotBlank() && key.isNotBlank()) {
                MangaTag(title = name, key = key, source = source)
            } else null
        }.toSet()
        return MangaListFilterOptions(
            availableTags = tags,
            availableStates = EnumSet.of(
                MangaState.ONGOING,
                MangaState.FINISHED,
                MangaState.PAUSED,
                MangaState.ABANDONED,
            ),
            availableContentTypes = emptySet(),
        )
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://").append(domain)
            if (!filter.query.isNullOrEmpty()) {
                append("/page/").append(page)
                append("/?s=").append(filter.query.urlEncoded())
                append("&post_type=wp-manga")
            } else if (filter.tags.isNotEmpty()) {
                val tag = filter.tags.first()
                append("/series-genre/").append(tag.key)
                append("/page/").append(page).append("/")
            } else {
                append("/series/page/").append(page).append("/")
                when (order) {
                    SortOrder.ALPHABETICAL -> append("?m_orderby=alphabet")
                    SortOrder.NEWEST -> append("?m_orderby=new-manga")
                    SortOrder.POPULARITY -> append("?m_orderby=trending")
                    else -> append("?m_orderby=latest")
                }
                filter.states.oneOrThrowIfMany()?.let { state ->
                    append("&status=").append(
                        when (state) {
                            MangaState.ONGOING -> "on-going"
                            MangaState.FINISHED -> "end"
                            MangaState.ABANDONED -> "canceled"
                            MangaState.PAUSED -> "on-hold"
                            else -> return@let
                        },
                    )
                }
            }
        }

        val docs = webClient.httpGet(url).parseHtml()
        return docs.select("div.manga__item").mapNotNull { item ->
            val a = item.selectFirst("h2 a") ?: item.selectFirst("a") ?: return@mapNotNull null
            val imgEl = item.selectFirst("div.manga__thumb img")
            val isAdult = item.select(".manga-title-badges.adult, .manga-title-badges[class*=adult]").isNotEmpty()
            Manga(
                id = generateUid(a.attrAsRelativeUrl("href")),
                url = a.attrAsRelativeUrl("href"),
                title = a.text().trim(),
                altTitles = emptySet(),
                publicUrl = a.attrAsAbsoluteUrl("href"),
                rating = RATING_UNKNOWN,
                contentRating = if (isAdult) ContentRating.ADULT else ContentRating.SAFE,
                coverUrl = imgEl?.src(),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val publicUrl = manga.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(publicUrl).parseHtml()

        val title = doc.selectFirst("h1.post-title")?.text()?.trim()
            ?: doc.selectFirst(".post-title h1")?.text()?.trim()
            ?: manga.title

        val description = doc.selectFirst(".description-summary p")?.text()
            ?: doc.selectFirst(".summary__content p")?.text()
            ?: doc.selectFirst(".manga-summary p")?.text()

        val cover = doc.selectFirst(".summary_image img")?.src()
            ?: doc.selectFirst(".manga-thumb img")?.src()
            ?: manga.coverUrl

        val tags = doc.select(".genres-content a").mapNotNull { el ->
            val href = el.attrOrNull("href") ?: return@mapNotNull null
            val key = href.removeSuffix("/").substringAfterLast("/")
            val tagTitle = el.text().trim()
            if (key.isNotBlank() && tagTitle.isNotBlank()) {
                MangaTag(title = tagTitle, key = key, source = source)
            } else null
        }.toSet()

        val statusText = doc.select(".post-content_item").firstOrNull {
            it.selectFirst(".summary-heading")?.text()?.contains("Status", ignoreCase = true) == true
        }?.selectFirst(".summary-content")?.text()?.trim()

        val state = when {
            statusText == null -> null
            statusText.contains("OnGoing", ignoreCase = true) -> MangaState.ONGOING
            statusText.contains("Completed", ignoreCase = true) || statusText.contains("End", ignoreCase = true) -> MangaState.FINISHED
            statusText.contains("Canceled", ignoreCase = true) -> MangaState.ABANDONED
            statusText.contains("Hiatus", ignoreCase = true) || statusText.contains("Hold", ignoreCase = true) -> MangaState.PAUSED
            else -> null
        }

        val author = doc.selectFirst(".author-content a")?.text()?.trim()

        val isAdult = doc.select(".manga-title-badges.adult, .manga-title-badges[class*=adult]").isNotEmpty()
            || tags.any { it.key == "adult" || it.key == "hentai" }

        val chapters = loadChapters(publicUrl)

        return manga.copy(
            title = title,
            description = description,
            coverUrl = cover ?: manga.coverUrl,
            tags = tags,
            state = state,
            authors = setOfNotNull(author),
            contentRating = if (isAdult) ContentRating.ADULT else manga.contentRating,
            chapters = chapters,
        )
    }

    private suspend fun loadChapters(mangaUrl: String): List<MangaChapter> {
        val allChapters = mutableListOf<MangaChapter>()
        var t = 1

        while (true) {
            val ajaxUrl = mangaUrl.removeSuffix("/") + "/ajax/chapters/?t=$t"
            val ajaxDoc = webClient.httpPost(
                ajaxUrl.toHttpUrl(),
                emptyMap<String, String>(),
                Headers.Builder().add("X-Requested-With", "XMLHttpRequest").build(),
            ).parseHtml()

            val pageChapters = ajaxDoc.select("li.wp-manga-chapter").mapNotNull { li ->
                val a = li.selectFirst("a") ?: return@mapNotNull null
                val href = a.attrAsRelativeUrl("href")
                if (href.isBlank() || href == "#") return@mapNotNull null
                val chapterTitle = a.ownText().trim().ifEmpty { a.text().trim() }
                val dateText = li.selectFirst("span.chapter-release-date i")?.text()?.trim() ?: ""
                MangaChapter(
                    id = generateUid(href),
                    url = href,
                    title = chapterTitle,
                    number = 0f,
                    volume = 0,
                    branch = null,
                    uploadDate = parseChapterDate(dateText),
                    scanlator = null,
                    source = source,
                )
            }

            if (pageChapters.isEmpty()) break
            allChapters.addAll(pageChapters)
            t++
        }

        return allChapters.reversed().mapIndexed { index, chapter ->
            chapter.copy(number = (index + 1).toFloat())
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(chapterUrl).parseHtml()

        return doc.select(".reading-content .page-break img").mapNotNull { img ->
            val aesirData = img.attr("data-aesir")
            val imageUrl = if (aesirData.isNotBlank()) {
                String(Base64.getDecoder().decode(aesirData)).trim()
            } else {
                img.src()?.trim()
            }
            if (!imageUrl.isNullOrBlank()) {
                MangaPage(
                    id = generateUid(imageUrl),
                    url = imageUrl,
                    preview = null,
                    source = source,
                )
            } else null
        }
    }

    private fun parseChapterDate(dateStr: String): Long {
        if (dateStr.isBlank()) return 0L
        // relative dates like "3 minutes ago", "2 days ago"
        if (dateStr.contains("ago", ignoreCase = true)) {
            val now = System.currentTimeMillis()
            val num = Regex("""\d+""").find(dateStr)?.value?.toLongOrNull() ?: return now
            return when {
                dateStr.contains("minute") -> now - num * 60_000L
                dateStr.contains("hour") -> now - num * 3_600_000L
                dateStr.contains("day") -> now - num * 86_400_000L
                dateStr.contains("week") -> now - num * 604_800_000L
                dateStr.contains("month") -> now - num * 2_592_000_000L
                dateStr.contains("year") -> now - num * 31_536_000_000L
                else -> now
            }
        }
        return try {
            SimpleDateFormat("MMMM d, yyyy", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun String.urlEncoded(): String = java.net.URLEncoder.encode(this, "UTF-8")
}
