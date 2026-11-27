package org.koitharu.kotatsu.parsers.site.mangareader.id

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
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import java.util.Locale

@MangaSourceParser("KOMIKPOI", "KomikPoi", "id", ContentType.HENTAI)
internal class KomikPoi(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.KOMIKPOI, "komikpoi.com", pageSize = 20, searchPageSize = 10) {

	override val sourceLocale: Locale = Locale.ENGLISH

	@Volatile
	private var chapterReferer: String? = null

	override val filterCapabilities: MangaListFilterCapabilities
		get() = super.filterCapabilities.copy(
			isTagsExclusionSupported = false,
		)

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		chapterReferer = chapter.url.toAbsoluteUrl(domain)
		return super.getPages(chapter).map { page ->
			if (page.url.contains(DEAD_CDN_HOST)) {
				page.copy(url = page.url.replace(DEAD_CDN_HOST, LIVE_CDN_HOST))
			} else {
				page
			}
		}
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val referer = when (request.url.host) {
			"cdn.linksaya.com" -> chapterReferer ?: "https://$domain/"
			LIVE_CDN_HOST -> LIVE_CDN_REFERER
			else -> return super.intercept(chain)
		}
		val newRequest = request.newBuilder()
			.header("Referer", referer)
			.header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
			.header("Sec-Fetch-Dest", "image")
			.header("Sec-Fetch-Mode", "no-cors")
			.header("Sec-Fetch-Site", "cross-site")
			.build()
		return chain.proceed(newRequest)
	}

	private companion object {
		private const val DEAD_CDN_HOST = "dgi2jrxl1sxrf7quats6.online"
		private const val LIVE_CDN_HOST = "dgi2jrxl1sxrf7quats6.cfd"
		private const val LIVE_CDN_REFERER = "https://doujindesuid.cfd/"
	}
}
