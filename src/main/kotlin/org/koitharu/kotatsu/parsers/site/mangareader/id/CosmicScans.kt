package org.koitharu.kotatsu.parsers.site.mangareader.id

import okhttp3.Headers
import org.json.JSONArray
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@MangaSourceParser("COSMIC_SCANS", "CosmicScans.id", "id")
internal class CosmicScans(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.COSMIC_SCANS, pageSize = 30) {

	override val configKeyDomain = ConfigKey.Domain("02.cosmicscans.to")

	private val apiUrl = "https://cdncid.csmcscns.id/v1"

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities = MangaListFilterCapabilities(
		isSearchSupported = true,
		isSearchWithFiltersSupported = true,
		isMultipleTagsSupported = false,
		isTagsExclusionSupported = false,
	)

	private val genres = listOf(
		"action", "adult", "adventure", "arts", "boys-love", "comedy", "crime", "demons",
		"drama", "drama-supernatural", "ecchi", "fantasy", "gender-bender", "girls-love",
		"harem", "historical", "horror", "isekai", "josei", "life", "magical-girls", "martial",
		"martial-arts", "mature", "mecha", "medical", "music", "mystery", "philosophical",
		"psychological", "reincarnation", "romance", "school", "school-life", "sci-fi", "seinen",
		"shoujo", "shoujo-ai", "shounen", "shounen-ai", "slice-of-life", "smut", "sports",
		"superhero", "supernatural", "thriller", "tragedy", "wuxia", "yuri",
	)

	private val cursors = ConcurrentHashMap<String, ConcurrentHashMap<Int, String>>()

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = genres.map { MangaTag(it.replace('-', ' ').replaceFirstChar { char -> char.uppercase() }, it, source) }.toSet(),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query?.trim().orEmpty()
		val tag = filter.tags.firstOrNull()?.key?.trim().orEmpty()
		val key = "$order|$query|$tag"
		val endpoint = if (query.isNotEmpty()) {
			buildString {
				append("$apiUrl/manga/search?q=").append(query.urlEncoded())
				if (tag.isNotEmpty()) append("&genres=").append(tag.urlEncoded())
			}
		} else {
			buildString {
				append("$apiUrl/manga/filter?limit=30&order_by=")
				append(if (order == SortOrder.ALPHABETICAL) "az" else "update")
				if (tag.isNotEmpty()) append("&genres_slug=").append(tag.urlEncoded())
				cursors[key]?.get(page - 1)?.let { append("&after=").append(it.urlEncoded()) }
			}
		}

		if (query.isNotEmpty() && page > 1) return emptyList()
		val json = webClient.httpGet(endpoint, apiHeaders()).parseJson()
		if (query.isEmpty()) {
			json.optJSONObject("cursor")?.optString("nextCursor", "")?.takeIf { it.isNotBlank() }
				?.let { cursors.getOrPut(key) { ConcurrentHashMap() }[page] = it }
		}
		return parseMangaArray(json.optJSONArray("data"))
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.trim('/').substringAfterLast('/').takeIf { it.isNotBlank() } ?: return manga
		val json = webClient.httpGet("$apiUrl/manga/mangaDetail/${slug.urlEncoded()}", apiHeaders()).parseJson()
		val data = json.optJSONObject("data") ?: json
		val cover = data.optString("cover", "").takeIf { it.isNotBlank() } ?: manga.coverUrl
		return manga.copy(
			title = data.optString("title", manga.title),
			coverUrl = cover,
			largeCoverUrl = data.optString("big_cover", "").takeIf { it.isNotBlank() } ?: cover,
			description = data.optString("sinopsis", "").takeIf { it.isNotBlank() },
			authors = setOfNotNull(
				data.optString("author", "").takeIf { it.isNotBlank() },
				data.optString("artist", "").takeIf { it.isNotBlank() },
			),
			tags = parseGenres(data.optJSONArray("genre")),
			state = parseState(data.optString("status")),
			rating = parseRating(data.optString("rating")),
			chapters = parseChapters(slug, data.optJSONArray("chapters")),
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val path = chapter.url.trim('/')
		val chapterSlug = path.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return emptyList()
		val json = webClient.httpGet("$apiUrl/manga/readingPage/${chapterSlug.urlEncoded()}", apiHeaders()).parseJson()
		val data = json.optJSONObject("data") ?: return emptyList()
		val images = data.optJSONArray("chapters") ?: return emptyList()
		return (0 until images.length()).mapNotNull { index ->
			val imageUrl = extractImageUrl(images.optString(index, ""))
			if (imageUrl.isBlank()) null else MangaPage(generateUid(imageUrl), imageUrl, null, source)
		}
	}

	private fun parseMangaArray(array: JSONArray?): List<Manga> {
		if (array == null) return emptyList()
		return (0 until array.length()).mapNotNull { index ->
			val item = array.optJSONObject(index) ?: return@mapNotNull null
			val slug = item.optString("slug", "").trim()
			if (slug.isBlank()) return@mapNotNull null
			val cover = item.optString("cover", "")
			Manga(
				id = generateUid(slug),
				url = "/series/$slug",
				publicUrl = "https://$domain/series/$slug",
				title = item.optString("title", "Untitled"),
				altTitles = emptySet(),
				coverUrl = cover,
				largeCoverUrl = item.optString("big_cover", "").ifBlank { cover },
				rating = parseRating(item.optString("rating")),
				contentRating = ContentRating.SAFE,
				tags = parseGenres(item.optJSONArray("genres")),
				state = parseState(item.optString("status")),
				authors = emptySet(),
				source = source,
			)
		}
	}

	private fun parseChapters(slug: String, array: JSONArray?): List<MangaChapter> {
		if (array == null) return emptyList()
		return (0 until array.length()).mapNotNull { index ->
			val item = array.optJSONObject(index) ?: return@mapNotNull null
			val chapterSlug = item.optString("slug", "").trim()
			val numberText = item.optString("chapterNum", "").trim()
			val number = numberText.toFloatOrNull() ?: return@mapNotNull null
			if (chapterSlug.isBlank()) return@mapNotNull null
			val url = "/series/$slug/chapter/$chapterSlug"
			MangaChapter(
				id = generateUid(url),
				title = "Chapter ${formatNumber(numberText)}",
				url = url,
				number = number,
				uploadDate = parseDate(item.optString("time")),
				source = source,
				scanlator = null,
				branch = null,
				volume = 0,
			)
		}.sortedBy { it.number }
	}

	private fun parseGenres(array: JSONArray?): Set<MangaTag> {
		if (array == null) return emptySet()
		return (0 until array.length()).mapNotNull { index ->
			val name = array.optString(index, "").trim()
			name.takeIf { it.isNotBlank() }?.let { MangaTag(it, it.lowercase().replace(' ', '-'), source) }
		}.toSet()
	}

	private fun parseState(value: String?): MangaState? = when (value?.lowercase(Locale.ROOT)) {
		"ongoing", "berjalan" -> MangaState.ONGOING
		"completed", "finished", "tamat", "selesai" -> MangaState.FINISHED
		else -> null
	}

	private fun parseRating(value: String?): Float {
		val rating = value?.toFloatOrNull() ?: return RATING_UNKNOWN
		return if (rating > 1f) rating / 10f else rating
	}

	private fun parseDate(value: String?): Long = runCatching {
		SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.US).parse(value.orEmpty())?.time ?: 0L
	}.getOrDefault(0L)

	private fun formatNumber(value: String): String {
		val number = value.toFloatOrNull() ?: return value
		return if (number == number.toInt().toFloat()) number.toInt().toString() else value.trimEnd('0').trimEnd('.')
	}

	private fun extractImageUrl(value: String): String {
		if (value.startsWith("http://") || value.startsWith("https://")) return value
		return Regex("<img[^>]+src=['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE).find(value)?.groupValues?.get(1).orEmpty()
	}

	private fun apiHeaders() = Headers.Builder()
		.add("Accept", "application/json")
		.add("Referer", "https://$domain/")
		.add("Origin", "https://$domain")
		.build()
}
