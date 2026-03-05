package org.koitharu.kotatsu.parsers.site.mangareader.id

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*

@MangaSourceParser("MANGAPOI", "Mangapoi", "id")
internal class MangapoiParser(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.MANGAPOI, "mangapoi.my", pageSize = 20, searchPageSize = 10) {
    
    override val listUrl = "/manga"
    override val isNetShieldProtected = true
    
    override val userAgentKey = org.koitharu.kotatsu.parsers.config.ConfigKey.UserAgent(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    )
    
    override val selectMangaList = ".listupd .bs .bsx"
    override val selectMangaListImg = "img"
    override val selectMangaListTitle = ".tt"
    override val selectChapter = "#chapterlist ul li"
    override val selectPage = "#readerarea img"
    
    override fun getRequestHeaders(): Headers {
        return Headers.Builder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .add("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8")
            .add("Sec-Ch-Ua", "\"Chromium\";v=\"131\", \"Not(A:Brand\";v=\"24\"")
            .add("Sec-Ch-Ua-Mobile", "?0")
            .add("Sec-Ch-Ua-Platform", "\"Windows\"")
            .add("Sec-Fetch-Dest", "document")
            .add("Sec-Fetch-Mode", "navigate")
            .add("Sec-Fetch-Site", "none")
            .add("Sec-Fetch-User", "?1")
            .build()
    }
}
