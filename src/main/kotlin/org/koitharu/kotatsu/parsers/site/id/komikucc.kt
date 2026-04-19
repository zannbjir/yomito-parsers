package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

private const val KOMIKUCC_CDN = "https://cdn.komiku.cc/"


@MangaSourceParser("KOMIKUCC", "Komiku.cc", "id")
internal class Komikucc(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.KOMIKUCC, 20) {

	override val configKeyDomain = ConfigKey.Domain("komiku.cc")

	private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZZZZZ", Locale.ROOT)

	// RSC header wajib untuk Next.js RSC endpoint
	private fun rscHeaders(): Headers = Headers.Builder()
		.add("rsc", "1")
		.add("Next-Router-State-Tree", "%5B%22%22%2C%7B%22children%22%3A%5B%22(site)%22%2C%7B%22children%22%3A%5B%22__PAGE__%22%2C%7B%7D%5D%7D%5D%7D%2Cnull%2Cnull%2Ctrue%5D")
		.add("Referer", "https://$domain/")
		.add("Accept", "text/x-component")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities = MangaListFilterCapabilities(
		isSearchSupported = true,
		isMultipleTagsSupported = true,
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
		availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA),
	)

	// ============================== List ==================================
	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		// Search pakai endpoint berbeda (non-RSC, HTML biasa)
		if (filter is MangaListFilter.Search) {
			return searchManga(filter.query)
		}

		val urlBuilder = "https://$domain/list".toHttpUrl().newBuilder()

		if (filter is MangaListFilter.Advanced) {
			filter.states.oneOrThrowIfMany()?.let {
				urlBuilder.addQueryParameter("status", when (it) {
					MangaState.ONGOING -> "ongoing"
					MangaState.FINISHED -> "completed"
					MangaState.PAUSED -> "hiatus"
					else -> return@let
				})
			}
			filter.types.oneOrThrowIfMany()?.let {
				urlBuilder.addQueryParameter("type", when (it) {
					ContentType.MANGA -> "manga"
					ContentType.MANHWA -> "manhwa"
					ContentType.MANHUA -> "manhua"
					else -> return@let
				})
			}
			filter.tags.forEach { tag ->
				urlBuilder.addQueryParameter("genre[]", tag.key)
			}
		}

		urlBuilder.addQueryParameter("order", when (order) {
			SortOrder.ALPHABETICAL -> "title"
			SortOrder.NEWEST -> "latest"
			SortOrder.POPULARITY -> "popular"
			else -> "update"
		})

		if (page > 1) urlBuilder.addQueryParameter("page", page.toString())

		val body = webClient.httpGet(urlBuilder.build(), rscHeaders()).parseRaw()
		return parseMangaListFromRsc(body)
	}

	private suspend fun searchManga(query: String): List<Manga> {
		val url = "https://$domain/search".toHttpUrl().newBuilder()
			.addQueryParameter("q", query.trim())
			.build()
		val doc = webClient.httpGet(url).parseHtml()
		return doc.select("a[href*=/komik/]").mapNotNull { element ->
			val href = element.absUrl("href")
			val slug = href.toHttpUrl().pathSegments.getOrNull(1) ?: return@mapNotNull null
			Manga(
				id = generateUid("/komik/$slug"),
				url = "/komik/$slug",
				publicUrl = href,
				title = element.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null,
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = element.selectFirst("img")?.absUrl("src"),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	private fun parseMangaListFromRsc(body: String): List<Manga> {
		val dataJson = extractRscKey(body, "data") ?: return emptyList()
		return try {
			val arr = JSONArray(dataJson)
			(0 until arr.length()).mapNotNull { i ->
				val obj = arr.optJSONObject(i) ?: return@mapNotNull null
				val link = obj.optString("link").ifBlank { return@mapNotNull null }
				val title = obj.optString("title").ifBlank { return@mapNotNull null }
				val img = obj.optString("img")
				Manga(
					id = generateUid(link),
					url = link,
					publicUrl = "https://$domain$link",
					title = title,
					altTitles = emptySet(),
					rating = RATING_UNKNOWN,
					contentRating = null,
					coverUrl = img.toAbsoluteCdnUrl(),
					tags = emptySet(),
					state = null,
					authors = emptySet(),
					source = source,
				)
			}
		} catch (_: Exception) {
			emptyList()
		}
	}

	// =========================== Manga Details ============================
	override suspend fun getDetails(manga: Manga): Manga {
		// Detail page: fetch HTML biasa dulu untuk status/cover/desc
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

		val statusText = doc.selectFirst(".bg-gray-100.text-gray-800")?.ownText()?.trim()
		val state = when (statusText) {
			"Ongoing" -> MangaState.ONGOING
			"Selesai", "Completed" -> MangaState.FINISHED
			"Hiatus" -> MangaState.PAUSED
			else -> null
		}

		val tags = doc.select(".bg-zinc-700").mapNotNullToSet { el ->
			val text = el.ownText().trim()
			if (text.isBlank()) return@mapNotNullToSet null
			MangaTag(key = text.lowercase(Locale.ROOT), title = text, source = source)
		}

		val author = doc.selectFirst("span:contains(author:) + span")?.ownText()?.trim()
		val description = doc.select("p.line-clamp-4").joinToString("\n") { it.ownText().trim() }
		val cover = doc.selectFirst("img.object-cover")?.absUrl("src")

		// Chapter list: fetch via RSC endpoint (sama URL, beda header)
		val chapterBody = webClient.httpGet(manga.url.toAbsoluteUrl(domain), rscHeaders()).parseRaw()
		val chapters = parseChapterListFromRsc(chapterBody)

		return manga.copy(
			description = description.ifBlank { null },
			state = state,
			authors = setOfNotNull(author),
			contentRating = ContentRating.SAFE,
			tags = tags,
			chapters = chapters,
			coverUrl = cover ?: manga.coverUrl,
		)
	}

	private fun parseChapterListFromRsc(body: String): List<MangaChapter> {
		val chaptersJson = extractRscKey(body, "chapters") ?: return emptyList()
		return try {
			val arr = JSONArray(chaptersJson)
			val total = arr.length()
			(0 until total).mapNotNull { i ->
				val obj = arr.optJSONObject(i) ?: return@mapNotNull null
				val link = obj.optString("link").ifBlank { return@mapNotNull null }
				val title = obj.optString("title").ifBlank { return@mapNotNull null }
				// updated_at lebih reliable dari created_at
				val dateStr = obj.optString("updated_at").ifBlank { obj.optString("created_at") }
				MangaChapter(
					id = generateUid(link),
					title = title,
					url = link,
					number = (total - i).toFloat(),
					volume = 0,
					scanlator = null,
					uploadDate = dateFormat.parseSafe(dateStr),
					branch = null,
					source = source,
				)
			}
		} catch (_: Exception) {
			emptyList()
		}
	}

	// =============================== Pages ================================
	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val body = webClient.httpGet(chapter.url.toAbsoluteUrl(domain), rscHeaders()).parseRaw()
		val imagesJson = extractRscKey(body, "images") ?: return emptyList()
		return try {
			val arr = JSONArray(imagesJson)
			(0 until arr.length()).mapNotNull { i ->
				val img = arr.optString(i).ifBlank { return@mapNotNull null }
				MangaPage(
					id = generateUid("$i-${chapter.url}"),
					url = img.toAbsoluteCdnUrl(),
					preview = null,
					source = source,
				)
			}
		} catch (_: Exception) {
			emptyList()
		}
	}

	// ====================== Tags ==========================================
	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		// Genres tersedia di RSC response dari /list
		val body = webClient.httpGet("https://$domain/list", rscHeaders()).parseRaw()
		val genresJson = extractRscKey(body, "genres") ?: return emptySet()
		return try {
			val arr = JSONArray(genresJson)
			val tags = mutableSetOf<MangaTag>()
			for (i in 0 until arr.length()) {
				val obj = arr.optJSONObject(i) ?: continue
				val link = obj.optString("link").ifBlank { continue }
				val title = obj.optString("title").ifBlank { continue }
				tags.add(MangaTag(key = link, title = title, source = source))
			}
			tags
		} catch (_: Exception) {
			emptySet()
		}
	}


	private fun extractRscKey(body: String, key: String): String? {
		for (line in body.lineSequence()) {
			val colonIdx = line.indexOf(':')
			if (colonIdx < 0) continue

			// Skip prefix numerik
			val prefix = line.substring(0, colonIdx)
			if (!prefix.all { it.isDigit() }) continue

			val content = line.substring(colonIdx + 1).trim()
			if (content.isEmpty()) continue

			if (content.startsWith("{")) {
				try {
					val obj = JSONObject(content)
					if (obj.has(key)) {
						return obj.get(key).toString()
					}
				} catch (_: Exception) {
				}
			}
		}
		return null
	}

	private fun String.toAbsoluteCdnUrl(): String = when {
		startsWith("http") -> this
		else -> KOMIKUCC_CDN + removePrefix("/")
	}
}
