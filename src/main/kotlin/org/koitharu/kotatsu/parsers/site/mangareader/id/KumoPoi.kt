package org.koitharu.kotatsu.parsers.site.mangareader.id

import okhttp3.Headers
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
import java.util.TimeZone

@MangaSourceParser("KUMOPOI", "KumoPoi", "id")
internal class KumoPoi(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.KUMOPOI, pageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain("beta.kumopoi.com")

	private val apiHost = "api.kumopoi.com"
	private val cdnHost = "https://kumo.gorae.my.id"

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
		)

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Accept", "application/json, text/plain, */*")
		.add("Origin", "https://$domain")
		.add("Referer", "https://$domain/")
		.build()

	override suspend fun getFavicons(): Favicons {
		val icon = runCatching {
			webClient.httpGet("https://$apiHost/api/v1/settings", getRequestHeaders())
				.parseJson().getJSONObject("data").getString("navicon")
		}.getOrNull()?.takeIf { it.isNotBlank() }
		return Favicons.single("$cdnHost/${icon ?: "settings/navicon_1784968110748.png"}")
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = fetchGenres(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA),
	)

	private suspend fun fetchGenres(): Set<MangaTag> {
		val array = webClient.httpGet("https://$apiHost/api/v1/genres", getRequestHeaders())
			.parseJson().getJSONArray("data")
		val result = LinkedHashSet<MangaTag>()
		for (i in 0 until array.length()) {
			val item = array.optJSONObject(i) ?: continue
			val slug = item.optString("slug").takeIf { it.isNotBlank() } ?: continue
			val name = item.optString("name").takeIf { it.isNotBlank() } ?: continue
			if (item.optJSONObject("_count")?.optInt("comics", 1) == 0) continue
			result += MangaTag(title = name.toTitleCase(), key = slug, source = source)
		}
		return result
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://$apiHost/api/v1/comics?page=").append(page)
			append("&limit=").append(pageSize)
			append("&excludeAdult=false")
			append("&sort=").append(
				when (order) {
					SortOrder.POPULARITY -> "popular"
					SortOrder.ALPHABETICAL -> "az"
					else -> "latest"
				},
			)
			filter.query?.takeIf { it.isNotBlank() }?.let { append("&search=").append(it.urlEncoded()) }
			filter.tags.firstOrNull()?.let { append("&genre=").append(it.key) }
			filter.states.oneOrThrowIfMany()?.let { state ->
				when (state) {
					MangaState.ONGOING -> append("&status=ONGOING")
					MangaState.FINISHED -> append("&status=END")
					else -> Unit
				}
			}
			filter.types.oneOrThrowIfMany()?.let { type ->
				when (type) {
					ContentType.MANGA -> append("&type=MANGA")
					ContentType.MANHWA -> append("&type=MANHWA")
					ContentType.MANHUA -> append("&type=MANHUA")
					else -> Unit
				}
			}
		}
		val response = webClient.httpGet(url, getRequestHeaders()).parseJson()
		val data = response.optJSONObject("data") ?: return emptyList()
		return parseList(data.optJSONArray("data"))
	}

	private fun parseList(array: JSONArray?): List<Manga> {
		if (array == null) return emptyList()
		val result = ArrayList<Manga>(array.length())
		for (i in 0 until array.length()) {
			val item = array.optJSONObject(i) ?: continue
			val slug = item.optString("slug").takeIf { it.isNotBlank() } ?: continue
			val title = item.optString("title").takeIf { it.isNotBlank() } ?: continue
			result += Manga(
				id = generateUid(slug),
				title = title,
				altTitles = emptySet(),
				url = "/comic/$slug",
				publicUrl = "https://$domain/comic/$slug",
				coverUrl = coverUrl(item.getStringOrNull("cover")),
				largeCoverUrl = null,
				rating = RATING_UNKNOWN,
				contentRating = if (item.optBoolean("isAdult", false)) ContentRating.ADULT else null,
				tags = parseTags(item.optJSONArray("genres")),
				state = parseState(item.getStringOrNull("status")),
				authors = emptySet(),
				source = source,
			)
		}
		return result
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.substringAfterLast("/comic/").trim('/').substringBefore('/')
		val data = webClient.httpGet("https://$apiHost/api/v1/comics/$slug", getRequestHeaders())
			.parseJson().getJSONObject("data")
		val chapters = ArrayList<MangaChapter>()
		data.optJSONArray("chapters")?.let { array ->
			for (i in 0 until array.length()) {
				val item = array.optJSONObject(i) ?: continue
				if (item.optBoolean("isLocked", false)) continue
				val chapterId = item.optString("id").takeIf { it.isNotBlank() } ?: continue
				val number = item.optString("number").toFloatOrNull() ?: continue
				val chapterTitle = item.optString("title").takeIf { it.isNotBlank() } ?: "Chapter $number"
				chapters += MangaChapter(
					id = generateUid(chapterId),
					title = chapterTitle,
					number = number,
					volume = 0,
					url = "/comic/$slug/chapter/$chapterId",
					scanlator = null,
					uploadDate = parseDate(item.getStringOrNull("publishedAt")),
					branch = null,
					source = source,
				)
			}
		}
		chapters.sortBy { it.number }
		return manga.copy(
			title = data.optString("title").takeIf { it.isNotBlank() } ?: manga.title,
			description = data.optString("description").takeIf { it.isNotBlank() } ?: "",
			coverUrl = coverUrl(data.getStringOrNull("cover")) ?: manga.coverUrl,
			largeCoverUrl = null,
			altTitles = data.optJSONArray("altTitles")?.toStringList() ?: manga.altTitles,
			tags = parseTags(data.optJSONArray("genres")).ifEmpty { manga.tags },
			authors = setOfNotNull(data.getStringOrNull("author")),
			state = parseState(data.getStringOrNull("status")) ?: manga.state,
			contentRating = if (data.optBoolean("isAdult", false)) ContentRating.ADULT else manga.contentRating,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterId = chapter.url.substringAfterLast('/')
		val data = webClient.httpGet("https://$domain/api/reader/$chapterId/pages", getRequestHeaders())
			.parseJson().optJSONObject("data") ?: return emptyList()
		val pages = data.optJSONArray("pages") ?: return emptyList()
		val result = ArrayList<MangaPage>(pages.length())
		for (i in 0 until pages.length()) {
			val item = pages.optJSONObject(i) ?: continue
			val token = item.optString("token").takeIf { it.isNotBlank() } ?: continue
			val url = "https://$apiHost/api/v1/media/chapter/deliver?token=$token"
			result += MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
		return result
	}

	private fun parseTags(array: JSONArray?): Set<MangaTag> {
		if (array == null) return emptySet()
		val result = LinkedHashSet<MangaTag>()
		for (i in 0 until array.length()) {
			val item = array.optJSONObject(i) ?: continue
			val slug = item.optString("slug").takeIf { it.isNotBlank() } ?: continue
			val name = item.optString("name").takeIf { it.isNotBlank() } ?: slug
			result += MangaTag(title = name.toTitleCase(), key = slug, source = source)
		}
		return result
	}

	private fun parseState(status: String?): MangaState? = when (status?.uppercase(Locale.ROOT)) {
		"ONGOING" -> MangaState.ONGOING
		"END", "COMPLETED", "END_SEASON" -> MangaState.FINISHED
		"HIATUS" -> MangaState.PAUSED
		"DROP", "DROPPED" -> MangaState.ABANDONED
		else -> null
	}

	private fun coverUrl(cover: String?): String? {
		if (cover.isNullOrBlank()) return null
		if (cover.startsWith("http")) return cover
		return "$cdnHost/${cover.removePrefix("/")}".replace(" ", "%20")
	}

	private fun parseDate(iso: String?): Long {
		if (iso.isNullOrBlank()) return 0L
		return runCatching {
			SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
				.apply { timeZone = TimeZone.getTimeZone("UTC") }
				.parse(iso)?.time
		}.getOrNull() ?: 0L
	}

	private fun JSONArray.toStringList(): Set<String> {
		val result = LinkedHashSet<String>(length())
		for (i in 0 until length()) {
			optString(i).takeIf { it.isNotBlank() }?.let { result += it }
		}
		return result
	}

	private fun JSONObject.getStringOrNull(name: String): String? =
		if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
}
