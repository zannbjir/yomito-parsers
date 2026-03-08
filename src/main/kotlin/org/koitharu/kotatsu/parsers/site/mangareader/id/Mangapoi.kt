package org.koitharu.kotatsu.parsers.site.mangareader.id

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser

@MangaSourceParser("MANGAPOI", "MangaPoi", "id", ContentType.HENTAI)
internal class Mangapoi(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.MANGAPOI, "mangapoi.my", pageSize = 20, searchPageSize = 10) {
    
    override val datePattern = "MMM d, yyyy"
    
    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "https://mangapoi.my/")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Accept-Encoding", "gzip, deflate, br")
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "none")
        .add("Cache-Control", "max-age=0")
        .build()
}
