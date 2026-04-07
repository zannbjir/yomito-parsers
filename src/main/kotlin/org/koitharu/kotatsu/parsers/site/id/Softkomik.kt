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

@MangaSourceParser("SOFTKOMIK", "Softkomik", "id")
internal class Softkomik(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.SOFTKOMIK, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("softkomik.co")

    private val apiUrl = "https://v2.softdevices.my.id"
    private val coverCdn = "https://cover.softdevices.my.id/softkomik-cover"

    private val cdnUrls = listOf(
        "https://cd1.softkomik.online/softkomik",
        "https://f1.softkomik.com/file/softkomik-image",
        "https://img.softdevices.my.id/softkomik-image",
        "https://image.softkomik.com/softkomik"
    )

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST, SortOrder.POPULARITY)

    override val filterCapabilities = MangaListFilterCapabilities(isSearchSupported = true)

    private val baseHeaders = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36")
        .add("Referer", "https://softkomik.co/")
        .add("Origin", "https://softkomik.co")
        .build()

    private var session: SessionDto? = null

    private suspend fun ensureSession() {
        if (session != null && session!!.ex > System.currentTimeMillis()) return
        try {
            webClient.httpGet("https://softkomik.co/", baseHeaders)
            val res = webClient.httpGet("https://softkomik.co/api/sessions", baseHeaders).body?.string()
            if (!res.isNullOrEmpty()) {
                val json = JSONObject(res)
                session = SessionDto(
                    ex = json.optLong("ex"),
                    token = json.opt("token")?.toString() ?: "",
                    sign = json.opt("sign")?.toString() ?: ""
                )
            }
        } catch (_: Exception) {}
    }

    override suspend fun getFilterOptions() = MangaListFilterOptions()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        ensureSession()

        val url = if (!filter.query.isNullOrEmpty()) {
            "https://softkomik.co/search?q=${filter.query.urlEncoded()}&page=${page + 1}"
        } else {
            val sort = if (order == SortOrder.POPULARITY) "popular" else "newKomik"
            "https://softkomik.co/komik/library?page=${page + 1}&sortBy=$sort"
        }

        val doc = webClient.httpGet(url, baseHeaders).parseHtml()
        val script = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return emptyList()

        val json = try { JSONObject(script) } catch (e: Exception) { return emptyList() }
        val pageProps = json.optJSONObject("props")?.optJSONObject("pageProps") ?: return emptyList()

        val mangaArray = pageProps.optJSONArray("res")
            ?: pageProps.optJSONArray("data")
            ?: pageProps.optJSONArray("terbaru")
            ?: return emptyList()

        return mangaArray.mapNotNull { jo ->
            val slug = jo.opt("title_slug")?.toString() ?: jo.opt("id")?.toString() ?: return@mapNotNull null
            if (slug.isEmpty()) return@mapNotNull null

            val title = jo.opt("title")?.toString() ?: "Untitled"
            var cover = jo.opt("gambar")?.toString() ?: ""
            if (cover.startsWith("/")) cover = "$coverCdn/$cover"

            Manga(
                id = generateUid(slug),
                title = title.trim(),
                altTitles = emptySet(),
                url = "/komik/$slug",
                publicUrl = "https://softkomik.co/komik/$slug",
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.SAFE,
                coverUrl = cover,
                tags = emptySet(),
                state = if ((jo.opt("status")?.toString() ?: "").contains("ongoing", ignoreCase = true)) MangaState.ONGOING else MangaState.FINISHED,
                authors = emptySet(),
                source = source
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        ensureSession()

        val doc = webClient.httpGet(manga.publicUrl, baseHeaders).parseHtml()
        val script = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return manga

        val json = try { JSONObject(script) } catch (e: Exception) { return manga }
        val pageProps = json.optJSONObject("props")?.optJSONObject("pageProps") ?: return manga
        val detail = pageProps.optJSONObject("manga") ?: pageProps.optJSONObject("res") ?: pageProps

        val chaptersArray = pageProps.optJSONArray("chapterlist") ?: pageProps.optJSONArray("chapters") ?: JSONArray()

        val tags = mutableSetOf<MangaTag>()
        detail.optJSONArray("Genre")?.let { arr ->
            for (i in 0 until arr.length()) {
                val name = arr.opt(i)?.toString()?.trim() ?: ""
                if (name.isNotEmpty()) tags.add(MangaTag(name, name, source))
            }
        }

        val chapters = chaptersArray.mapNotNull { ch ->
            val chStr = ch.opt("chapter")?.toString() ?: "0"
            val number = chStr.toFloatOrNull() ?: 0f
            MangaChapter(
                id = generateUid("${manga.url}-$chStr"),
                title = "Chapter $chStr",
                url = "${manga.url}/chapter/$chStr",
                number = number,
                volume = 0,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source
            )
        }.sortedByDescending { it.number }

        return manga.copy(
            description = detail.opt("sinopsis")?.toString() ?: "",
            tags = tags,
            authors = setOfNotNull(detail.opt("author")?.toString()?.takeIf { it.isNotEmpty() }),
            state = if ((detail.opt("status")?.toString() ?: "").contains("ongoing", ignoreCase = true)) MangaState.ONGOING else MangaState.FINISHED,
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        ensureSession()

        val rscHeaders = baseHeaders.newBuilder().add("rsc", "1").build()
        val doc = webClient.httpGet("https://softkomik.co${chapter.url}", rscHeaders).parseHtml()

        val script = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return emptyList()
        val json = try { JSONObject(script) } catch (e: Exception) { return emptyList() }

        val pageProps = json.optJSONObject("props")?.optJSONObject("pageProps") ?: return emptyList()
        val data = pageProps.optJSONObject("data") ?: pageProps

        var images = data.optJSONArray("imageSrc") ?: JSONArray()

        if (images.length() == 0) {
            val id = data.opt("_id")?.toString() ?: ""
            val segments = chapter.url.split("/")
            if (segments.size >= 4) {
                val slug = segments[2]
                val chNum = segments.last()
                val imgApi = "$apiUrl/komik/$slug/chapter/$chNum/img/$id"

                try {
                    val sessionRes = webClient.httpGet("https://softkomik.co/api/sessions", baseHeaders).body?.string()
                    val sessionJson = JSONObject(sessionRes ?: "{}")
                    val token = sessionJson.opt("token")?.toString() ?: ""
                    val sign = sessionJson.opt("sign")?.toString() ?: ""

                    val apiHeaders = baseHeaders.newBuilder()
                        .add("X-Token", token)
                        .add("X-Sign", sign)
                        .add("Accept", "application/json")
                        .build()

                    val imgRes = webClient.httpGet(imgApi, apiHeaders).body?.string()
                    images = JSONObject(imgRes ?: "{}").optJSONArray("imageSrc") ?: JSONArray()
                } catch (_: Exception) {}
            }
        }

        val isInter2 = data.optBoolean("storageInter2", false)
        val host = if (isInter2) cdnUrls[2] else cdnUrls[0]

        return (0 until images.length()).mapNotNull { i ->
            val path = images.opt(i)?.toString()?.removePrefix("/") ?: ""
            if (path.isNotEmpty()) MangaPage(generateUid(path), "$host/$path", null, source) else null
        }
    }
}

// DTO Session
private data class SessionDto(
    val ex: Long,
    val token: String,
    val sign: String
)
