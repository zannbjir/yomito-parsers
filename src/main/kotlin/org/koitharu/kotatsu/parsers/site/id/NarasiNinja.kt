package org.koitharu.kotatsu.parsers.site.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("NARASININJA", "NarasiNinja", "id")
internal class NarasiNinjaParser(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.NARASININJA, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("narasininja.net")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED, SortOrder.NEWEST, SortOrder.POPULARITY, SortOrder.ALPHABETICAL
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = true,
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
        availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA),
    )

    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain/komik").parseHtml()
        // Ambil dari filter button + genre page
        return doc.select("button.px-3\\.py-1\\.5, .genre a, ul.genrez li a").mapNotNull { el ->
            val text = el.text().trim()
            if (text.isNotEmpty()) MangaTag(text, text.lowercase(), source) else null
        }.toSet()
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = urlBuilder().apply {
            when {
                !filter.query.isNullOrEmpty() -> {
                    // Search page
                    addPathSegment("search")
                    addQueryParameter("s", filter.query)
                }
                filter.tags.isNotEmpty() || filter.tagsExclude.isNotEmpty() -> {
                    // Genre filter (pakai path /genre/ kalau single, atau query kalau multi)
                    if (filter.tags.size == 1 && filter.tagsExclude.isEmpty()) {
                        addPathSegment("genre")
                        addPathSegment(filter.tags.first().key)
                    } else {
                        addPathSegment("komik")
                        filter.tags.forEach { addQueryParameter("genre[]", it.key) }
                        filter.tagsExclude.forEach { addQueryParameter("genre[]", "-${it.key}") }
                    }
                }
                else -> addPathSegment("komik")
            }

            // Sort order
            if (!filter.query.isNullOrEmpty() && page > 1) {
                addPathSegment("page").addPathSegment(page.toString())
            } else if (page > 1) {
                addQueryParameter("page", page.toString())
            }

            addQueryParameter("order", when (order) {
                SortOrder.ALPHABETICAL -> "title"
                SortOrder.NEWEST -> "latest"
                SortOrder.POPULARITY -> "popular"
                SortOrder.UPDATED -> "update"
                else -> "update"
            })
        }.build()

        val doc = webClient.httpGet(url).parseHtml()

        // Selector yang lebih luas (handle baik list biasa maupun search)
        return doc.select(".listupd .bs .bsx, .bs .bsx, a[href^='/komik/'] > .thumb").mapNotNull { el ->
            val link = el.selectFirst("a") ?: el
            val href = link.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
            val title = el.selectFirst(".tt a, .title")?.text()
                ?: link.attr("title").orEmpty().ifBlank { return@mapNotNull null }

            Manga(
                id = generateUid(href),
                title = title,
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                coverUrl = el.selectFirst("img")?.src(),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source
            )
        }.distinctBy { it.id }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl.toAbsoluteUrl(domain)).parseHtml()

        val author = doc.selectFirst("td:contains(Author), .author")?.nextElementSibling()?.text()?.trim()
        val statusText = doc.selectFirst("td:contains(Status)")?.nextElementSibling()?.text()?.trim()
        val state = when (statusText?.lowercase()) {
            "ongoing", "berlangsung" -> MangaState.ONGOING
            "completed", "tamat" -> MangaState.FINISHED
            else -> null
        }
        val description = doc.selectFirst(".entry-content, .desc, .sinopsis, .summary")?.text()?.trim()

        val tags = doc.select(".seriestugenre a, .genre a, button.px-3\\.py-1\\.5").mapNotNullToSet {
            val text = it.text().trim()
            if (text.isNotEmpty()) MangaTag(text, text.lowercase(), source) else null
        }

        val chapters = doc.select("#chapterlist ul > li, .chapter-list li").mapChapters(reversed = true) { index, el ->
            val link = el.selectFirst("a") ?: return@mapChapters null
            val url = link.attrAsRelativeUrlOrNull("href") ?: return@mapChapters null
            val title = el.selectFirst(".chapternum, .chapter-title")?.text() ?: "Chapter ${index + 1}"

            MangaChapter(
                id = generateUid(url),
                title = title,
                url = url,
                number = index + 1f,
                volume = 0,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source
            )
        }

        return manga.copy(
            authors = setOfNotNull(author),
            state = state,
            description = description,
            tags = tags,
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

        return doc.select("img.ts-main-image, .reading-content img, .reader-area img, #reader img, section img")
            .mapNotNull { img ->
                val url = img.attrAsAbsoluteUrlOrNull("src") ?: img.attrAsAbsoluteUrlOrNull("data-src")
                if (url.isNullOrBlank() || url.contains("data:")) return@mapNotNull null
                MangaPage(generateUid(url), url, null, source)
            }
    }
}
