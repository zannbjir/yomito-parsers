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

    // Paksa semua gambar http → https
    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val pages = super.getPages(chapter)
        return pages.map { page ->
            val fixedUrl = page.url.replace("http://", "https://")
            page.copy(url = fixedUrl)
        }
    }

    // Interceptor SUPER KUAT (ini yang paling penting)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        // Terapkan ke semua domain manhwaland (termasuk .baby, .email, dll)
        if (url.contains("manhwaland")) {
            val newRequest = request.newBuilder()
                // Referer paling penting
                .header("Referer", "https://www.manhwaland.baby/")
                // User-Agent desktop yang sangat umum
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36")
                // Header tambahan biar kelihatan seperti browser asli
                .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .header("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Origin", "https://www.manhwaland.baby")
                .header("Sec-Fetch-Site", "cross-site")
                .header("Sec-Fetch-Mode", "no-cors")
                .header("Sec-Fetch-Dest", "image")
                .build()

            return chain.proceed(newRequest)
        }
        return chain.proceed(request)
    }
}
