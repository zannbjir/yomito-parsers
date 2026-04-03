package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("MANHWAKU", "ManhwaKu", "id")
internal class Manhwaku(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANHWAKU, 20) {

    override val configKeyDomain = ConfigKey.Domain("manhwaku.biz.id")

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST)

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(isSearchSupported = true)

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
    )

    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        val doc = webClient.httpGet("https://$domain/jelajahi", getRequestHeaders()).parseHtml()
        return doc.select("button.px-3.py-1\\.5").mapNotNull { btn ->
            val title = btn.text().trim()
            if (title.isNotEmpty()) MangaTag(title, title, source) else null
        }.toSet()
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val isSearch = !filter.query.isNullOrEmpty()
        val url = if (isSearch) {
            "https://$domain/jelajahi?search=${filter.query.urlEncoded()}&page=${page + 1}"
        } else {
            "https://$domain/jelajahi?page=${page + 1}"
        }

        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()

        val buildIdMatch = Regex(""""buildId":"([^"]+)"""").find(doc.html())
        val buildId = buildIdMatch?.groupValues?.get(1)

        if (buildId != null) {
            val dataUrl = if (isSearch) {
                "https://$domain/_next/data/$buildId/jelajahi.json?search=${filter.query.urlEncoded()}&page=${page + 1}"
            } else {
                "https://$domain/_next/data/$buildId/jelajahi.json?page=${page + 1}"
            }

            try {
                val jsonResponse = webClient.httpGet(dataUrl, getRequestHeaders()).parseJson()
                val pageProps = jsonResponse.optJSONObject("pageProps") ?: return emptyList()
                val dataArray = pageProps.optJSONArray("data")
                    ?: pageProps.optJSONObject("data")?.optJSONArray("items")
                    ?: return emptyList()

                return dataArray.mapNotNull { jo ->
                    val slug = (jo.opt("slug")?.toString() ?: jo.opt("id")?.toString()) ?: return@mapNotNull null
                    if (slug.isEmpty()) return@mapNotNull null

                    val title = jo.opt("title")?.toString() ?: "Untitled"
                    var cover = jo.opt("cover")?.toString() ?: jo.opt("image")?.toString() ?: ""
                    if (cover.startsWith("/")) cover = "https://$domain$cover"

                    Manga(
                        id = generateUid(slug),
                        title = title.trim(),
                        altTitles = emptySet(),
                        url = "/detail/$slug",
                        publicUrl = "https://$domain/detail/$slug",
                        rating = RATING_UNKNOWN,
                        contentRating = ContentRating.SAFE,
                        coverUrl = cover,
                        tags = emptySet(),
                        state = null,
                        authors = emptySet(),
                        source = source
                    )
                }
            } catch (_: Exception) {}
        }

        // Fallback HTML
        val mangaList = mutableListOf<Manga>()
        doc.select("a[href^='/detail/']").forEach { el ->
            val href = el.attr("href")
            val slug = href.substringAfter("/detail/")
            if (slug.isEmpty()) return@forEach

            val title = el.select("h2, h3, .font-bold, .text-white").firstOrNull()?.text()?.trim() ?: return@forEach
            var cover = el.selectFirst("img")?.attr("src") ?: ""

            if (cover.startsWith("/_next/image")) {
                val urlParam = Regex("""url=([^&]+)""").find(cover)?.groupValues?.get(1)
                cover = urlParam?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: cover
            } else if (cover.startsWith("/")) {
                cover = "https://$domain$cover"
            }

            mangaList.add(
                Manga(
                    id = generateUid(slug),
                    title = title,
                    altTitles = emptySet(),
                    url = href,
                    publicUrl = "https://$domain$href",
                    rating = RATING_UNKNOWN,
                    contentRating = ContentRating.SAFE,
                    coverUrl = cover,
                    tags = emptySet(),
                    state = null,
                    authors = emptySet(),
                    source = source
                )
            )
        }
        return mangaList.distinctBy { it.url }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()

        val buildIdMatch = Regex(""""buildId":"([^"]+)"""").find(doc.html())
        val buildId = buildIdMatch?.groupValues?.get(1)

        var description = ""
        var state: MangaState? = null
        val chapters = mutableListOf<MangaChapter>()

        if (buildId != null) {
            val slug = manga.url.substringAfter("/detail/")
            val dataUrl = "https://$domain/_next/data/$buildId/detail/$slug.json"

            try {
                val jsonResponse = webClient.httpGet(dataUrl, getRequestHeaders()).parseJson()
                val pageProps = jsonResponse.optJSONObject("pageProps")
                val detailData = pageProps?.optJSONObject("data") ?: pageProps?.optJSONObject("manhwa")

                if (detailData != null) {
                    description = detailData.opt("description")?.toString() ?: detailData.opt("synopsis")?.toString() ?: ""
                    val status = detailData.opt("status")?.toString() ?: ""
                    state = if (status.equals("ongoing", ignoreCase = true)) MangaState.ONGOING else MangaState.FINISHED

                    val chaptersArray = detailData.optJSONArray("chapters") ?: JSONArray()
                    for (i in 0 until chaptersArray.length()) {
                        val ch = chaptersArray.getJSONObject(i)
                        val chSlug = ch.opt("slug")?.toString() ?: ""
                        val chTitle = ch.opt("title")?.toString() ?: ch.opt("name")?.toString() ?: ""
                        val numberStr = ch.opt("number")?.toString() ?: ch.opt("chapter")?.toString() ?: "0"
                        val number = numberStr.toFloatOrNull() ?: 0f

                        if (chSlug.isNotEmpty()) {
                            val urlPath = "/read/$slug/$chSlug"
                            chapters.add(
                                MangaChapter(
                                    id = generateUid(urlPath),
                                    title = chTitle.ifEmpty { "Chapter $number" },
                                    url = urlPath,
                                    number = number,
                                    volume = 0,
                                    scanlator = null,
                                    uploadDate = 0L,
                                    branch = null,
                                    source = source
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return manga.copy(
            description = description.ifEmpty { manga.description },
            state = state ?: manga.state,
            chapters = chapters.distinctBy { it.url }.sortedBy { it.number }
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain), getRequestHeaders()).parseHtml()

        val buildIdMatch = Regex(""""buildId":"([^"]+)"""").find(doc.html())
        val buildId = buildIdMatch?.groupValues?.get(1)

        if (buildId != null) {
            val segments = chapter.url.trim('/').split("/")
            if (segments.size >= 3) {
                val manhwaSlug = segments[1]
                val chapterSlug = segments[2]
                val dataUrl = "https://$domain/_next/data/$buildId/read/$manhwaSlug/$chapterSlug.json"

                try {
                    val jsonResponse = webClient.httpGet(dataUrl, getRequestHeaders()).parseJson()
                    val pageProps = jsonResponse.optJSONObject("pageProps")
                    val imagesArray = pageProps?.optJSONObject("data")?.optJSONArray("images")
                        ?: pageProps?.optJSONArray("images")
                        ?: JSONArray()

                    return (0 until imagesArray.length()).mapNotNull { i ->
                        val imgUrl = imagesArray.optString(i, "")
                        if (imgUrl.isNotBlank()) {
                            val finalUrl = if (imgUrl.startsWith("/")) "https://$domain$imgUrl" else imgUrl
                            MangaPage(generateUid(finalUrl), finalUrl, null, source)
                        } else null
                    }
                } catch (_: Exception) {}
            }
        }

        // Fallback HTML
        val pages = mutableListOf<MangaPage>()
        doc.select("main img, .flex-col img, img[src*='storage'], img[data-src]").forEach { img ->
            var url = img.attr("data-src").ifEmpty { img.attr("src") }
            if (url.startsWith("/")) url = "https://$domain$url"
            if (url.isNotBlank() && url.length > 15) {
                pages.add(MangaPage(generateUid(url), url, null, source))
            }
        }
        return pages.distinctBy { it.url }
    }
}
