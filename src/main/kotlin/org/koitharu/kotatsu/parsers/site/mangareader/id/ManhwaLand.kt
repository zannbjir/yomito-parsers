package org.koitharu.kotatsu.parsers.site.mangareader.id

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser

@MangaSourceParser("MANHWALAND", "ManhwaLand.vip", "id", ContentType.HENTAI)
internal class ManhwaLand(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.MANHWALAND, "www.manhwaland.baby", pageSize = 20, searchPageSize = 10) {

    override val filterCapabilities: MangaListFilterCapabilities
        get() = super.filterCapabilities.copy(isTagsExclusionSupported = false)

    override val datePattern = "MMM d, yyyy"

    // Paksa semua gambar pakai HTTPS
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val pages = super.getPages(chapter)
        return pages.map { page ->
            page.copy(url = page.url.replace("http://", "https://"))
        }
    }

    // === INTERCEPTOR GACOR EKSTREM ===
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        if (url.contains("manhwaland")) {
            val builder = request.newBuilder()

            // Referer wajib dari domain utama
            builder.header("Referer", "https://www.manhwaland.baby/")

            // User-Agent desktop paling gacor
            builder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36")

            // Header lengkap seperti browser asli
            builder.header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            builder.header("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
            builder.header("Origin", "https://www.manhwaland.baby")
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
