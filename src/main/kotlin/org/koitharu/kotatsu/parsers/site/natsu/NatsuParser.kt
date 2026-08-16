package org.koitharu.kotatsu.parsers.site.natsu

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.attrAsAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl
import org.koitharu.kotatsu.parsers.util.toTitleCase
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.EnumSet
import java.util.Locale

internal abstract class NatsuParser(
    context: MangaLoaderContext,
    source: MangaParserSource,
    pageSize: Int = 24,
) : PagedMangaParser(context, source, pageSize, pageSize) {

    override val sourceLocale: Locale = Locale.ENGLISH

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
        .add("Referer", "https://$domain/")
        .add("Origin", "https://$domain")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL,
        SortOrder.RATING,
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
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.PAUSED,
        ),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
            ContentType.COMICS,
            ContentType.NOVEL,
        ),
    )

    private var nonce: String? = null

    private suspend fun getNonce(): String {
        if (nonce == null) {
            val response = webClient.httpGet(
                "https://$domain/wp-admin/admin-ajax.php?type=search_form&action=get_nonce",
            )

            val html = response.parseHtml()
            nonce = html
                .selectFirst("input[name=search_nonce]")
                ?.attr("value")
                ?.takeIf { it.isNotBlank() }
        }

        return requireNotNull(nonce)
    }

    override suspend fun getListPage(
        page: Int,
        order: SortOrder,
        filter: MangaListFilter,
    ): List<Manga> {
        val url = "https://$domain/wp-admin/admin-ajax.php?action=advanced_search"

        val formParts = mutableMapOf<String, String>()
        formParts["nonce"] = getNonce()
        formParts["inclusion"] = "OR"

        formParts["genre"] = if (filter.tags.isNotEmpty()) {
            JSONArray(filter.tags.map { it.key }).toString()
        } else {
            "[]"
        }

        formParts["exclusion"] = "OR"

        formParts["genre_exclude"] = if (filter.tagsExclude.isNotEmpty()) {
            JSONArray(filter.tagsExclude.map { it.key }).toString()
        } else {
            "[]"
        }

        formParts["page"] = page.toString()

        formParts["author"] = if (!filter.author.isNullOrEmpty()) {
            JSONArray(filter.author).toString()
        } else {
            "[]"
        }

        formParts["artist"] = "[]"
        formParts["project"] = "0"

        formParts["type"] = if (filter.types.isNotEmpty()) {
            JSONArray().apply {
                filter.types.forEach { type ->
                    when (type) {
                        ContentType.MANGA -> put("manga")
                        ContentType.MANHWA -> put("manhwa")
                        ContentType.MANHUA -> put("manhua")
                        ContentType.COMICS -> put("comic")
                        ContentType.NOVEL -> put("novel")
                        else -> Unit
                    }
                }
            }.toString()
        } else {
            "[]"
        }

        formParts["status"] = if (filter.states.isNotEmpty()) {
            JSONArray().apply {
                filter.states.forEach { state ->
                    when (state) {
                        MangaState.ONGOING -> put("ongoing")
                        MangaState.FINISHED -> put("completed")
                        MangaState.PAUSED -> put("on-hiatus")
                        else -> Unit
                    }
                }
            }.toString()
        } else {
            "[]"
        }

        formParts["order"] = "desc"
        formParts["orderby"] = when (order) {
            SortOrder.UPDATED -> "updated"
            SortOrder.POPULARITY -> "popular"
            SortOrder.ALPHABETICAL -> "title"
            SortOrder.RATING -> "rating"
            else -> "popular"
        }

        if (!filter.query.isNullOrEmpty()) {
            formParts["query"] = filter.query
        }

        val extraHeaders = Headers.headersOf(
            "Referer", "https://$domain/advanced-search/",
            "Origin", "https://$domain",
            "X-Requested-With", "XMLHttpRequest",
            "Accept", "*/*",
        )

        val html = webClient
            .httpPost(url.toHttpUrl(), formParts, extraHeaders)
            .parseHtml()

        return parseMangaList(html)
    }

    protected open fun parseMangaList(doc: Document): List<Manga> {
        return doc.select("body > div").mapNotNull { element ->
            val mainLink = element
                .selectFirst("a[href*='/manga/']")
                ?: return@mapNotNull null

            val href = mainLink.attrAsRelativeUrl("href")

            if (href.contains("/chapter-")) {
                return@mapNotNull null
            }

            val title = element
                .selectFirst("a.text-base, a.text-white, h1")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: mainLink.attr("title").ifBlank { mainLink.text() }

            val coverUrl = element
                .selectFirst("img")
                ?.src()

            val rating = element
                .selectFirst(".numscore, span.text-yellow-400")
                ?.text()
                ?.toFloatOrNull()
                ?.let {
                    if (it > 5f) it / 10f else it / 5f
                }
                ?: RATING_UNKNOWN

            val stateText = element
                .selectFirst(
                    "span.bg-accent, p:contains(Ongoing), p:contains(Completed)",
                )
                ?.text()
                ?.lowercase()

            val state = when {
                stateText?.contains("ongoing") == true -> MangaState.ONGOING
                stateText?.contains("completed") == true -> MangaState.FINISHED
                stateText?.contains("hiatus") == true -> MangaState.PAUSED
                else -> null
            }

            Manga(
                id = generateUid(href),
                url = href,
                title = title,
                altTitles = emptySet(),
                publicUrl = mainLink.attrAsAbsoluteUrl("href"),
                rating = rating,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
                coverUrl = coverUrl,
                tags = emptySet(),
                state = state,
                authors = emptySet(),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient
            .httpGet(manga.url.toAbsoluteUrl(domain))
            .parseHtml()

        val mangaId = doc
            .selectFirst("[hx-get*='manga_id=']")
            ?.attr("hx-get")
            ?.substringAfter("manga_id=")
            ?.substringBefore("&")
            ?.trim()
            ?: doc
                .selectFirst("input#manga_id, [data-manga-id]")
                ?.let {
                    it.attr("value")
                        .ifEmpty { it.attr("data-manga-id") }
                }
            ?: manga.url
                .substringAfterLast("/manga/")
                .substringBefore("/")

        val titleElement = doc.selectFirst("h1[itemprop=name]")
        val title = titleElement?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: manga.title

        val altTitles = titleElement
            ?.nextElementSibling()
            ?.text()
            ?.split(',')
            ?.mapNotNull { it.trim().takeIf(String::isNotBlank) }
            ?.toSet()
            ?: emptySet()

        val description = doc
            .select("div[itemprop=description]")
            .joinToString("\n\n") { it.text() }
            .trim()
            .takeIf { it.isNotBlank() }

        val coverUrl = doc
            .selectFirst("div[itemprop=image] > img")
            ?.src()
            ?: manga.coverUrl

        val tags = doc
            .select("a[itemprop=genre]")
            .mapNotNullToSet { tag ->
                MangaTag(
                    key = tag.attr("href")
                        .substringAfterLast("/genre/")
                        .removeSuffix("/"),
                    title = tag.text().toTitleCase(),
                    source = source,
                )
            }

        fun findInfoText(key: String): String? {
            return doc
                .select("div.space-y-2 > .flex:has(h4)")
                .find {
                    it.selectFirst("h4")
                        ?.text()
                        ?.contains(key, ignoreCase = true) == true
                }
                ?.selectFirst("p.font-normal")
                ?.text()
        }

        val stateText = findInfoText("Status")?.lowercase()

        val state = when {
            stateText?.contains("ongoing") == true -> MangaState.ONGOING
            stateText?.contains("completed") == true -> MangaState.FINISHED
            stateText?.contains("hiatus") == true -> MangaState.PAUSED
            else -> manga.state
        }

        val authors = findInfoText("Author")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()

        val chapters = loadChapters(
            mangaId = mangaId,
            mangaAbsoluteUrl = manga.url.toAbsoluteUrl(domain),
        )

        return manga.copy(
            title = title,
            altTitles = altTitles,
            description = description,
            coverUrl = coverUrl,
            tags = tags,
            state = state,
            authors = authors,
            chapters = chapters,
        )
    }

    protected open val hxTrigger = "getChapterList"

    protected open suspend fun loadChapters(
        mangaId: String,
        mangaAbsoluteUrl: String,
    ): List<MangaChapter> {
        val headers = Headers.headersOf(
            "HX-Request", "true",
            "HX-Target", "chapter-list",
            "HX-Trigger", hxTrigger,
            "HX-Current-URL", mangaAbsoluteUrl,
            "Referer", mangaAbsoluteUrl,
        )

        val url = "https://$domain/wp-admin/admin-ajax.php" +
            "?manga_id=$mangaId&page=1&action=chapter_list"

        val doc = webClient
            .httpGet(url, headers)
            .parseHtml()

        return doc
            .select("div#chapter-list > div[data-chapter-number]")
            .mapNotNull { element ->
                val href = element
                    .selectFirst("a")
                    ?.attrAsRelativeUrl("href")
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                MangaChapter(
                    id = generateUid(href),
                    title = element
                        .selectFirst("div.font-medium span")
                        ?.text()
                        ?.trim()
                        ?: "",
                    url = href,
                    number = element
                        .attr("data-chapter-number")
                        .toFloatOrNull()
                        ?: -1f,
                    volume = 0,
                    scanlator = null,
                    uploadDate = parseDate(
                        element.selectFirst("time")?.text(),
                    ),
                    branch = null,
                    source = source,
                )
            }
            .reversed()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient
            .httpGet(chapter.url.toAbsoluteUrl(domain))
            .parseHtml()

        return doc
            .select("section[data-image-data] img, main section section > img")
            .mapNotNull { img ->
                val url = img
                    .src()
                    ?.takeIf { it.isNotBlank() }
                    ?.toRelativeUrl(domain)
                    ?: return@mapNotNull null

                MangaPage(
                    id = generateUid(url),
                    url = url,
                    preview = null,
                    source = source,
                )
            }
            .distinctBy { it.url }
    }

    protected open suspend fun fetchAvailableTags(): Set<MangaTag> {
        return try {
            val response = webClient.httpGet(
                "https://$domain/wp-json/wp/v2/genre" +
                    "?per_page=100&page=1&orderby=count&order=desc",
            )

            val jsonArray = JSONArray(
                response.body.use { it.string() },
            )

            buildSet {
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.optJSONObject(i) ?: continue
                    val slug = item
                        .optString("slug")
                        .takeIf { it.isNotBlank() }
                        ?: continue
                    val name = item
                        .optString("name")
                        .takeIf { it.isNotBlank() }
                        ?: continue

                    add(
                        MangaTag(
                            title = name.toTitleCase(),
                            key = slug,
                            source = source,
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            try {
                val doc = webClient
                    .httpGet("https://$domain/advanced-search/")
                    .parseHtml()

                val scriptContent = doc
                    .select("script")
                    .firstOrNull {
                        it.data().contains("var searchTerms")
                    }
                    ?.data()
                    ?: return emptySet()

                val jsonString = scriptContent
                    .substringAfter("var searchTerms =")
                    .substringBeforeLast(";")
                    .trim()

                val json = JSONObject(jsonString)
                val genreArray = json
                    .optJSONArray("genre")
                    ?: return emptySet()

                buildSet {
                    for (i in 0 until genreArray.length()) {
                        val item = genreArray.optJSONObject(i) ?: continue

                        if (item.optString("taxonomy") != "genre") {
                            continue
                        }

                        val slug = item
                            .optString("slug")
                            .takeIf { it.isNotBlank() }
                            ?: continue

                        val name = item
                            .optString("name")
                            .takeIf { it.isNotBlank() }
                            ?: continue

                        add(
                            MangaTag(
                                title = name.toTitleCase(),
                                key = slug,
                                source = source,
                            ),
                        )
                    }
                }
            } catch (_: Exception) {
                emptySet()
            }
        }
    }

    protected open fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrEmpty()) return 0L

        return try {
            when {
                dateStr.contains("ago", ignoreCase = true) -> {
                    val number = Regex("""(\d+)""")
                        .find(dateStr)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: return 0L

                    val calendar = Calendar.getInstance()

                    when {
                        dateStr.contains("min", ignoreCase = true) ->
                            calendar.apply {
                                add(Calendar.MINUTE, -number)
                            }

                        dateStr.contains("hour", ignoreCase = true) ->
                            calendar.apply {
                                add(Calendar.HOUR, -number)
                            }

                        dateStr.contains("day", ignoreCase = true) ->
                            calendar.apply {
                                add(Calendar.DAY_OF_MONTH, -number)
                            }

                        dateStr.contains("week", ignoreCase = true) ->
                            calendar.apply {
                                add(Calendar.WEEK_OF_YEAR, -number)
                            }

                        dateStr.contains("month", ignoreCase = true) ->
                            calendar.apply {
                                add(Calendar.MONTH, -number)
                            }

                        dateStr.contains("year", ignoreCase = true) ->
                            calendar.apply {
                                add(Calendar.YEAR, -number)
                            }

                        else -> calendar
                    }.timeInMillis
                }

                else -> {
                    SimpleDateFormat(
                        "MMM dd, yyyy",
                        sourceLocale,
                    ).parse(dateStr)?.time ?: 0L
                }
            }
        } catch (_: Exception) {
            0L
        }
    }
}