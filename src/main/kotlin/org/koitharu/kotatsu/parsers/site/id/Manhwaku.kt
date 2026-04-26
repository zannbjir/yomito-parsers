package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.json.JSONArray
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
    PagedMangaParser(context, MangaParserSource.MANHWAKU, pageSize = PAGE_SIZE) {

    override val configKeyDomain = ConfigKey.Domain("manhwaku.biz.id")

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .add("Accept", "application/json,text/html;q=0.9,*/*;q=0.8")
        .build()

    override val availableSortOrders: Set<SortOrder> =
        EnumSet.of(SortOrder.UPDATED, SortOrder.NEWEST, SortOrder.POPULARITY, SortOrder.ALPHABETICAL, SortOrder.ALPHABETICAL_DESC)

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(isSearchSupported = true)

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        // The site is a Next.js App Router site that fetches all manga client-side from a JSON
        // endpoint. The HTML pages contain only an empty shell, so HTML parsing returns nothing.
        // Use the same JSON endpoint the site itself uses; it returns the entire catalogue
        // (~450 entries) so we paginate locally.
        val all = fetchAllManhwa()
        val query = filter.query?.trim()?.lowercase()
        val filtered = all.asSequence()
            .filter { entry ->
                if (query.isNullOrEmpty()) true else entry.title.lowercase().contains(query)
            }
            .filter { entry ->
                if (filter.states.isEmpty()) true else {
                    val s = mapState(entry.statusRaw)
                    s != null && filter.states.contains(s)
                }
            }
            .toList()

        val sorted = when (order) {
            SortOrder.POPULARITY -> filtered.sortedByDescending { it.rating }
            SortOrder.ALPHABETICAL -> filtered.sortedBy { it.title.lowercase() }
            SortOrder.ALPHABETICAL_DESC -> filtered.sortedByDescending { it.title.lowercase() }
            SortOrder.UPDATED, SortOrder.NEWEST -> filtered.sortedByDescending { it.updateMillis }
            else -> filtered
        }

        val from = (page - 1) * PAGE_SIZE
        if (from >= sorted.size) return emptyList()
        val to = minOf(sorted.size, from + PAGE_SIZE)
        return sorted.subList(from, to).map { entry ->
            Manga(
                id = generateUid(entry.slug),
                title = entry.title,
                altTitles = emptySet(),
                url = "/detail/${entry.slug}",
                publicUrl = "https://$domain/detail/${entry.slug}",
                rating = if (entry.rating > 0f) (entry.rating / 10f).coerceIn(0f, 1f) else RATING_UNKNOWN,
                contentRating = ContentRating.SAFE,
                coverUrl = entry.cover,
                tags = entry.genres.mapTo(HashSet()) { MangaTag(title = it, key = it.lowercase(), source = source) },
                state = mapState(entry.statusRaw),
                authors = if (entry.author.isNotBlank()) setOf(entry.author) else emptySet(),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.substringAfter("/detail/").trim('/')
        val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()

        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: manga.title

        val description = doc.select("p")
            .map { it.text().trim() }
            .firstOrNull { it.length > 50 }

        val bodyText = doc.body().text()
        val state = when {
            bodyText.contains("Ongoing", ignoreCase = true) ||
                bodyText.contains("Berjalan", ignoreCase = true) -> MangaState.ONGOING

            bodyText.contains("Completed", ignoreCase = true) ||
                bodyText.contains("Tamat", ignoreCase = true) -> MangaState.FINISHED

            bodyText.contains("Hiatus", ignoreCase = true) -> MangaState.PAUSED
            else -> manga.state
        }

        // Chapter list is embedded as JSON inside the RSC stream (self.__next_f.push chunks).
        // The shape is roughly:
        //   ...,\"slug\":\"chapter-3\",\"title\":\"Chapter 3\",\"url\":\"...\",\"waktu_rilis\":\"$D2026-01-01T...\",...
        // The regex below extracts every distinct chapter entry from the raw HTML.
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
        // Real chapter images have alt like "<title> Chapter <n> - Page <i>".
        // This excludes thumbnails/avatars/ads.
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

    private suspend fun fetchAllManhwa(): List<ManhwaEntry> {
        val cached = catalogueCache
        if (cached != null) return cached
        val raw = webClient.httpGet("https://$domain/api/all_manhwa", getRequestHeaders()).parseRaw()
        val arr = JSONArray(raw)
        val out = ArrayList<ManhwaEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val genres = o.optJSONArray("genres")?.let { ja ->
                List(ja.length()) { ja.optString(it) }.filter { it.isNotBlank() }
            }.orEmpty()
            out.add(
                ManhwaEntry(
                    slug = o.optString("slug"),
                    title = o.optString("title"),
                    cover = o.optString("cover_url"),
                    rating = o.optString("rating").toFloatOrNull() ?: 0f,
                    statusRaw = o.optString("status"),
                    genres = genres,
                    author = o.optString("pengarang"),
                    updateMillis = parseIsoDate(o.optString("lastUpdateTime")),
                ),
            )
        }
        catalogueCache = out
        return out
    }

    private fun mapState(raw: String?): MangaState? = when {
        raw == null -> null
        raw.equals("Berjalan", ignoreCase = true) ||
            raw.equals("Ongoing", ignoreCase = true) -> MangaState.ONGOING

        raw.equals("Tamat", ignoreCase = true) ||
            raw.equals("Completed", ignoreCase = true) -> MangaState.FINISHED

        raw.equals("Hiatus", ignoreCase = true) -> MangaState.PAUSED
        else -> null
    }

    private fun parseIsoDate(raw: String): Long {
        if (raw.isBlank()) return 0L
        val tries = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
        )
        for (pattern in tries) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                fmt.timeZone = TimeZone.getTimeZone("UTC")
                fmt.parse(raw)?.let { return it.time }
            } catch (_: Exception) {
                // try next
            }
        }
        return 0L
    }

    private data class ManhwaEntry(
        val slug: String,
        val title: String,
        val cover: String,
        val rating: Float,
        val statusRaw: String,
        val genres: List<String>,
        val author: String,
        val updateMillis: Long,
    )

    @Volatile
    private var catalogueCache: List<ManhwaEntry>? = null

    companion object {
        private const val PAGE_SIZE = 24
    }
}
