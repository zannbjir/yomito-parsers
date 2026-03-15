package org.koitharu.kotatsu.parsers.site.mangareader.id

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import java.util.Locale

@MangaSourceParser("BACAMAN", "Bacaman", "id")
internal class Bacaman(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.BACAMAN, "bacaman.id", pageSize = 20, searchPageSize = 20) {

    override val sourceLocale: Locale = Locale("id")
    
    override val datePattern = "MMMM d, yyyy"
    override val listUrl = "/manga"
    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
        .add("Referer", "https://$domain/")
        .build()
}
