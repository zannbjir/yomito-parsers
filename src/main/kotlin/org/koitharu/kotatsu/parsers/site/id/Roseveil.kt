package org.koitharu.kotatsu.parsers.site.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("ROSEVEIL", "Roseveil", "id", ContentType.HENTAI)
internal class Roseveil(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.ROSEVEIL, 20) {

    override val configKeyDomain = ConfigKey.Domain("roseveil.org")

    private val apiUrl get() = "https://api.$domain/api"

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
        SortOrder.RATING,
        SortOrder.UPDATED,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
            isMultipleTagsSupported = false,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = buildTagSet(),
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
            append(apiUrl)
            append("/search?type=COMIC&limit=20&page=")
            append(page)

            if (!filter.query.isNullOrEmpty()) {
                append("&q=")
                append(filter.query.urlEncoded())
            }

            append("&sort=")
            append(
                when (order) {
                    SortOrder.NEWEST -> "new"
                    SortOrder.POPULARITY -> "views"
                    SortOrder.RATING -> "rating"
                    SortOrder.ALPHABETICAL -> "title"
                    SortOrder.UPDATED -> "new"
                    else -> "new"
                },
            )

            append("&order=")
            append(if (order == SortOrder.ALPHABETICAL) "asc" else "desc")

            filter.states.oneOrThrowIfMany()?.let {
                append("&status=")
                append(
                    when (it) {
                        MangaState.ONGOING -> "ONGOING"
                        MangaState.FINISHED -> "COMPLETED"
                        MangaState.PAUSED -> "HIATUS"
                        else -> ""
                    },
                )
            }

            filter.tags.oneOrThrowIfMany()?.let {
                append("&genre=")
                append(it.key)
            }

            filter.types.oneOrThrowIfMany()?.let {
                append("&subtype=")
                append(
                    when (it) {
                        ContentType.MANGA -> "MANGA"
                        ContentType.MANHWA -> "MANHWA"
                        ContentType.MANHUA -> "MANHUA"
                        else -> ""
                    },
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
                url = slug,
                publicUrl = "https://$domain/comic/$slug",
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.ADULT,
                coverUrl = jo.optString("poster_image_url").orEmpty(),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val json = webClient.httpGet("$apiUrl/series/comic/${manga.url}").parseJson()

        val tags = json.optJSONArray("genres")?.mapJSON { jo ->
            MangaTag(
                title = jo.getString("name").toTitleCase(),
                key = jo.getString("slug"),
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

        val slug = json.optString("slug").ifBlank { manga.url }

        val chapters = units.mapJSON { jo ->
            val chapterSlug = jo.getString("slug")
            val numberStr = jo.optString("number")
            val number = numberStr.toFloatOrNull() ?: 0f
            val displayNumber = formatChapterNumber(numberStr)
            MangaChapter(
                id = generateUid("$slug/chapter/$chapterSlug"),
                title = "Chapter $displayNumber",
                url = "$slug/chapter/$chapterSlug",
                number = number,
                volume = 0,
                scanlator = null,
                uploadDate = parseDate(jo.optString("created_at")),
                branch = null,
                source = source,
            )
        }.sortedBy { it.number }

        return manga.copy(
            title = json.optString("title").ifBlank { manga.title },
            coverUrl = json.optString("poster_image_url").ifBlank { manga.coverUrl },
            description = json.optString("synopsis"),
            tags = tags,
            authors = authors,
            state = state,
            chapters = chapters,
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val parts = chapter.url.split("/chapter/")
        if (parts.size != 2) return emptyList()
        val seriesSlug = parts[0]
        val chapterSlug = parts[1]

        val json = webClient.httpGet(
            "$apiUrl/series/comic/$seriesSlug/chapter/$chapterSlug",
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
        }.sortedBy {
            it.id
        }
    }

    private fun formatChapterNumber(number: String): String {
        val f = number.toFloatOrNull() ?: return number
        return if (f == f.toLong().toFloat()) f.toLong().toString() else f.toString()
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

    private fun buildTagSet(): Set<MangaTag> = linkedSetOf(
        MangaTag("Action", "action", source),
        MangaTag("Adult", "adult", source),
        MangaTag("Adventure", "adventure", source),
        MangaTag("Animals", "animals", source),
        MangaTag("Boys Love", "boys-love", source),
        MangaTag("Comedy", "comedy", source),
        MangaTag("Crime", "crime", source),
        MangaTag("Demon", "demon", source),
        MangaTag("Drama", "drama", source),
        MangaTag("Ecchi", "ecchi", source),
        MangaTag("Fantasy", "fantasy", source),
        MangaTag("Game", "game", source),
        MangaTag("Gender Bender", "gender-bender", source),
        MangaTag("Harem", "harem", source),
        MangaTag("Historical", "historical", source),
        MangaTag("Horror", "horror", source),
        MangaTag("Isekai", "isekai", source),
        MangaTag("Josei", "josei", source),
        MangaTag("Magic", "magic", source),
        MangaTag("Manhwa", "manhwa", source),
        MangaTag("Martial Arts", "martial-arts", source),
        MangaTag("Mature", "mature", source),
        MangaTag("Medical", "medical", source),
        MangaTag("Mirror", "mirror", source),
        MangaTag("Mystery", "mystery", source),
        MangaTag("Office Workers", "office-workers", source),
        MangaTag("Project", "project", source),
        MangaTag("Psychological", "psychological", source),
        MangaTag("Regression", "regression", source),
        MangaTag("Reincarnation", "reincarnation", source),
        MangaTag("Revenge", "revenge", source),
        MangaTag("Reverse Harem", "reverse-harem", source),
        MangaTag("Romance", "romance", source),
        MangaTag("Royalty", "royalty", source),
        MangaTag("School Life", "school-life", source),
        MangaTag("Sci Fi", "sci-fi", source),
        MangaTag("Seinen", "seinen", source),
        MangaTag("Shoujo", "shoujo", source),
        MangaTag("Shounen", "shounen", source),
        MangaTag("Shounen Ai", "shounen-ai", source),
        MangaTag("Slice Of Life", "slice-of-life", source),
        MangaTag("Smut", "smut", source),
        MangaTag("Super Power", "super-power", source),
        MangaTag("Supernatural", "supernatural", source),
        MangaTag("Survival", "survival", source),
        MangaTag("Thriller", "thriller", source),
        MangaTag("Transmigration", "transmigration", source),
        MangaTag("Yaoi", "yaoi", source),
    )
}
