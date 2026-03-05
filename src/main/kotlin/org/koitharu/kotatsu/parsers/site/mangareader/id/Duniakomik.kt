package org.koitharu.kotatsu.parsers.site.mangareader.id

import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("DUNIAKOMIK", "DuniaKomik", "id", ContentType.HENTAI)
internal class Duniakomik(context: MangaLoaderContext) :
    MangaReaderParser(context, MangaParserSource.DUNIAKOMIK, "duniakomik.com", pageSize = 12, searchPageSize = 12) {
    
    override val datePattern = "MMMM d, yyyy"

    override val selectMangaList = ".listupd .bs"
    override val selectMangaListImg = "img"
    override val selectMangaListTitle = ".tt"
    override val selectChapter = ".clstyle ul li"
    override val selectPage = "#readerarea img"

    override val userAgentKey = org.koitharu.kotatsu.parsers.config.ConfigKey.UserAgent(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("Referer", "https://$domain/")
            .build()
        return chain.proceed(request)
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = if (!filter.query.isNullOrEmpty()) {
            "https://$domain/page/$page/?s=${filter.query.urlEncoded()}&post_type=komik_series"
        } else if (filter.tags.isNotEmpty()) {
            "https://$domain/genre/${filter.tags.first().key}/page/$page/"
        } else {
            "https://$domain/series/page/$page/"
        }
        
        return parseMangaList(webClient.httpGet(url).parseHtml())
    }
}
