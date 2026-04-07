package org.koitharu.kotatsu.parsers.site.en

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.EnumSet

@MangaSourceParser("MANGACLOUD", "MangaCloud", "en", ContentType.MANGA)
internal class MangaCloud(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANGACLOUD, 20) {

	override val configKeyDomain = ConfigKey.Domain("mangacloud.org")

	private val apiUrl = "https://api.mangacloud.org"
	private val cdnUrl = "https://pika.mangacloud.org"

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
		)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
		SortOrder.RELEVANCE,
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
			MangaState.ABANDONED,
		),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	private var cachedTags: Set<MangaTag>? = null

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		cachedTags?.let { return it }
		return try {
			val response = webClient.httpGet("$apiUrl/tag/list").parseJson()
			val data = response.getJSONArray("data")
			val tags = mutableSetOf<MangaTag>()
			for (i in 0 until data.length()) {
				val tag = data.getJSONObject(i)
				tags.add(
					MangaTag(
						key = tag.getString("id"),
						title = tag.getString("name"),
						source = source,
					)
				)
			}
			cachedTags = tags
			tags
		} catch (_: Exception) {
			emptySet()
		}
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		if (!filter.query.isNullOrEmpty()) {
			return getBrowseManga(page, filter, order)
		}
		return when (order) {
			SortOrder.POPULARITY -> getPopularManga(page)
			SortOrder.UPDATED -> getLatestManga(page)
			else -> getBrowseManga(page, filter, order)
		}
	}

	private suspend fun getPopularManga(page: Int): List<Manga> {
		val time = when (page) {
			1 -> "today"
			2 -> "week"
			else -> "month"
		}
		val response = webClient.httpGet("$apiUrl/comic-popular-view/$time").parseJson()
		val data = response.getJSONObject("data")
		val list = data.getJSONArray("list")
		return (0 until list.length()).map { parseMangaFromBrowse(list.getJSONObject(it)) }
	}

	private suspend fun getLatestManga(page: Int): List<Manga> {
		val jsonBody = JSONObject().apply { put("page", page) }
		val response = webClient.httpPost("$apiUrl/comic-updates".toHttpUrl(), jsonBody).parseJson()
		val data = response.getJSONObject("data")
		val list = data.getJSONArray("list")
		return (0 until list.length()).map { parseMangaFromBrowse(list.getJSONObject(it)) }
	}

	private suspend fun getBrowseManga(page: Int, filter: MangaListFilter, order: SortOrder? = null): List<Manga> {
		val includes = JSONArray()
		filter.tags.forEach { includes.put(it.key) }
		val excludes = JSONArray()
		filter.tagsExclude.forEach { excludes.put(it.key) }

		val jsonBody = JSONObject().apply {
			put("title", filter.query?.takeIf { it.isNotBlank() })
			put("type", filter.types.firstOrNull()?.let { type ->
				when (type) {
					ContentType.MANGA -> "Manga"
					ContentType.MANHWA -> "Manhwa"
					ContentType.MANHUA -> "Manhua"
					else -> null
				}
			})
			put("sort", order?.let {
				when (it) {
					SortOrder.NEWEST -> "created_date-DESC"
					SortOrder.ALPHABETICAL -> "title-ASC"
					SortOrder.ALPHABETICAL_DESC -> "title-DESC"
					SortOrder.UPDATED -> "updated_date-DESC"
					SortOrder.RATING -> "rating"
					else -> null
				}
			})
			put("status", filter.states.firstOrNull()?.let { state ->
				when (state) {
					MangaState.ONGOING -> "Ongoing"
					MangaState.FINISHED -> "Completed"
					MangaState.PAUSED -> "Hiatus"
					MangaState.ABANDONED -> "Cancelled"
					else -> null
				}
			})
			put("includes", includes)
			put("excludes", excludes)
			put("page", page)
		}

		val response = webClient.httpPost("$apiUrl/comic/browse".toHttpUrl(), jsonBody).parseJson()
		val data = response.getJSONArray("data")
		return (0 until data.length()).map { parseMangaFromBrowse(data.getJSONObject(it)) }
	}

	private fun parseMangaFromBrowse(json: JSONObject): Manga {
		val slug = json.optString("slug", json.getString("id"))
		val title = json.getString("title")
		val poster = json.optString("poster", "").nullIfEmpty()
		val status = json.optString("status", "").nullIfEmpty()

		val coverUrl = poster?.let {
			if (it.startsWith("http")) it else "$cdnUrl/$it"
		}

		val tags = parseTags(json.optJSONArray("tags"))

		return Manga(
			id = generateUid(slug),
			url = slug,
			publicUrl = "https://mangacloud.org/comic/$slug",
			coverUrl = coverUrl,
			title = title,
			altTitles = emptySet(),
			rating = RATING_UNKNOWN,
			contentRating = ContentRating.SAFE,
			tags = tags,
			state = parseState(status),
			authors = emptySet(),
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val response = webClient.httpGet("$apiUrl/comic/${manga.url}").parseJson()
		val data = response.getJSONObject("data")

		val title = data.getString("title")
		val poster = data.optString("poster", "").nullIfEmpty()
		val description = data.optString("description", "").nullIfEmpty()
		val status = data.optString("status", "").nullIfEmpty()
		val author = data.optString("author", "").nullIfEmpty()
		val comicId = data.getString("id")

		val coverUrl = poster?.let {
			if (it.startsWith("http")) it else "$cdnUrl/$it"
		} ?: manga.coverUrl

		val tags = parseTags(data.optJSONArray("tags"))
		val authors = author?.let { setOf(it) }.orEmpty()

		val chapters = mutableListOf<MangaChapter>()
		val chaptersArray = data.optJSONArray("chapters")
		if (chaptersArray != null) {
			for (i in 0 until chaptersArray.length()) {
				val ch = chaptersArray.getJSONObject(i)
				val chapterId = ch.getString("id")
				val number = ch.optDouble("number", 0.0).toFloat()
				val name = ch.optString("name", "").nullIfEmpty()
				val date = ch.optLong("date", 0L)

				val chapterTitle = buildString {
					append("Chapter ")
					append(number.toString().substringBefore(".0"))
					name?.let {
						append(" - ")
						append(it)
					}
				}

				chapters.add(
					MangaChapter(
						id = generateUid(chapterId),
						title = chapterTitle,
						number = number,
						volume = 0,
						url = JSONObject().apply {
							put("comicId", comicId)
							put("chapterId", chapterId)
						}.toString(),
						uploadDate = date,
						source = source,
						scanlator = null,
						branch = null,
					)
				)
			}
		}

		return manga.copy(
			title = title,
			coverUrl = coverUrl,
			description = description,
			tags = tags,
			authors = authors,
			state = parseState(status),
			chapters = chapters.reversed(),
		)
	}

	override suspend fun getRelatedManga(seed: Manga): List<Manga> = emptyList()

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterData = JSONObject(chapter.url)
		val chapterId = chapterData.getString("chapterId")
		val comicId = chapterData.getString("comicId")

		val response = webClient.httpGet("$apiUrl/chapter/$chapterId").parseJson()
		val data = response.getJSONObject("data")
		val images = data.getJSONArray("images")

		return (0 until images.length()).map { i ->
			val img = images.getJSONObject(i)
			MangaPage(
				id = generateUid("$chapterId-$i"),
				url = "$cdnUrl/$comicId/$chapterId/${img.getString("id")}.${img.getString("format")}",
				preview = null,
				source = source,
			)
		}
	}

	private fun parseState(status: String?): MangaState? = when (status) {
		"ongoing" -> MangaState.ONGOING
		"completed" -> MangaState.FINISHED
		"hiatus" -> MangaState.PAUSED
		"cancelled" -> MangaState.ABANDONED
		else -> null
	}

	private fun parseTags(tagsArray: JSONArray?): Set<MangaTag> {
		if (tagsArray == null) return emptySet()
		val tags = mutableSetOf<MangaTag>()
		for (i in 0 until tagsArray.length()) {
			val tagObj = tagsArray.getJSONObject(i)
			tags.add(
				MangaTag(
					key = tagObj.getString("id"),
					title = tagObj.getString("name"),
					source = source,
				)
			)
		}
		return tags
	}
}
