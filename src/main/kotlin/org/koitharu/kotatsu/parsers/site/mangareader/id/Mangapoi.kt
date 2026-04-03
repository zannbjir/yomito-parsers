package org.koitharu.kotatsu.parsers.site.mangareader.id

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser

@MangaSourceParser("MANGAPOI", "MangaPoi", "id", ContentType.HENTAI)
internal class Mangapoi(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.MANGAPOI, "mangapoi.my", pageSize = 20, searchPageSize = 10) {

    override val datePattern = "MMM d, yyyy"

    // Header lebih lengkap + mobile-like
    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36")
        .add("Referer", "https://mangapoi.my/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
        .add("Accept-Encoding", "gzip, deflate, br")
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "none")
        .add("Cache-Control", "max-age=0")
        .build()

    // Interceptor agresif (ini yang biasanya bantu bypass Cloudflare)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host

        if (host.contains("mangapoi.my")) {
            val newRequest = request.newBuilder()
                .removeHeader("Referer")
                .addHeader("Referer", "https://mangapoi.my/")
                .addHeader("Origin", "https://mangapoi.my")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36")
                .addHeader("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                .build()
            return chain.proceed(newRequest)
        }
        return chain.proceed(request)
    }
}
