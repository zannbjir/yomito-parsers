package org.koitharu.kotatsu.parsers.site.madara.id

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("MGKOMIK", "MgKomik", "id")
internal class MgKomik(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MGKOMIK, "id.mgkomik.cc") {

	// id.mgkomik.cc is a Madara WordPress site (mangaSubString=komik). The previous
	// MangaReaderParser config was wrong and produced an empty list once Cloudflare
	// cleared; the Tachiyomi-side keiyoushi extension confirms it's a Madara site.
	override val listUrl = "komik/"
	override val tagPrefix = "komik-genre/"
	override val datePattern = "dd MMM yy"

	// The keiyoushi extension adds Sec-Fetch-* headers and a randomised X-Requested-With
	// header so the in-app webview-based Cloudflare challenge solver passes more reliably,
	// then strips X-Requested-With on every actual request via an interceptor (otherwise
	// `id.mgkomik.cc` rejects requests carrying it). We mirror the same trick here so the
	// chapter pages and `wp-content/uploads/` images don't keep returning Cloudflare blocks.
	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("User-Agent", config[userAgentKey])
		.add("Sec-Fetch-Dest", "document")
		.add("Sec-Fetch-Mode", "navigate")
		.add("Sec-Fetch-Site", "same-origin")
		.add("Upgrade-Insecure-Requests", "1")
		.add("X-Requested-With", randomXrwToken())
		.build()

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		if (request.header("X-Requested-With") == null) {
			return chain.proceed(request)
		}
		val rebuilt = request.newBuilder()
			.removeHeader("X-Requested-With")
			.build()
		return chain.proceed(rebuilt)
	}

	private fun randomXrwToken(): String {
		val length = (8..20).random()
		val chars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
		return buildString(length) { repeat(length) { append(chars.random()) } }
	}
}
