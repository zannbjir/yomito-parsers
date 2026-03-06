package org.koitharu.kotatsu.parsers.site.id

import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("THRIVE", "Thrive", "id")
internal class Thrive(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.THRIVE, 20) {

	override val configKeyDomain = ConfigKey.Domain("thrive.moe")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST)

	override val filterCapabilities = MangaListFilterCapabilities(
		isMultipleTagsSupported = true,
		isTagsExclusionSupported = false,
		isSearchSupported = true,
		isSearchWithFiltersSupported = true
	)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions(
			availableTags = emptySet(),
			availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
			availableContentTypes = EnumSet.of(
				ContentType.MANGA,
				ContentType.MANHWA,
				ContentType.MANHUA,
			),
		)
	}

	private fun JSONObject.optStringSafe(key: String): String {
		return optString(key, "")
	}

	private fun Document.extractNextData(): JSONObject {
		val script = selectFirst("script#__NEXT_DATA__")?.data()
			?: throw Exception("__NEXT_DATA__ not found")
		return JSONObject(script)
	}

	private fun JSONObject.getPageProps(): JSONObject {
		return getJSONObject("props").getJSONObject("pageProps")
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = when {
			!filter.query.isNullOrEmpty() -> "https://$domain/search?route=${filter.query.urlEncoded()}"
			else -> if (page > 1) "https://$domain/?page=$page" else "https://$domain/"
		}

		val doc = webClient.httpGet(url).parseHtml()
		val pageProps = doc.extractNextData().getPageProps()

		val mangaArray = pageProps.optJSONArray("terbaru")
			?: pageProps.optJSONArray("res")
			?: return emptyList()

		val mangaList = mutableListOf<Manga>()
		for (i in 0 until mangaArray.length()) {
			val jo = mangaArray.getJSONObject(i)
			val id = jo.optStringSafe("id")
			if (id.isEmpty()) continue

			val cover = jo.optStringSafe("cover")
			val title = jo.optStringSafe("title").ifEmpty { "Untitled" }

			mangaList.add(Manga(
				id = generateUid(id),
				url = "/title/$id",
				publicUrl = "https://$domain/title/$id",
				title = title.trim(),
				altTitles = emptySet(),
				coverUrl = cover,
				largeCoverUrl = cover,
				authors = emptySet(),
				tags = emptySet(),
				state = null,
				description = null,
				contentRating = null,
				source = source,
				rating = RATING_UNKNOWN
			))
		}
		return mangaList
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.publicUrl).parseHtml()
		val data = doc.extractNextData().getPageProps()
		
		val title = data.optStringSafe("title").ifEmpty { manga.title }
		val cover = data.optStringSafe("image").ifEmpty { manga.coverUrl }

		val descObj = data.optJSONObject("desc")
		val description = data.optStringSafe("desc_ID").ifEmpty {
			descObj?.optStringSafe("en") ?: descObj?.optStringSafe("id")
		}

		val altTitles = data.optStringSafe("alt_title")
			.takeIf { it.isNotBlank() }
			?.let { setOf(it) } ?: emptySet()

		val state = when (data.optStringSafe("status").lowercase()) {
			"completed" -> MangaState.FINISHED
			"ongoing" -> MangaState.ONGOING
			else -> MangaState.ONGOING
		}

		val authors = data.optJSONArray("author")?.let { arr ->
			(0 until arr.length()).mapNotNull { arr.optString(it) }.toSet()
		} ?: emptySet()

		val tags = data.optJSONArray("tags")?.let { arr ->
			(0 until arr.length()).mapNotNull { i ->
				val name = arr.optString(i).takeIf { it.isNotBlank() } ?: return@mapNotNull null
				MangaTag(
					title = name,
					key = name.lowercase().replace(" ", "-").replace("'", ""),
					source = source
				)
			}.toSet()
		} ?: emptySet()

		val contentRating = when (data.optStringSafe("rating").lowercase()) {
			"safe" -> null
			"suggestive" -> ContentRating.SUGGESTIVE
			"erotica" -> ContentRating.ADULT
			"pornographic" -> ContentRating.ADULT
			else -> null
		}

		val chaptersArray = data.optJSONArray("chapterlist") ?: JSONArray()
		val chapters = mutableListOf<MangaChapter>()

		for (i in 0 until chaptersArray.length()) {
			val ch = chaptersArray.getJSONObject(i)
			val chId = ch.optStringSafe("chapter_id")
			if (chId.isEmpty()) continue

			val chNum = ch.optStringSafe("chapter_number")
			val chTitle = ch.optStringSafe("chapter_title")
			val number = chNum.toFloatOrNull() ?: 0f

			chapters.add(MangaChapter(
				id = generateUid(chId),
				title = chTitle.ifEmpty { "Chapter $chNum" },
				url = "/read/$chId",
				number = number,
				volume = 0,
				scanlator = ch.optStringSafe("scanlator").takeIf { it.isNotBlank() },
				uploadDate = ch.optStringSafe("created_at").parseDate(),
				branch = null,
				source = source,
			))
		}

		return manga.copy(
			title = title,
			altTitles = altTitles,
			description = description,
			coverUrl = cover,
			largeCoverUrl = cover,
			tags = tags,
			state = state,
			authors = authors,
			contentRating = contentRating,
			chapters = chapters.sortedByDescending { it.number }
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val data = doc.extractNextData().getPageProps()

		val prefix = data.optStringSafe("prefix")
		val images = data.optJSONArray("image") ?: return emptyList()

		val pages = mutableListOf<MangaPage>()
		for (i in 0 until images.length()) {
			val imageName = images.optString(i) ?: continue
			val imageUrl = "https://cdn.thrive.moe/data/$prefix/$imageName"

			pages.add(MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source
			))
		}
		return pages
	}

	private fun String.parseDate(): Long {
		if (this.isBlank()) return 0L
		return try {
			val cleanDate = substringBefore("+").substringBefore(".")
			SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
				timeZone = TimeZone.getTimeZone("UTC")
			}.parse(cleanDate)?.time ?: 0L
		} catch (e: Exception) {
			0L
		}
	}
}
