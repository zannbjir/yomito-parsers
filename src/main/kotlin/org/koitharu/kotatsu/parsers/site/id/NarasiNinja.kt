package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.util.*

@MangaSourceParser("NARASININJA", "NarasiNinja", "id")
internal class NarasiNinjaParser(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.NARASININJA, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("narasininja.net")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = true,
            isTagsExclusionSupported = false,
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = buildGenreTagSet(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
        ),
    )

    // ── CSRF ─────────────────────────────────────────────────────────────────

    private suspend fun getCsrfToken(): String {
        val doc = webClient.httpGet("https://$domain/komik").parseHtml()
        return doc.selectFirst("meta[name=csrf-token]")?.attr("content")
            ?: error("CSRF token not found")
    }

    // ── FILTER POST ───────────────────────────────────────────────────────────

    private suspend fun fetchFilterPage(
        page: Int,
        query: String,
        order: String,
        status: String,
        type: String,
        genres: List<String>,
    ): org.jsoup.nodes.Document {
        val csrf = getCsrfToken()
        val url = "https://$domain/komik/filter?page=$page"

        // Build form body sebagai string (application/x-www-form-urlencoded)
        val bodyParts = mutableListOf(
            "_token=${csrf.urlEncoded()}",
            "search=${query.urlEncoded()}",
            "status=${status.urlEncoded()}",
            "type=${type.urlEncoded()}",
            "order=${order.urlEncoded()}",
        )
        genres.forEach { bodyParts.add("genre[]=${it.urlEncoded()}") }

        val body = bodyParts.joinToString("&")

        val headers = Headers.Builder()
            .add("X-CSRF-TOKEN", csrf)
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Content-Type", "application/x-www-form-urlencoded")
            .add("Referer", "https://$domain/komik")
            .build()

        return webClient.httpPost(url.toHttpUrl(), body, headers).parseHtml()
    }

    // ── LIST PAGE ─────────────────────────────────────────────────────────────

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val orderStr = when (order) {
            SortOrder.ALPHABETICAL -> "title"
            SortOrder.NEWEST -> "added"
            SortOrder.POPULARITY -> "default"
            SortOrder.UPDATED -> "update"
            else -> "update"
        }

        val statusStr = when (filter.states.oneOrThrowIfMany()) {
            MangaState.ONGOING -> "ongoing"
            MangaState.FINISHED -> "completed"
            MangaState.PAUSED -> "hiatus"
            else -> ""
        }

        val typeStr = when (filter.types.oneOrThrowIfMany()) {
            ContentType.MANGA -> "manga"
            ContentType.MANHWA -> "manhwa"
            ContentType.MANHUA -> "manhua"
            else -> ""
        }

        val genreIds = filter.tags.map { it.key }

        val doc = fetchFilterPage(
            page = page,
            query = filter.query.orEmpty(),
            order = orderStr,
            status = statusStr,
            type = typeStr,
            genres = genreIds,
        )

        // Response dari filter API adalah JSON di dalam HTML (atau JSON langsung)
        // Coba parse sebagai JSON response terlebih dahulu
        val bodyText = doc.body().text()
        return try {
            val json = JSONObject(bodyText)
            val data = json.optJSONArray("data") ?: return emptyList()
            data.mapJSON { jo ->
                val slug = jo.getString("slug")
                val coverUrl = "https://$domain/storage/comic/image-bg/$slug.jpg"
                Manga(
                    id = generateUid(slug),
                    title = jo.getString("title"),
                    altTitles = emptySet(),
                    url = "/komik/$slug",
                    publicUrl = "https://$domain/komik/$slug",
                    rating = RATING_UNKNOWN,
                    contentRating = null,
                    coverUrl = coverUrl,
                    tags = emptySet(),
                    state = parseStatus(jo.optJSONObject("detail")?.optString("status")),
                    authors = emptySet(),
                    source = source,
                )
            }
        } catch (_: Exception) {
            // Fallback: parse sebagai HTML biasa
            doc.select(".listupd .bs .bsx, .bs .bsx").mapNotNull { el ->
                val a = el.selectFirst("a") ?: return@mapNotNull null
                val href = a.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
                val title = a.attr("title").ifBlank {
                    el.selectFirst(".tt, .title")?.text() ?: return@mapNotNull null
                }
                val slug = href.removePrefix("/komik/").removeSuffix("/")
                val coverUrl = "https://$domain/storage/comic/image-bg/$slug.jpg"
                Manga(
                    id = generateUid(href),
                    title = title,
                    altTitles = emptySet(),
                    url = href,
                    publicUrl = href.toAbsoluteUrl(domain),
                    rating = RATING_UNKNOWN,
                    contentRating = null,
                    coverUrl = coverUrl,
                    tags = emptySet(),
                    state = null,
                    authors = emptySet(),
                    source = source,
                )
            }
        }
    }

    // ── DETAILS ───────────────────────────────────────────────────────────────

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl.toAbsoluteUrl(domain)).parseHtml()

        val title = doc.selectFirst("h1.entry-title")?.text()?.trim() ?: manga.title

        val coverUrl = doc.selectFirst(".thumb img")?.let { img ->
            img.absUrl("src").ifBlank { img.absUrl("data-src") }
        } ?: manga.coverUrl

        val description = doc.selectFirst(".entry-content.entry-content-single p")?.text()?.trim()

        val statusText = doc.selectFirst(".infotable tr:contains(Status) td:last-child")?.text()
        val state = parseStatus(statusText)

        val author = doc.selectFirst(".infotable tr:contains(Author) td:last-child")
            ?.text()?.takeUnless { it.isBlank() || it == "-" }

        val artist = doc.selectFirst(".infotable tr:contains(Artist) td:last-child")
            ?.text()?.takeUnless { it.isBlank() || it == "-" }

        val tags = doc.select(".seriestugenre a").mapNotNullToSet { a ->
            val text = a.text().trim()
            if (text.isBlank()) return@mapNotNullToSet null
            MangaTag(
                title = text,
                key = text.lowercase(),
                source = source,
            )
        }

        // Chapter list dari HTML
        val chapterNumberRegex = Regex("""(\d+)(?:[._-](\d+))?""")
        val chapters = doc.select("#chapterlist li, .eplister li").mapChapters(reversed = false) { _, li ->
            val a = li.selectFirst("a") ?: return@mapChapters null
            val href = a.attrAsRelativeUrlOrNull("href") ?: return@mapChapters null
            val chTitle = li.selectFirst(".chapternum")?.text() ?: a.text()
            val dateText = li.selectFirst(".chapterdate")?.text()
            val uploadDate = parseChapterDate(dateText)

            val dataNum = li.attr("data-num").ifEmpty {
                chTitle.substringAfterLast(" ")
            }
            val match = chapterNumberRegex.find(dataNum.trim())
            val major = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val minor = match?.groupValues?.get(2)?.toIntOrNull()
            val number = if (minor != null) "$major.$minor".toFloat() else major.toFloat()

            MangaChapter(
                id = generateUid(href),
                title = chTitle,
                url = href,
                number = number,
                volume = 0,
                scanlator = null,
                uploadDate = uploadDate,
                branch = null,
                source = source,
            )
        }

        return manga.copy(
            title = title,
            coverUrl = coverUrl,
            description = description,
            tags = tags,
            state = state,
            authors = setOfNotNull(author, artist?.takeIf { it != author }),
            chapters = chapters,
        )
    }

    // ── PAGES ─────────────────────────────────────────────────────────────────

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
        return doc.select("#readerarea img.ts-main-image").mapNotNull { img ->
            val url = img.absUrl("src").ifBlank { img.absUrl("data-src") }
            if (url.isBlank() || url.startsWith("data:")) return@mapNotNull null
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    private fun parseStatus(text: String?): MangaState? = when {
        text == null -> null
        text.contains("ongoing", ignoreCase = true) -> MangaState.ONGOING
        text.contains("completed", ignoreCase = true) ||
            text.contains("finished", ignoreCase = true) ||
            text.contains("tamat", ignoreCase = true) -> MangaState.FINISHED
        text.contains("hiatus", ignoreCase = true) ||
            text.contains("on hold", ignoreCase = true) -> MangaState.PAUSED
        else -> null
    }

    private fun parseChapterDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        return try {
            java.text.SimpleDateFormat("MMMM dd, yyyy", Locale.US).parse(text.trim())?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun buildGenreTagSet(): Set<MangaTag> = linkedSetOf(
        MangaTag("4-Koma", "123", source),
        MangaTag("Action", "6", source),
        MangaTag("Adaptation", "48", source),
        MangaTag("Adult", "43", source),
        MangaTag("Adventure", "3", source),
        MangaTag("Animals", "84", source),
        MangaTag("Anthology", "144", source),
        MangaTag("Antihero", "90", source),
        MangaTag("Apocalypse", "56", source),
        MangaTag("Award Winning", "129", source),
        MangaTag("Beasts", "68", source),
        MangaTag("Bodyswap", "103", source),
        MangaTag("Boys' Love", "124", source),
        MangaTag("Bully", "135", source),
        MangaTag("Comedy", "12", source),
        MangaTag("Comic", "53", source),
        MangaTag("Cooking", "38", source),
        MangaTag("Crime", "31", source),
        MangaTag("Crossdressing", "125", source),
        MangaTag("Dark Fantasy", "60", source),
        MangaTag("Delinquents", "74", source),
        MangaTag("Demon", "65", source),
        MangaTag("Demons", "52", source),
        MangaTag("Doujinshi", "70", source),
        MangaTag("Drama", "1", source),
        MangaTag("Dungeons", "106", source),
        MangaTag("Ecchi", "15", source),
        MangaTag("Fantasy", "4", source),
        MangaTag("Full Color", "49", source),
        MangaTag("Game", "47", source),
        MangaTag("Gender Bender", "25", source),
        MangaTag("Genderswap", "40", source),
        MangaTag("Ghosts", "99", source),
        MangaTag("Girls' Love", "122", source),
        MangaTag("Gore", "36", source),
        MangaTag("Harem", "7", source),
        MangaTag("Historical", "13", source),
        MangaTag("Horror", "17", source),
        MangaTag("Incest", "93", source),
        MangaTag("Isekai", "8", source),
        MangaTag("Josei", "39", source),
        MangaTag("Leveling", "151", source),
        MangaTag("Long Strip", "50", source),
        MangaTag("Mafia", "130", source),
        MangaTag("Magic", "30", source),
        MangaTag("Martial Arts", "14", source),
        MangaTag("Mature", "9", source),
        MangaTag("Mecha", "37", source),
        MangaTag("Medical", "32", source),
        MangaTag("Military", "73", source),
        MangaTag("Monster Girls", "138", source),
        MangaTag("Monsters", "63", source),
        MangaTag("Murim", "29", source),
        MangaTag("Music", "92", source),
        MangaTag("Mystery", "22", source),
        MangaTag("Ninja", "109", source),
        MangaTag("Office Workers", "23", source),
        MangaTag("One-Shot", "61", source),
        MangaTag("Overpowered", "148", source),
        MangaTag("Psychological", "27", source),
        MangaTag("Regression", "34", source),
        MangaTag("Reincarnation", "33", source),
        MangaTag("Revenge", "82", source),
        MangaTag("Reverse Harem", "128", source),
        MangaTag("Romance", "10", source),
        MangaTag("Royalty", "114", source),
        MangaTag("School Life", "2", source),
        MangaTag("Sci-fi", "26", source),
        MangaTag("Seinen", "5", source),
        MangaTag("Shoujo", "21", source),
        MangaTag("Shoujo Ai", "45", source),
        MangaTag("Shounen", "11", source),
        MangaTag("Shounen Ai", "64", source),
        MangaTag("Slice of Life", "16", source),
        MangaTag("Smut", "71", source),
        MangaTag("Sports", "19", source),
        MangaTag("Super Power", "18", source),
        MangaTag("Supernatural", "20", source),
        MangaTag("Survival", "42", source),
        MangaTag("System", "57", source),
        MangaTag("Thriller", "62", source),
        MangaTag("Time Travel", "98", source),
        MangaTag("Tragedy", "28", source),
        MangaTag("Transmigration", "102", source),
        MangaTag("Vampire", "67", source),
        MangaTag("Villainess", "79", source),
        MangaTag("Webtoon", "86", source),
        MangaTag("Wuxia", "55", source),
        MangaTag("Xianxia", "89", source),
        MangaTag("Xuanhuan", "80", source),
        MangaTag("Yaoi", "141", source),
        MangaTag("Yuri", "46", source),
        MangaTag("Zombies", "140", source),
    )
}
