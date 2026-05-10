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
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36")
        .add("Referer", "https://$domain/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED, SortOrder.NEWEST, SortOrder.POPULARITY, SortOrder.ALPHABETICAL
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

        val items = doc.select(
            ".listupd .bs, .listupd .bsx, .bs .bsx, div.bs, " +
            ".utao .uta, .animepost, article.bs, .bixbox .listupd .bs"
        )

        return items.mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val href = a.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
            val title = el.selectFirst(".tt, h3, h4, .title")?.text()?.trim()
                ?: a.attr("title").trim().ifBlank { return@mapNotNull null }

            val cover = el.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank {
                    img.attr("data-lazy-src").ifBlank {
                        img.attr("src")
                    }
                }
            }?.ifBlank { null }

            Manga(
                id = generateUid(href),
                title = title,
                altTitles = emptySet(),
                url = href,
                publicUrl = a.attrAsAbsoluteUrl("href"),
                coverUrl = cover,
                largeCoverUrl = null,
                rating = RATING_UNKNOWN,
                contentRating = null,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    private fun buildListUrl(page: Int, order: SortOrder, filter: MangaListFilter): String {
        val url = "https://$domain/comics".toHttpUrlOrNull()!!.newBuilder()

        if (!filter.query.isNullOrEmpty()) {
            url.addQueryParameter("q", filter.query)
        } else {
            filter.tags.forEach { tag ->
                url.addQueryParameter("genres", tag.key)
            }
            val sortParam = when (order) {
                SortOrder.UPDATED -> "updatedAt"
                SortOrder.NEWEST -> "createdAt"
                SortOrder.ALPHABETICAL -> "title"
                SortOrder.POPULARITY -> "views"
                else -> "updatedAt"
            }
            url.addQueryParameter("sort", sortParam)
        }

        if (page > 1) {
            url.addQueryParameter("page", page.toString())
        }

        return url.build().toString()
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()

        val title = doc.selectFirst(
            "h1.entry-title, h1.manga-title, h1.series-title, h1"
        )?.text()?.trim() ?: manga.title

        val description = doc.selectFirst(
            ".entry-content p, .synops p, .desc p, .summary p, " +
            "[itemprop=description], .entry-content, .synops, .desc"
        )?.text()?.trim()

        val tags = doc.select(".mgen a, .genres a, .genre a").mapNotNullToSet { a ->
            val key = a.attr("href").removeSuffix("/").substringAfterLast("/")
            val tagTitle = a.text().trim()
            if (key.isNotBlank() && tagTitle.isNotBlank()) {
                MangaTag(title = tagTitle, key = key, source = source)
            } else null
        }

        val stateText = doc.selectFirst(
            ".tsinfo .imptdt i, " +
            ".infotable td:contains(Status) + td, " +
            ".spe span:contains(Status) i"
        )?.text()?.trim()

        val state = when {
            stateText == null -> null
            stateText.contains("ongoing", ignoreCase = true) ||
            stateText.contains("berjalan", ignoreCase = true) -> MangaState.ONGOING
            stateText.contains("completed", ignoreCase = true) ||
            stateText.contains("tamat", ignoreCase = true) -> MangaState.FINISHED
            stateText.contains("hiatus", ignoreCase = true) -> MangaState.PAUSED
            else -> null
        }

        val author = doc.selectFirst(
            ".infotable td:contains(Author) + td, " +
            ".tsinfo .imptdt:contains(Author) a, " +
            ".spe span:contains(Author) a"
        )?.text()?.trim()

        val chapters = doc.select("#chapterlist > ul > li, .chapterlist li")
            .mapChapters(reversed = true) { index, el ->
                val a = el.selectFirst("a") ?: return@mapChapters null
                val chUrl = a.attrAsRelativeUrlOrNull("href") ?: return@mapChapters null
                val chTitle = el.selectFirst(".chapternum")?.text()?.trim()
                MangaChapter(
                    id = generateUid(chUrl),
                    title = chTitle,
                    url = chUrl,
                    number = index + 1f,
                    volume = 0,
                    scanlator = null,
                    uploadDate = 0L,
                    branch = null,
                    source = source,
                )
            }

        val cover = doc.selectFirst(
            ".thumb img, .series-thumb img, [itemprop=image] img"
        )?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }?.ifBlank { null }

        return manga.copy(
            title = title,
            description = description,
            tags = tags,
            state = state,
            authors = setOfNotNull(author),
            coverUrl = cover ?: manga.coverUrl,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain), getRequestHeaders()).parseHtml()

        return doc.select("#readerarea img, .reading-content img, .entry-content img")
            .mapNotNull { img ->
                val url = img.attr("data-src").ifBlank {
                    img.attr("data-lazy-src").ifBlank {
                        img.attr("src")
                    }
                }.trim()
                if (url.isNotBlank() && !url.contains("placeholder") && url.startsWith("http")) {
                    MangaPage(
                        id = generateUid(url),
                        url = url,
                        preview = null,
                        source = source
                    )
                } else null
            }
    }
}
