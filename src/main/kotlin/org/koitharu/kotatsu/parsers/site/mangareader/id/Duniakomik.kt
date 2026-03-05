package org.koitharu.kotatsu.parsers.site.mangareader.id

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser

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
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    )
    
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val request = chain.request().newBuilder()
            .header("Referer", "https://$domain/")
            .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .build()
        return chain.proceed(request)
    }

    override suspend fun getListPage(page: Int, order: org.koitharu.kotatsu.parsers.model.SortOrder, filter: org.koitharu.kotatsu.parsers.model.MangaListFilter): List<org.koitharu.kotatsu.parsers.model.Manga> {
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
