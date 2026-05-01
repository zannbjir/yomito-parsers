package org.koitharu.kotatsu.parsers.site.madara.id

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import java.util.Locale

@MangaSourceParser("MGKOMIK", "MgKomik", "id")
internal class MgKomik(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MGKOMIK, "id.mgkomik.cc", 20),
	Interceptor {

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val tagPrefix = "genres/"
	override val listUrl = "komik/"
	override val datePattern = "dd MMM yy"
	override val stylePage = ""
	override val sourceLocale: Locale = Locale.ENGLISH

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
		.add("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
		.add("Referer", "https://$domain/")
		.add("Sec-Fetch-Dest", "document")
		.add("Sec-Fetch-Mode", "navigate")
		.add("Sec-Fetch-Site", "same-origin")
		.add("Sec-Fetch-User", "?1")
		.add("Upgrade-Insecure-Requests", "1")
		.build()

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		if (request.header("X-Requested-With") == null) {
			return chain.proceed(request)
		}
		val cleaned = request.newBuilder().removeHeader("X-Requested-With").build()
		return chain.proceed(cleaned)
	}
}
