package org.koitharu.kotatsu.parsers.site.mangareader.id

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*

@MangaSourceParser("MANHWADESU", "ManhwaDesu", "id")
internal class ManhwadesuParser(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.MANHWADESU, "manhwadesu.art", pageSize = 20, searchPageSize = 10) {
    
    override val listUrl = "/komik"
    
    override val isNetShieldProtected = true

    override val userAgentKey = org.koitharu.kotatsu.parsers.config.ConfigKey.UserAgent(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    )

    override val selectMangaList = ".listupd .bs .bsx"
    override val selectMangaListImg = "img"
    override val selectMangaListTitle = ".tt"
    override val selectChapter = "#chapterlist ul li"
    override val selectPage = "#readerarea img"

    override fun onCreateConfig(keys: MutableCollection<org.koitharu.kotatsu.parsers.config.ConfigKey<*>>) {
        super.onCreateConfig(keys)
    }

    private fun getSecurityHeaders(): Headers {
        return Headers.Builder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
            .add("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
            .add("Cache-Control", "max-age=0")
            .add("Sec-Ch-Ua", "\"Chromium\";v=\"122\", \"Not(A:Brand\";v=\"24\", \"Google Chrome\";v=\"122\"")
            .add("Sec-Ch-Ua-Mobile", "?0")
            .add("Sec-Ch-Ua-Platform", "\"Windows\"")
            .add("Sec-Fetch-Dest", "document")
            .add("Sec-Fetch-Mode", "navigate")
            .add("Sec-Fetch-Site", "none")
            .add("Sec-Fetch-User", "?1")
            .add("Upgrade-Insecure-Requests", "1")
            .build()
    }   
}
