package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANHWAKU", "ManhwaKu", "id")
internal class Manhwaku(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANHWAKU, 20) {

    override val configKeyDomain = ConfigKey.Domain("manhwaku.biz.id")

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST)

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(isSearchSupported = true)

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val isSearch = !filter.query.isNullOrEmpty()
        val url = if (isSearch) {
            "https://$domain/jelajahi?search=${filter.query.urlEncoded()}&page=$page"
        } else {
            "https://$domain/jelajahi?page=$page"
        }

        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
        val results = mutableListOf<Manga>()

        doc.select("a[href^=/detail/]").forEach { a ->
            val href = a.attr("href")
            val slug = href.substringAfter("/detail/").substringBefore('?').trim('/')
            if (slug.isEmpty()) return@forEach

            val title = a.selectFirst("h3")?.text()?.trim()
                ?: a.selectFirst("img")?.attr("alt")?.trim()
                ?: return@forEach
            if (title.isBlank()) return@forEach

            val cover = a.selectFirst("img")?.let { img ->
                val raw = img.attr("src")
                when {
                    raw.startsWith("/_next/image") -> {
                        val encoded = Regex("""url=([^&]+)""").find(raw)?.groupValues?.get(1)
                        encoded?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                    }
                    raw.startsWith("/") -> "https://$domain$raw"
                    else -> raw
                }
            }.orEmpty()

            val stateText = a.selectFirst(".text-emerald-400, .text-rose-400, .text-amber-400")?.text()
            val state = when {
                stateText == null -> null
                stateText.contains("Ongoing", ignoreCase = true) -> MangaState.ONGOING
                stateText.contains("Completed", ignoreCase = true) ||
                    stateText.contains("Tamat", ignoreCase = true) -> MangaState.FINISHED
                else -> null
            }

            results.add(
                Manga(
                    id = generateUid(slug),
                    title = title,
                    altTitles = emptySet(),
                    url = "/detail/$slug",
                    publicUrl = "https://$domain/detail/$slug",
                    rating = RATING_UNKNOWN,
                    contentRating = ContentRating.SAFE,
                    coverUrl = cover,
                    tags = emptySet(),
                    state = state,
                    authors = emptySet(),
                    source = source,
                ),
            )
        }

        return results.distinctBy { it.url }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.substringAfter("/detail/").trim('/')
        val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()

        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: manga.title

        // Description: find the paragraph inside the Sinopsis section.
        val description = doc.select("p")
            .map { it.text().trim() }
            .firstOrNull { it.length > 50 }

        // State: derived from badges shown in detail card.
        val bodyText = doc.body().text()
        val state = when {
            bodyText.contains("Ongoing", ignoreCase = true) -> MangaState.ONGOING
            bodyText.contains("Completed", ignoreCase = true) ||
                bodyText.contains("Tamat", ignoreCase = true) -> MangaState.FINISHED
            else -> manga.state
        }

        // Chapter list is embedded in the RSC stream (self.__next_f.push chunks) even
        // though only the first page is rendered as anchors. Parse the full list directly
        // from the raw HTML.
        val rawHtml = doc.html()
        val chapterRegex = Regex(
            "\\\\\"slug\\\\\":\\\\\"(chapter-[^\\\\]+)\\\\\",\\\\\"title\\\\\":\\\\\"([^\\\\]+)\\\\\"," +
                "\\\\\"url\\\\\":\\\\\"[^\\\\]*\\\\\",\\\\\"waktu_rilis\\\\\":\\\\\"(?:\\\$D)?([^\\\\]+)\\\\\""
        )
        val seen = HashSet<String>()
        val chapters = chapterRegex.findAll(rawHtml).mapNotNull { m ->
            val chSlug = m.groupValues[1]
            if (!seen.add(chSlug)) return@mapNotNull null
            val chTitle = m.groupValues[2]
            val date = m.groupValues[3]
            val number = Regex("""chapter-(\d+(?:\.\d+)?)""").find(chSlug)
                ?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
            val urlPath = "/read/$slug/$chSlug"
            MangaChapter(
                id = generateUid(urlPath),
                title = chTitle,
                url = urlPath,
                number = number,
                volume = 0,
                scanlator = null,
                uploadDate = parseIsoDate(date),
                branch = null,
                source = source,
            )
        }.toList().sortedBy { it.number }

        return manga.copy(
            title = title,
            description = description ?: manga.description,
            state = state,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain), getRequestHeaders()).parseHtml()
        // Real manga pages have `alt` like "<title> Chapter <n> - Page <i>".
        // This filter excludes thumbnails/avatars/ads.
        val seen = HashSet<String>()
        return doc.select("img[alt*=Page]").mapNotNull { img ->
            var url = img.attr("src")
            if (url.isBlank()) url = img.attr("data-src")
            if (url.isBlank()) return@mapNotNull null
            if (url.startsWith("/")) url = "https://$domain$url"
            if (!seen.add(url)) return@mapNotNull null
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    private fun parseIsoDate(raw: String): Long {
        if (raw.isBlank()) return 0L
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.US)
            fmt.parse(raw)?.time ?: 0L
        } catch (_: Exception) {
            try {
                val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.US)
                fmt.parse(raw)?.time ?: 0L
            } catch (_: Exception) {
                0L
            }
        }
    }
}
