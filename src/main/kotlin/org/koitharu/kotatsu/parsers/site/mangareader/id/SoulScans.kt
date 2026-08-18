package org.koitharu.kotatsu.parsers.site.mangareader.id

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("SOULSCANS", "SoulScans", "id")
internal class SoulScans(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.SOULSCANS, pageSize = 30) {

    override val configKeyDomain = ConfigKey.Domain("v1.soulscans.org")

    private val apiOrigin = "https://img.soulscans.org"
    private val apiUrl = "$apiOrigin/api"

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isSearchWithFiltersSupported = true,
        isMultipleTagsSupported = false,
        isTagsExclusionSupported = false,
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val genres = webClient.httpGet("$apiUrl/genres").parseJsonArray()
        val tags = (0 until genres.length()).mapNotNull { i ->
            val genre = genres.optJSONObject(i) ?: return@mapNotNull null
            val key = genre.optString("slug", "").trim()
            val title = genre.optString("name", "").trim()
            if (key.isNotEmpty() && title.isNotEmpty()) MangaTag(title, key, source) else null
        }.toSet()
        return MangaListFilterOptions(availableTags = tags)
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val sort = when (order) {
            SortOrder.POPULARITY -> "views"
            SortOrder.ALPHABETICAL -> "az"
            else -> "latest"
        }
        val direction = if (order == SortOrder.ALPHABETICAL) "asc" else "desc"
        val url = buildString {
            append(apiUrl).append("/search?type=COMIC&limit=30&page=").append(page)
            filter.query?.takeIf { it.isNotBlank() }?.let {
                append("&q=").append(it.urlEncoded())
            }
            filter.tags.firstOrNull()?.key?.takeIf { it.isNotBlank() }?.let {
                append("&genre=").append(it.urlEncoded())
            }
            append("&sort=").append(sort)
            append("&order=").append(direction)
        }

        val json = webClient.httpGet(url, apiHeaders()).parseJson()
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            data.optJSONObject(i)?.let(::parseManga)
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.trim('/').substringAfter("comic/").substringBefore('/')
        if (slug.isEmpty()) return manga

        val json = webClient.httpGet(
            "$apiUrl/series/comic/${slug.urlEncoded()}",
            apiHeaders(),
        ).parseJson()

        val title = json.optString("title", manga.title)
        val cover = absoluteUrl(json.optString("poster_image_url", "")).ifEmpty { manga.coverUrl }
        val genres = parseGenres(json.optJSONArray("genres"))
        val chapters = parseChapters(slug, json.optJSONArray("units"))
        val authors = setOfNotNull(
            json.optString("author_name", "").takeIf { it.isNotBlank() },
            json.optString("artist_name", "").takeIf { it.isNotBlank() },
        )

        return manga.copy(
            title = title,
            altTitles = parseAlternativeTitles(json.opt("alternative_titles")),
            coverUrl = cover,
            largeCoverUrl = cover,
            description = json.optString("synopsis", "").takeIf { it.isNotBlank() },
            authors = authors,
            tags = genres,
            state = parseState(json.optString("series_status").ifBlank { json.optString("comic_status") }),
            rating = parseRating(json.optString("rating_average")),
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val path = chapter.url.trim('/')
        val comicPath = path.substringAfter("comic/")
        val slug = comicPath.substringBefore("/chapter/")
        val chapterSlug = comicPath.substringAfter("/chapter/")
        if (slug.isBlank() || chapterSlug.isBlank()) return emptyList()

        val json = webClient.httpGet(
            "$apiUrl/series/comic/${slug.urlEncoded()}/chapter/${chapterSlug.urlEncoded()}",
            apiHeaders(),
        ).parseJson()
        val chapterData = json.optJSONObject("chapter") ?: json
        val pages = chapterData.optJSONArray("pages") ?: return emptyList()

        return (0 until pages.length()).mapNotNull { i ->
            val page = pages.optJSONObject(i) ?: return@mapNotNull null
            val imageUrl = absoluteUrl(page.optString("image_url", ""))
            if (imageUrl.isEmpty()) return@mapNotNull null
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }

    private fun parseManga(json: JSONObject): Manga {
        val slug = json.optString("slug", "")
        val cover = absoluteUrl(json.optString("poster_image_url", ""))
        return Manga(
            id = generateUid(slug),
            url = "/comic/$slug",
            publicUrl = "https://$domain/comic/$slug",
            title = json.optString("title", "Untitled"),
            altTitles = parseAlternativeTitles(json.opt("alternative_titles")),
            coverUrl = cover,
            largeCoverUrl = cover,
            authors = setOfNotNull(
                json.optString("author_name", "").takeIf { it.isNotBlank() },
                json.optString("artist_name", "").takeIf { it.isNotBlank() },
            ),
            tags = emptySet(),
            state = parseState(json.optString("series_status").ifBlank { json.optString("comic_status") }),
            contentRating = ContentRating.SAFE,
            source = source,
            rating = parseRating(json.optString("rating_average")),
        )
    }

    private fun parseChapters(slug: String, units: JSONArray?): List<MangaChapter> {
        if (units == null) return emptyList()
        return (0 until units.length()).mapNotNull { i ->
            val unit = units.optJSONObject(i) ?: return@mapNotNull null
            val numberText = unit.optString("number", "").trim()
            val number = numberText.toFloatOrNull() ?: return@mapNotNull null
            val unitSlug = unit.optString("slug", "").trim()
            if (unitSlug.isEmpty()) return@mapNotNull null
            val url = "/comic/$slug/chapter/$unitSlug"
            MangaChapter(
                id = generateUid(url),
                title = "Chapter ${formatChapterNumber(numberText)}",
                url = url,
                number = number,
                uploadDate = parseDate(unit.optString("created_at")),
                source = source,
                scanlator = null,
                branch = null,
                volume = 0,
            )
        }.sortedBy { it.number }
    }

    private fun parseGenres(array: JSONArray?): Set<MangaTag> {
        if (array == null) return emptySet()
        return (0 until array.length()).mapNotNull { i ->
            val genre = array.optJSONObject(i) ?: return@mapNotNull null
            val key = genre.optString("slug", "").trim()
            val title = genre.optString("name", "").trim()
            if (key.isNotEmpty() && title.isNotEmpty()) MangaTag(title, key, source) else null
        }.toSet()
    }

    private fun parseAlternativeTitles(value: Any?): Set<String> {
        return when (value) {
            is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).takeIf(String::isNotBlank) }.toSet()
            is String -> value.split(",", ";", "\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            else -> emptySet()
        }
    }

    private fun parseState(value: String?): MangaState? = when (value?.uppercase(Locale.ROOT)) {
        "ONGOING" -> MangaState.ONGOING
        "COMPLETED", "FINISHED" -> MangaState.FINISHED
        "HIATUS" -> MangaState.PAUSED
        else -> null
    }

    private fun parseRating(value: String?): Float {
        val rating = value?.toFloatOrNull() ?: return RATING_UNKNOWN
        return (rating / 10f).takeIf { it in 0f..1f } ?: RATING_UNKNOWN
    }

    private fun parseDate(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        val patterns = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
        )
        for (pattern in patterns) {
            runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(value)?.time
            }.getOrNull()?.let { return it }
        }
        return 0L
    }

    private fun formatChapterNumber(value: String): String {
        val number = value.toFloatOrNull() ?: return value
        return if (number == number.toInt().toFloat()) {
            number.toInt().toString()
        } else {
            value.trimEnd('0').trimEnd('.')
        }
    }

    private fun absoluteUrl(value: String): String {
        val url = value.trim()
        return when {
            url.isEmpty() -> ""
            url.startsWith("http://") || url.startsWith("https://") -> url
            else -> "$apiOrigin/${url.removePrefix("/")}"
        }
    }

    private fun apiHeaders() = Headers.Builder()
        .add("Accept", "application/json")
        .add("Referer", "https://$domain/")
        .add("Origin", "https://$domain")
        .build()
}