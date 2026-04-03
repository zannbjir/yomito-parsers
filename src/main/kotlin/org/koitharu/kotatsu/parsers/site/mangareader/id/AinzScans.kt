package org.koitharu.kotatsu.parsers.site.mangareader.id

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser

@MangaSourceParser("AINZSCANS", "AinzScans", "id")
internal class AinzScans(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.AINZSCANS, "v1.ainzscans01.com", pageSize = 20, searchPageSize = 10) {

    override val listUrl = "/comic"
    override val datePattern = "MMM d, yyyy"
    override val sourceLocale = Locale.ENGLISH

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36")
        .add("Referer", "https://v1.ainzscans01.com/")
        .build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host.contains("ainzscans")) {
            val newRequest = request.newBuilder()
                .addHeader("Referer", "https://v1.ainzscans01.com/")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36")
                .build()
            return chain.proceed(newRequest)
        }
        return chain.proceed(request)
    }
}
