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
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANHWAKU", "ManhwaKu", "id")
internal class Manhwaku(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.MANHWAKU, 20) {

    override val configKeyDomain = ConfigKey.Domain("manhwaku.biz.id")

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST)

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(isSearchSupported = true)

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = emptySet(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val isSearch = !filter.query.isNullOrEmpty()
        val url = if (isSearch) {
            "https://$domain/jelajahi?search=${filter.query.urlEncoded()}&page=${page + 1}"
        } else {
            "https://$domain/jelajahi?page=${page + 1}"
        }

        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
        
        val scriptData = doc.selectFirst("script:containsData(static/chunks/app/jelajahi/page)")?.data()
        val buildIdMatch = Regex(""""buildId":"([^"]+)"""").find(doc.html())
        val buildId = buildIdMatch?.groupValues?.get(1)
        val mangaList = mutableListOf<Manga>()

        if (buildId != null) {
            val dataUrl = if (isSearch) {
                "https://$domain/_next/data/$buildId/jelajahi.json?search=${filter.query.urlEncoded()}&page=${page + 1}"
            } else {
                "https://$domain/_next/data/$buildId/jelajahi.json?page=${page + 1}"
            }

            try {
                val jsonResponse = webClient.httpGet(dataUrl, getRequestHeaders()).parseJson()
                val pageProps = jsonResponse.optJSONObject("pageProps") ?: return emptyList()
                val dataArray = pageProps.optJSONArray("data") ?: pageProps.optJSONObject("data")?.optJSONArray("items") ?: return emptyList()

                for (i in 0 until dataArray.length()) {
                    val jo = dataArray.getJSONObject(i)
                    val slug = jo.optString("slug", "").ifEmpty { jo.optString("id", "") }
                    if (slug.isEmpty()) continue

                    val title = jo.optString("title", "Untitled")
                    val cover = jo.optString("cover", "").ifEmpty { jo.optString("image", "") }
                    val finalCover = if (cover.startsWith("http")) cover else "https://$domain$cover"

                    mangaList.add(Manga(
                        id = generateUid(slug),
                        title = title.trim(),
                        altTitles = emptySet(),
                        url = "/detail/$slug",
                        publicUrl = "https://$domain/detail/$slug",
                        rating = RATING_UNKNOWN,
                        contentRating = ContentRating.SAFE,
                        coverUrl = finalCover,
                        tags = emptySet(),
                        state = null,
                        authors = emptySet(),
                        source = source
                    ))
                }
                return mangaList
            } catch (e: Exception) {
            }
        }

        doc.select("a[href^='/detail/']").forEach { el ->
            val href = el.attr("href")
            val slug = href.substringAfter("/detail/")
            val title = el.select("h2, h3, p.font-bold, .text-white").firstOrNull()?.text() ?: return@forEach
            var img = el.selectFirst("img")?.attr("src") ?: ""
            if (img.startsWith("/_next/image")) {
                val urlParam = Regex("""url=([^&]+)""").find(img)?.groupValues?.get(1)
                if (urlParam != null) {
                    img = java.net.URLDecoder.decode(urlParam, "UTF-8")
                }
            } else if (img.startsWith("/")) {
                img = "https://$domain$img"
            }

            mangaList.add(Manga(
                id = generateUid(slug),
                title = title.trim(),
                altTitles = emptySet(),
                url = href,
                publicUrl = "https://$domain$href",
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.SAFE,
                coverUrl = img,
                largeCoverUrl = img,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source
            ))
        }

        return mangaList.distinctBy { it.url }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()
        val buildIdMatch = Regex(""""buildId":"([^"]+)"""").find(doc.html())
        val buildId = buildIdMatch?.groupValues?.get(1)

        val chapters = mutableListOf<MangaChapter>()
        var description = ""
        var state: MangaState? = null

        if (buildId != null) {
            val slug = manga.url.substringAfter("/detail/")
            val dataUrl = "https://$domain/_next/data/$buildId/detail/$slug.json"
            try {
                val jsonResponse = webClient.httpGet(dataUrl, getRequestHeaders()).parseJson()
                val pageProps = jsonResponse.optJSONObject("pageProps")
                val detailData = pageProps?.optJSONObject("data") ?: pageProps?.optJSONObject("manhwa")

                if (detailData != null) {
                    description = detailData.optString("description", detailData.optString("synopsis", ""))
                    val statusStr = detailData.optString("status", "")
                    state = if (statusStr.equals("ongoing", true)) MangaState.ONGOING else MangaState.FINISHED

                    val chaptersArray = detailData.optJSONArray("chapters") ?: JSONArray()
                    for (i in 0 until chaptersArray.length()) {
                        val ch = chaptersArray.getJSONObject(i)
                        val chSlug = ch.optString("slug", "")
                        val chTitle = ch.optString("title", ch.optString("name", ""))
                        val chNumStr = ch.optString("number", "")
                        
                        val number = chNumStr.toFloatOrNull() ?: chTitle.replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: 0f

                        if (chSlug.isNotEmpty()) {
                            val urlPath = "/read/$slug/$chSlug"
                            chapters.add(MangaChapter(
                                id = generateUid(urlPath),
                                title = chTitle.ifEmpty { "Chapter $number" },
                                url = urlPath,
                                number = number,
                                volume = 0,
                                scanlator = "",
                                uploadDate = 0L,
                                branch = null,
                                source = source
                            ))
                        }
                    }
                }
            } catch (e: Exception) {}
        }

        if (chapters.isEmpty()) {
            doc.select("a[href*='/read/']").forEach { el ->
                val href = el.attrAsRelativeUrl("href")
                val title = el.text().trim()
                
                val number = title.replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: 0f

                chapters.add(MangaChapter(
                    id = generateUid(href),
                    title = title,
                    url = href,
                    number = number,
                    volume = 0,
                    scanlator = "",
                    uploadDate = 0L,
                    branch = null,
                    source = source
                ))
            }
            description = doc.select("p.text-gray-400, .mt-4.text-sm").firstOrNull()?.text()?.trim() ?: ""
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

        val pages = mutableListOf<MangaPage>()

        if (buildId != null) {
            val segments = chapter.url.trim('/').split("/")
            if (segments.size >= 3) {
                val manhwaSlug = segments[1]
                val chapterSlug = segments[2]
                val dataUrl = "https://$domain/_next/data/$buildId/read/$manhwaSlug/$chapterSlug.json"
                try {
                    val jsonResponse = webClient.httpGet(dataUrl, getRequestHeaders()).parseJson()
                    val pageProps = jsonResponse.optJSONObject("pageProps")
                    val imagesArray = pageProps?.optJSONObject("data")?.optJSONArray("images") ?: pageProps?.optJSONArray("images") ?: JSONArray()
                    
                    for (i in 0 until imagesArray.length()) {
                        var imgUrl = imagesArray.getString(i)
                        if (imgUrl.startsWith("/")) imgUrl = "https://$domain$imgUrl"
                        pages.add(MangaPage(generateUid(imgUrl), imgUrl, null, source))
                    }
                    if (pages.isNotEmpty()) return pages
                } catch (e: Exception) {}
            }
        }

        doc.select("main img, .flex-col img").forEach { img ->
            var url = img.attr("data-src").ifEmpty { img.attr("src") }
            if (url.startsWith("/_next/image")) {
                val urlParam = Regex("""url=([^&]+)""").find(url)?.groupValues?.get(1)
                if (urlParam != null) {
                    url = java.net.URLDecoder.decode(urlParam, "UTF-8")
                }
            } else if (url.startsWith("/")) {
                url = "https://$domain$url"
            }

            if (url.isNotBlank() && !url.contains("logo") && !url.contains("icon") && url.length > 10) {
                pages.add(MangaPage(generateUid(url), url, null, source))
            }
        }

        return pages.distinctBy { it.url }
    }
}
