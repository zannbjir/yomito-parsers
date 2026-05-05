package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("AINZSCANS", "AinzScans", "id")
internal class AinzScans(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.AINZSCANS, 20) {

    override val configKeyDomain = ConfigKey.Domain("v1.ainzscans01.com")

    private val apiDomain get() = "api.ainzscans01.com"

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        if (host != apiDomain && !host.endsWith("ainzscans01.com")) {
            val newRequest = request.newBuilder()
                .header("Referer", "https://$domain/")
                .build()
            return chain.proceed(newRequest)
        }
        return chain.proceed(request)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
        SortOrder.RATING,
        SortOrder.UPDATED,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = false,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchTags(),
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.PAUSED,
        ),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
        ),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(apiDomain)
            append("/api/search?type=COMIC&limit=20&page=")
            append(page)

            if (!filter.query.isNullOrEmpty()) {
                append("&q=")
                append(filter.query.urlEncoded())
            }

            val sort = when (order) {
                SortOrder.NEWEST -> "new"
                SortOrder.POPULARITY -> "views"
                SortOrder.RATING -> "rate"
                SortOrder.UPDATED -> "latest"
                else -> "latest"
            }
            append("&sort=")
            append(sort)
            append("&order=desc")

            filter.states.oneOrThrowIfMany()?.let {
                append("&status=")
                append(
                    when (it) {
                        MangaState.ONGOING -> "ONGOING"
                        MangaState.FINISHED -> "COMPLETED"
                        MangaState.PAUSED -> "HIATUS"
                        else -> ""
                    }
                )
            }

            filter.tags.oneOrThrowIfMany()?.let {
                append("&genre=")
                append(it.key)
            }

            filter.types.oneOrThrowIfMany()?.let {
                append("&comic_type=")
                append(
                    when (it) {
                        ContentType.MANGA -> "MANGA"
                        ContentType.MANHWA -> "MANHWA"
                        ContentType.MANHUA -> "MANHUA"
                        else -> ""
                    }
                )
            }
        }

        val json = webClient.httpGet(url).parseJson()
        val data = json.optJSONArray("data") ?: return emptyList()

        return data.mapJSONNotNull { jo ->
            val slug = jo.optString("slug").ifBlank { return@mapJSONNotNull null }
            val title = jo.optString("title").ifBlank { return@mapJSONNotNull null }
            Manga(
                id = generateUid(slug),
                title = title,
                altTitles = emptySet(),
                url = "/comic/$slug",
                publicUrl = "https://$domain/comic/$slug",
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = jo.optString("poster_image_url").orEmpty(),
                tags = emptySet(),
                state = when (jo.optString("comic_status").uppercase()) {
                    "ONGOING" -> MangaState.ONGOING
                    "COMPLETED" -> MangaState.FINISHED
                    "HIATUS" -> MangaState.PAUSED
                    else -> null
                },
                authors = emptySet(),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.removePrefix("/comic/")
        val json = webClient.httpGet("https://$apiDomain/api/series/comic/$slug").parseJson()

        val tags = json.optJSONArray("genres")?.mapJSONNotNull { jo ->
            val tagSlug = jo.optString("slug").ifBlank { return@mapJSONNotNull null }
            val tagName = jo.optString("name").ifBlank { return@mapJSONNotNull null }
            MangaTag(
                key = tagSlug,
                title = tagName.toTitleCase(),
                source = source,
            )
        }?.toSet() ?: emptySet()

        val authors = setOfNotNull(
            json.optString("author_name").takeIf { it.isNotBlank() },
            json.optString("artist_name").takeIf { it.isNotBlank() && it != json.optString("author_name") },
        )

        val state = when (json.optString("comic_status").uppercase()) {
            "ONGOING" -> MangaState.ONGOING
            "COMPLETED" -> MangaState.FINISHED
            "HIATUS" -> MangaState.PAUSED
            else -> null
        }

        val units = json.optJSONArray("units") ?: return manga.copy(
            description = json.optString("synopsis"),
            tags = tags,
            authors = authors,
            state = state,
        )

        val chapters = units.mapJSONNotNull { jo ->
            val chapterSlug = jo.optString("slug").ifBlank { return@mapJSONNotNull null }
            val number = jo.optString("number").toFloatOrNull() ?: 0f
            val chapterTitle = jo.optString("title").takeIf { it.isNotBlank() }
                ?: jo.optString("number").removeSuffix(".00")
            MangaChapter(
                id = generateUid(chapterSlug),
                title = "Chapter $chapterTitle",
                url = "/comic/$slug/chapter/$chapterSlug",
                number = number,
                volume = 0,
                scanlator = null,
                uploadDate = parseDate(jo.optString("created_at")),
                branch = null,
                source = source,
            )
        }.sortedBy { it.number }

        return manga.copy(
            title = json.optString("title").takeIf { it.isNotBlank() } ?: manga.title,
            coverUrl = json.optString("poster_image_url").takeIf { it.isNotBlank() } ?: manga.coverUrl,
            description = json.optString("synopsis"),
            tags = tags,
            authors = authors,
            state = state,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val parts = chapter.url.removePrefix("/").split("/")
        val seriesSlug = parts.getOrNull(1) ?: return emptyList()
        val chapterSlug = parts.getOrNull(3) ?: return emptyList()

        val json = webClient.httpGet(
            "https://$apiDomain/api/series/comic/$seriesSlug/chapter/$chapterSlug"
        ).parseJson()

        val pages = json.optJSONObject("chapter")?.optJSONArray("pages") ?: return emptyList()

        return pages.mapJSONNotNull { jo ->
            val imageUrl = jo.optString("image_url").ifBlank { return@mapJSONNotNull null }.let { url ->
                if (url.startsWith("http")) url else "https://$apiDomain$url"
            }
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }

    private suspend fun fetchTags(): Set<MangaTag> {
        return setOf(
            MangaTag(key = "action", title = "Action", source = source),
            MangaTag(key = "adult", title = "Adult", source = source),
            MangaTag(key = "adventure", title = "Adventure", source = source),
            MangaTag(key = "beasts", title = "Beasts", source = source),
            MangaTag(key = "comedy", title = "Comedy", source = source),
            MangaTag(key = "cooking", title = "Cooking", source = source),
            MangaTag(key = "crime", title = "Crime", source = source),
            MangaTag(key = "drama", title = "Drama", source = source),
            MangaTag(key = "ecchi", title = "Ecchi", source = source),
            MangaTag(key = "fantasy", title = "Fantasy", source = source),
            MangaTag(key = "gender-bender", title = "Gender Bender", source = source),
            MangaTag(key = "gore", title = "Gore", source = source),
            MangaTag(key = "harem", title = "Harem", source = source),
            MangaTag(key = "historical", title = "Historical", source = source),
            MangaTag(key = "horror", title = "Horror", source = source),
            MangaTag(key = "isekai", title = "Isekai", source = source),
            MangaTag(key = "josei", title = "Josei", source = source),
            MangaTag(key = "magic", title = "Magic", source = source),
            MangaTag(key = "manga", title = "Manga", source = source),
            MangaTag(key = "manhwa", title = "Manhwa", source = source),
            MangaTag(key = "martial-arts", title = "Martial Arts", source = source),
            MangaTag(key = "mature", title = "Mature", source = source),
            MangaTag(key = "mecha", title = "Mecha", source = source),
            MangaTag(key = "medical", title = "Medical", source = source),
            MangaTag(key = "military", title = "Military", source = source),
            MangaTag(key = "monsters", title = "Monsters", source = source),
            MangaTag(key = "murim", title = "Murim", source = source),
            MangaTag(key = "music", title = "Music", source = source),
            MangaTag(key = "mystery", title = "Mystery", source = source),
            MangaTag(key = "psychological", title = "Psychological", source = source),
            MangaTag(key = "reincarnation", title = "Reincarnation", source = source),
            MangaTag(key = "romance", title = "Romance", source = source),
            MangaTag(key = "school-life", title = "School Life", source = source),
            MangaTag(key = "sci-fi", title = "Sci Fi", source = source),
            MangaTag(key = "seinen", title = "Seinen", source = source),
            MangaTag(key = "shoujo", title = "Shoujo", source = source),
            MangaTag(key = "shounen", title = "Shounen", source = source),
            MangaTag(key = "shounen-ai", title = "Shounen Ai", source = source),
            MangaTag(key = "slice-of-life", title = "Slice Of Life", source = source),
            MangaTag(key = "smut", title = "Smut", source = source),
            MangaTag(key = "sports", title = "Sports", source = source),
            MangaTag(key = "supernatural", title = "Supernatural", source = source),
            MangaTag(key = "survival", title = "Survival", source = source),
            MangaTag(key = "system", title = "System", source = source),
            MangaTag(key = "thriller", title = "Thriller", source = source),
            MangaTag(key = "tragedy", title = "Tragedy", source = source),
            MangaTag(key = "wuxia", title = "Wuxia", source = source),
        )
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrEmpty()) return 0L
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(dateStr)?.time ?: 0L
        } catch (_: Exception) {
            try {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .parse(dateStr)?.time ?: 0L
            } catch (_: Exception) {
                0L
            }
        }
    }
}
