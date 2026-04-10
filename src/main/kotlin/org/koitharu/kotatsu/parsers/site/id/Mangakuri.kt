package org.koitharu.kotatsu.parsers.site.id

import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANGAKURI", "Mangakuri", "id")
internal class Mangakuri(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANGAKURI, 20) {

    override val configKeyDomain = ConfigKey.Domain("mangakuri.org")

    private val apiDomain get() = "api.$domain"

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
                SortOrder.UPDATED -> "new"
                else -> "new"
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

        return data.mapJSON { jo ->
            val slug = jo.getString("slug")
            Manga(
                id = generateUid(slug),
                title = jo.getString("title"),
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
        val json = webClient.httpGet("https://$apiDomain/api/series/$slug").parseJson()

        val tags = json.optJSONArray("genres")?.mapJSON { jo ->
            MangaTag(
                key = jo.getString("slug"),
                title = jo.getString("name").toTitleCase(),
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

        val chapters = units.mapJSON { jo ->
            val chapterSlug = jo.getString("slug")
            val number = jo.optString("number").toFloatOrNull() ?: 0f
            MangaChapter(
                id = generateUid(chapterSlug),
                title = "Chapter ${jo.optString("number").removeSuffix(".00")}",
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
        // URL format: /comic/{seriesSlug}/chapter/{chapterSlug}
        val parts = chapter.url.removePrefix("/").split("/")
        // parts: [comic, seriesSlug, chapter, chapterSlug]
        val seriesSlug = parts.getOrNull(1) ?: return emptyList()
        val chapterSlug = parts.getOrNull(3) ?: return emptyList()

        val json = webClient.httpGet(
            "https://$apiDomain/api/series/$seriesSlug/chapter/$chapterSlug"
        ).parseJson()

        val pages = json.optJSONObject("chapter")?.optJSONArray("pages") ?: return emptyList()

        return pages.mapJSON { jo ->
            val imageUrl = jo.getString("image_url")
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source,
            )
        }
    }

    private suspend fun fetchTags(): Set<MangaTag> {
        return runCatching {
            val json = webClient.httpGet("https://$apiDomain/api/genres").parseJson()
            val data = json.optJSONArray("data") ?: return@runCatching emptySet()
            data.mapJSON { jo ->
                MangaTag(
                    key = jo.getString("slug"),
                    title = jo.getString("name").toTitleCase(),
                    source = source,
                )
            }.toSet()
        }.getOrElse { emptySet() }
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
