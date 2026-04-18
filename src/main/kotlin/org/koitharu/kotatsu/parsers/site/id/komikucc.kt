package org.koitharu.kotatsu.parsers.site.id

import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.core.SinglePageMangaParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

private const val KOMIKUCC_DOMAIN = "komiku.cc"
private const val KOMIKUCC_CDN = "https://cdn.komiku.cc/"

@MangaSourceParser("KOMIKUCC", "Komiku.cc", "id")
internal class Komikucc(context: MangaLoaderContext) :
	SinglePageMangaParser(context, MangaParserSource.KOMIKUCC) {

	override val configKeyDomain = ConfigKey.Domain(KOMIKUCC_DOMAIN)
	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
	)

	override val availableStates: Set<MangaState> = EnumSet.of(
		MangaState.ONGOING,
		MangaState.FINISHED,
		MangaState.PAUSED,
	)

	override val availableContentTypes: Set<ContentType> = EnumSet.of(
		ContentType.MANGA,
		ContentType.MANHWA,
		ContentType.MANHUA,
	)

	private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZZZZZ", Locale.ROOT)

	private val rscHeaders by lazy {
		headers.newBuilder()
			.add("rsc", "1") 
			.add("Referer", "https://$domain/") 
			.build()
	}

	override val filterCapabilities = MangaListFilterCapabilities( 
		isSearchSupported = true,
		isMultipleTagsSupported = true
	)

	// ============================== Popular ===============================
	override suspend fun getList(order: SortOrder, filter: MangaListFilter): List<Manga> {
		return getListPage(1, order, filter)
	}

	private suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val urlBuilder = "https://$domain/list".toHttpUrl().newBuilder()

		if (filter is MangaListFilter.Search) {
			return searchManga(filter.query ?: "")
		} else if (filter is MangaListFilter.Advanced) {
			filter.states.oneOrThrowIfMany()?.let {
				urlBuilder.addQueryParameter(
					"status",
					when (it) {
						MangaState.ONGOING -> "ongoing"
						MangaState.FINISHED -> "completed"
						MangaState.PAUSED -> "hiatus"
						else -> return@let
					},
				)
			}
			filter.types.oneOrThrowIfMany()?.let {
				urlBuilder.addQueryParameter(
					"type",
					when (it) {
						ContentType.MANGA -> "manga"
						ContentType.MANHWA -> "manhwa"
						ContentType.MANHUA -> "manhua"
						else -> return@let
					},
				)
			}
			filter.tags.forEach { tag ->
				urlBuilder.addQueryParameter("genre[]", tag.key)
			}
		}

		urlBuilder.addQueryParameter(
			"order",
			when (order) {
				SortOrder.ALPHABETICAL -> "title"
				SortOrder.NEWEST -> "latest"
				SortOrder.POPULARITY -> "popular"
				SortOrder.UPDATED -> "update"
				else -> "update"
			},
		)
		if (page > 1) urlBuilder.addQueryParameter("page", page.toString())

		val body = webClient.httpGet(urlBuilder.build(), rscHeaders).parseRaw()
		return parseMangaList(body)
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
				id = generateUid(slug),
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

	private fun parseMangaList(body: String): List<Manga> {
		val dataJson = extractRscData(body, "data") ?: return emptyList()
		return try {
			val arr = JSONArray(dataJson)
			(0 until arr.length()).mapNotNull { i ->
				val obj = arr.getJSONObject(i)
				val link = obj.optString("link")
				val title = obj.optString("title")
				val img = obj.optString("img")
				if (link.isEmpty() || title.isEmpty()) return@mapNotNull null
				
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
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

		val statusText = doc.selectFirst(".bg-gray-100.text-gray-800")?.ownText()?.trim()
		val state = when (statusText) {
			"Ongoing" -> MangaState.ONGOING
			"Selesai", "Completed" -> MangaState.FINISHED
			"Hiatus" -> MangaState.PAUSED
			else -> null
		}

		val tags = buildSet {
			doc.select(".bg-zinc-700").forEach { el ->
				val text = el.ownText().trim()
				if (text.isNotBlank()) {
					add(MangaTag(key = text.lowercase(), title = text, source = source))
				}
			}
		}

		val author = doc.selectFirst("span:contains(author:) + span")?.ownText()?.trim()
		val description = doc.select("p.line-clamp-4").joinToString("\n") { it.ownText().trim() }
		val cover = doc.selectFirst("img.object-cover")?.absUrl("src")

		val chapterBody = webClient.httpGet(manga.url.toAbsoluteUrl(domain), rscHeaders).parseRaw()
		val chapters = parseChapterList(chapterBody, manga)

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

	private fun parseChapterList(body: String, manga: Manga): List<MangaChapter> {
		val chaptersJson = extractRscData(body, "chapters") ?: return emptyList()
		return try {
			val arr = JSONArray(chaptersJson)
			val total = arr.length()
			(0 until total).mapNotNull { i ->
				val obj = arr.getJSONObject(i)
				val link = obj.optString("link")
				val title = obj.optString("title")
				if (link.isEmpty() || title.isEmpty()) return@mapNotNull null
				
				val dateStr = obj.optString("updated_at", obj.optString("created_at"))
				
				MangaChapter(
					id = generateUid(link),
					title = title,
					url = link,
					number = (total - i).toFloat(),
					volume = 0,
					scanlator = null,
					uploadDate = if (dateStr.isNotEmpty()) dateFormat.parseSafe(dateStr) else 0L,
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
		val body = webClient.httpGet(chapter.url.toAbsoluteUrl(domain), rscHeaders).parseRaw()
		val imagesJson = extractRscData(body, "images") ?: return emptyList()
		return try {
			val arr = JSONArray(imagesJson)
			(0 until arr.length()).map { i ->
				val img = arr.getString(i)
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

	// ========================== Filter Options ============================
	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = availableStates,
		availableContentTypes = availableContentTypes,
	)

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val body = webClient.httpGet("https://$domain/list", rscHeaders).parseRaw()
		val genresJson = extractRscData(body, "genres") ?: return emptySet()
		return try {
			val arr = JSONArray(genresJson)
			val tags = mutableSetOf<MangaTag>()
			for (i in 0 until arr.length()) {
				val obj = arr.getJSONObject(i)
				val link = obj.optString("link")
				val title = obj.optString("title")
				if (link.isNotEmpty() && title.isNotEmpty()) {
					tags.add(MangaTag(key = link, title = title, source = source))
				}
			}
			tags
		} catch (_: Exception) {
			emptySet()
		}
	}

	private fun extractRscData(body: String, key: String): String? {
		for (line in body.lineSequence()) {
			val jsonPart = line.substringAfter(":", "").trim()
			if (jsonPart.isEmpty()) continue
			try {
				val obj = JSONObject(jsonPart)
				if (obj.has(key)) {
					return obj.get(key).toString()
				}
			} catch (_: Exception) {
				continue
			}
		}
		return null
	}

	private fun String.toAbsoluteCdnUrl(): String = when {
		startsWith("http") -> this
		else -> KOMIKUCC_CDN + removePrefix("/")
	}
}
