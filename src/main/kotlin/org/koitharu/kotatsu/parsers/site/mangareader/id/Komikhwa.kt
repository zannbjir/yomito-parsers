package org.koitharu.kotatsu.parsers.site.mangareader.id

import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl

@MangaSourceParser("KOMIKHWA", "Komikhwa", "id")
internal class Komikhwa(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.KOMIKHWA, "komikhwa.com", pageSize = 20, searchPageSize = 10),
	Interceptor {

	override val datePattern = "MMMM d, yyyy"

	/**
	 * Chapter HTML renders an empty `#readerarea` (images loaded via JS).
	 * WP REST API has the rendered content with image URLs, so hit that directly.
	 */
	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(chapterUrl).parseHtml()
		val postId = doc.selectFirst("article[id^=post-]")?.id()?.removePrefix("post-")
			?: throw IllegalStateException("Cannot find post id in $chapterUrl")

		val json = webClient.httpGet("https://$domain/wp-json/wp/v2/posts/$postId").parseJson()
		val content = json.optJSONObject("content")?.optString("rendered").orEmpty()

		return IMG_SRC_REGEX.findAll(content)
			.map { it.groupValues[1] }
			.filter { it.isNotBlank() }
			.distinct()
			.map { url ->
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			}
			.toList()
	}

	/**
	 * CDN hosts (imageainewgeneration.lol, himmga.lat, gaimgame.pics) reject
	 * any Referer/Origin header — strip both for non-site hosts.
	 */
	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val host = request.url.host
		if (host == domain || host.endsWith(".komikhwa.com")) {
			return chain.proceed(request)
		}
		val rebuilt = request.newBuilder()
			.removeHeader("Origin")
			.removeHeader("Referer")
			.build()
		return chain.proceed(rebuilt)
	}

	companion object {
		private val IMG_SRC_REGEX = Regex("<img[^>]*src=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
	}
}
