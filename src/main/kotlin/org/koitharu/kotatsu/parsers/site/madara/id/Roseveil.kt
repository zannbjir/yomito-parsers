package org.koitharu.kotatsu.parsers.site.madara.id

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.util.Locale

@MangaSourceParser("ROSEVEIL", "Roseveil", "id", ContentType.HENTAI)
internal class Roseveil(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.ROSEVEIL, "roseveil.org") {

    override val sourceLocale = Locale.US
    override val datePattern = "MMMM dd, yyyy"
    override val withoutAjax = true
    override val listUrl = "comic/"

    // Header kuat + mobile
    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://roseveil.org/")
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
        .build()

    // Interceptor untuk bypass Cloudflare
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host.contains("roseveil")) {
            val newRequest = request.newBuilder()
                .addHeader("Referer", "https://roseveil.org/")
                .addHeader("Origin", "https://roseveil.org")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36")
                .addHeader("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                .build()
            return chain.proceed(newRequest)
        }
        return chain.proceed(request)
    }

    // Fix judul angka + "tidak ditemukan"
    override fun parseMangaList(doc: Document): List<Manga> {
        val items = doc.select("article, .manga, .comic-item, .post")
        if (items.isEmpty()) {
            return super.parseMangaList(doc)
        }

        return items.mapNotNull { item ->
            val link = item.selectFirst("h3 a, .post-title a, .manga-name a, a[href*='/comic/']") 
                ?: return@mapNotNull null

            val href = link.attrAsRelativeUrl("href")
            val title = link.text().trim().ifBlank {
                item.selectFirst("h3, .post-title, .manga-name, .title")?.text()?.trim()
            } ?: return@mapNotNull null

            val cover = item.selectFirst("img")?.src()

            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                coverUrl = cover,
                title = title,
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                source = source,
                contentRating = ContentRating.ADULT,
            )
        }
    }
}
