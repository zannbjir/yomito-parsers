package org.koitharu.kotatsu.parsers.site.madara.id

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import org.koitharu.kotatsu.parsers.network.CommonHeaders
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import java.util.Locale

@MangaSourceParser("MGKOMIK", "MgKomik", "id")
internal class Mgkomik(context: MangaLoaderContext) :
	MadaraParser(
		context,
		MangaParserSource.MGKOMIK,
		"id.mgkomik.cc",
		20,
	) {

	override val withoutAjax = true

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val tagPrefix = "genres/"
	override val listUrl = "komik/"
	override val datePattern = "dd MMM yy"
	override val stylePage = ""
	override val sourceLocale: Locale = Locale.ENGLISH

	override val filterCapabilities = MangaListFilterCapabilities(
		isMultipleTagsSupported = false,
		isSearchSupported = true,
		isSearchWithFiltersSupported = true,
	)

	override suspend fun getListPage(
		page: Int,
		order: SortOrder,
		filter: MangaListFilter,
	): List<Manga> {
		val query = filter.query?.trim().orEmpty()
		val tag = filter.tags.firstOrNull()?.key?.trim().orEmpty()
		val path = if (tag.isBlank()) "komik/" else "genres/${tag.urlEncoded()}/"
		val params = buildList {
			if (query.isNotBlank()) {
				add("s=${query.urlEncoded()}")
				add("post_type=wp-manga")
			}
			add("order_by=${sortKey(order)}")
			add("page=${page + 1}")
		}
		val url = "https://$domain/$path?${params.joinToString("&")}"
		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	private fun sortKey(order: SortOrder): String = when (order) {
		SortOrder.POPULARITY, SortOrder.POPULARITY_ASC -> "trending"
		SortOrder.ALPHABETICAL, SortOrder.ALPHABETICAL_DESC -> "alphabet"
		SortOrder.RATING, SortOrder.RATING_ASC -> "rating"
		SortOrder.NEWEST, SortOrder.NEWEST_ASC -> "new-manga"
		else -> "latest"
	}

	override fun getRequestHeaders(): Headers = super.getRequestHeaders()
		.newBuilder()
		.set(
			CommonHeaders.ACCEPT,
			"text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
		)
		.set(CommonHeaders.ACCEPT_LANGUAGE, "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
		.set("Referer", "https://$domain/")
		.build()

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val response = chain.proceed(request)
		if (
			CloudFlareHelper.checkResponseForProtection(response) !=
			CloudFlareHelper.PROTECTION_NOT_DETECTED
		) {
			response.close()
			context.requestBrowserAction(this, request.url.toString())
		}
		return response
	}
}
