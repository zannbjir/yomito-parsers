package org.koitharu.kotatsu.parsers.site.mangareader.id

import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.urlEncoded

@MangaSourceParser("MANHWALAND", "ManhwaLand.vip", "id", ContentType.HENTAI)
internal class ManhwaLand(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.MANHWALAND, "www.manhwaland.baby", pageSize = 20, searchPageSize = 10) {

    override val filterCapabilities: MangaListFilterCapabilities
        get() = super.filterCapabilities.copy(isTagsExclusionSupported = false)

    override val datePattern = "MMM d, yyyy"

    // The /manga/ archive on this domain is hard-blocked by Cloudflare while the homepage and
    // /page/N/ pagination are not. Override the list page builder so list/search work without
    // hitting the blocked archive.
    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)
            if (page > 1) {
                append("/page/")
                append(page)
                append("/")
            } else {
                append('/')
            }
            if (!filter.query.isNullOrEmpty()) {
                append("?s=")
                append(filter.query.urlEncoded())
            }
        }
        return parseMangaList(webClient.httpGet(url).parseHtml())
    }

    // Default MangaReaderParser fetches `/manga` to build the tag map during getDetails().
    // That URL is hard-blocked by Cloudflare on this domain. Skip it.
    override suspend fun getOrCreateTagMap(): Map<String, MangaTag> = emptyMap()

    // The `/manga/<slug>/` detail page is also Cloudflare-blocked (and even when CF clears,
    // the page sometimes returns the archive listing instead of the chapter list). Individual
    // chapter pages (`/<slug>-chapter-N/`) are NOT CF-blocked and the live AJAX search
    // endpoint also bypasses CF, so we use the search to recover the chapter count and
    // construct chapter URLs directly.
    override suspend fun getDetails(manga: Manga): Manga {
        val slug = manga.url.trim('/').removePrefix("manga/").trim('/')
        val query = slug.replace('-', ' ').take(40).urlEncoded()
        val ajaxUrl = "https://$domain/wp-admin/admin-ajax.php"
        val payload = "action=ts_ac_do_search&ts_ac_query=$query"
        val response = webClient.httpPost(ajaxUrl, payload).parseJson()

        var totalChapters = 0
        var entryTitle = manga.title
        val series = response.optJSONArray("series")
        outer@ for (i in 0 until (series?.length() ?: 0)) {
            val all = series!!.optJSONObject(i)?.optJSONArray("all") ?: continue
            for (j in 0 until all.length()) {
                val entry = all.getJSONObject(j)
                val link = entry.optString("post_link")
                if (link.contains("/manga/$slug/") || link.endsWith("/$slug/")) {
                    totalChapters = entry.optString("post_latest").toIntOrNull() ?: 0
                    entryTitle = entry.optString("post_title").ifBlank { entryTitle }
                    break@outer
                }
            }
        }

        val chapters = (1..totalChapters).map { num ->
            val urlPath = "/$slug-chapter-$num/"
            MangaChapter(
                id = generateUid(urlPath),
                title = "Chapter $num",
                url = urlPath,
                number = num.toFloat(),
                volume = 0,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source,
            )
        }

        return manga.copy(title = entryTitle, chapters = chapters)
    }

    // Paksa semua gambar pakai HTTPS
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val pages = super.getPages(chapter)
        return pages.map { page ->
            page.copy(url = page.url.replace("http://", "https://"))
        }
    }

    // Image hotlink protection: explicitly forge headers for image requests so they load.
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        if (url.contains("manhwaland")) {
            val builder = request.newBuilder()
            builder.header("Referer", "https://$domain/")
            builder.header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
            )
            builder.header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            builder.header("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
            builder.header("Origin", "https://$domain")
            builder.header("Sec-Fetch-Site", "cross-site")
            builder.header("Sec-Fetch-Mode", "no-cors")
            builder.header("Sec-Fetch-Dest", "image")
            builder.header("Sec-Ch-Ua", "\"Chromium\";v=\"134\", \"Not:A-Brand\";v=\"24\", \"Google Chrome\";v=\"134\"")
            builder.header("Sec-Ch-Ua-Mobile", "?0")
            builder.header("Sec-Ch-Ua-Platform", "\"Windows\"")
            builder.header("Cache-Control", "no-cache")
            return chain.proceed(builder.build())
        }
        return chain.proceed(request)
    }
}
