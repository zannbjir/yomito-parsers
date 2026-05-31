package org.koitharu.kotatsu.parsers.site.id

import androidx.collection.ArraySet
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("KOMIKAPK", "KomikAPK", "id")
internal class KomikAPKParser(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.KOMIKAPK, 20) {

    override val configKeyDomain = ConfigKey.Domain("komikapk.app")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = false,
            isMultipleTagsSupported = false,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
        availableContentTypes = setOf(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
        ),
    )

    // -------------------------------------------------------------------------
    // List
    // -------------------------------------------------------------------------

    override suspend fun getList(offset: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val page = (offset / pageSize) + 1

        // Determine type segment from content type filter
        val typeSegment = when (filter.types.firstOrNull()) {
            ContentType.MANGA -> "manga"
            ContentType.MANHWA -> "manhwa"
            ContentType.MANHUA -> "manhua"
            else -> "semua"
        }

        // Determine genre segment from tag filter
        val genreSegment = filter.tags.firstOrNull()?.key ?: "semua"

        val url = "https://${getDomain()}/pustaka/$typeSegment/$genreSegment/terbaru/$page"
        val doc = webClient.httpGet(url).parseHtml()

        return doc.select("a[href*='/komik/']").mapNotNull { a ->
            val href = a.attrAsAbsoluteUrl("href")
            // Each link: "/komik/{slug}" — filter out chapter links which have 3+ segments
            val path = href.removePrefix("https://${getDomain()}")
            val segments = path.split("/").filter { it.isNotEmpty() }
            if (segments.size != 2 || segments[0] != "komik") return@mapNotNull null

            val slug = segments[1]
            val title = a.text()
                .replace(Regex("^(manhwa|manga|manhua)\\s+Ch\\.?\\s*[\\d.]+\\s+", RegexOption.IGNORE_CASE), "")
                .trim()

            if (title.isEmpty()) return@mapNotNull null

            Manga(
                id = generateUid(href),
                title = title,
                altTitle = null,
                url = href,
                publicUrl = href,
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = "https://s1.cdn-guard.com/komikapk2-cover/$slug.webp",
                tags = emptySet(),
                state = null,
                author = null,
                largeCoverUrl = null,
                description = null,
                chapters = null,
                source = source,
            )
        }.distinctBy { it.id }
    }

    // -------------------------------------------------------------------------
    // Details
    // -------------------------------------------------------------------------

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.url).parseHtml()

        val title = doc.selectFirst("h1")?.text()?.trim() ?: manga.title

        val coverUrl = doc.selectFirst("meta[property='og:image']")
            ?.attr("content")
            ?.trimStart('$')
            ?: manga.coverUrl

        val description = doc.select("p").firstOrNull { p ->
            p.text().length > 50 && !p.text().startsWith("KomikAPK")
        }?.text()

        val tags = doc.select("a[href*='/pustaka/semua/']").mapNotNullTo(ArraySet()) { a ->
            val tagSlug = a.attr("href")
                .substringAfter("/pustaka/semua/")
                .substringBefore("/terbaru/")
                .trim()
            if (tagSlug.isEmpty() || tagSlug == "semua") return@mapNotNullTo null
            MangaTag(
                title = a.text().trim().replaceFirstChar { it.uppercase() },
                key = tagSlug,
                source = source,
            )
        }

        // Detect content type from page text / tags
        val typeText = doc.selectFirst("meta[property='og:description']")?.attr("content") ?: ""
        val contentType = when {
            "manhwa" in typeText.lowercase() -> ContentType.MANHWA
            "manhua" in typeText.lowercase() -> ContentType.MANHUA
            else -> ContentType.MANGA
        }

        // Chapters: links matching /komik/{slug}/{uploader}/{chapter}
        val slug = manga.url.substringAfterLast("/komik/").substringBefore("/")
        val chapterLinks = doc.select("a[href*='/komik/$slug/']").filter { a ->
            val path = a.attr("href").split("/").filter { it.isNotEmpty() }
            // pattern: komik / slug / uploader / chapter  = 4 segments
            path.size == 4
        }

        val chapters = chapterLinks.mapIndexedNotNull { index, a ->
            val href = a.attrAsAbsoluteUrl("href")
            val chapterText = a.text().trim()
            // Extract chapter number from text like "Chapter 001"
            val chapterNum = chapterText.replace(Regex("[^\\d.]"), "").toFloatOrNull() ?: (index + 1).toFloat()

            MangaChapter(
                id = generateUid(href),
                title = chapterText,
                number = chapterNum,
                volume = 0,
                url = href,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source,
            )
        }.sortedBy { it.number }

        return manga.copy(
            title = title,
            coverUrl = coverUrl,
            description = description,
            tags = tags,
            contentType = contentType,
            chapters = chapters,
        )
    }

    // -------------------------------------------------------------------------
    // Pages
    // -------------------------------------------------------------------------

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url).parseHtml()

        // Images are directly in the HTML as <img> tags with src from cdn-guard.com
        return doc.select("img[src*='cdn-guard.com/komikapk2-chapter']").mapIndexed { index, img ->
            val url = img.attr("src")
            MangaPage(
                id = generateUid("$url/$index"),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    // -------------------------------------------------------------------------
    // Tags
    // -------------------------------------------------------------------------

    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        val doc = webClient.httpGet("https://${getDomain()}/pustaka/semua/semua/terbaru/1").parseHtml()
        return doc.select("a[href*='/pustaka/semua/']").mapNotNullTo(ArraySet()) { a ->
            val tagSlug = a.attr("href")
                .substringAfter("/pustaka/semua/")
                .substringBefore("/terbaru/")
                .trim()
            if (tagSlug.isEmpty() || tagSlug == "semua") return@mapNotNullTo null
            MangaTag(
                title = a.text().trim().replaceFirstChar { it.uppercase() },
                key = tagSlug,
                source = source,
            )
        }
    }
}
