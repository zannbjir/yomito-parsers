package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
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
import java.util.concurrent.ConcurrentHashMap

@MangaSourceParser("KOMIKNESIA", "KomikNesia", "id")
internal class Komiknesia(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.KOMIKNESIA, pageSize = 20) {

	override val configKeyDomain = ConfigKey.Domain("02.komiknesia.asia")

	private val apiBase = "https://api-be.komiknesia.my.id/api"

	private fun apiHeaders(): Headers = Headers.Builder()
		.add("Accept", "application/json, text/plain, */*")
		.add("Origin", "https://$domain")
		.add("Referer", "https://$domain/")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST)

	override val filterCapabilities = MangaListFilterCapabilities(
		isMultipleTagsSupported = false,
		isSearchSupported = true,
		isSearchWithFiltersSupported = false,
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchTags(),
	)

	private val tagsCache = ConcurrentHashMap<String, Set<MangaTag>>()

	private suspend fun fetchTags(): Set<MangaTag> {
		tagsCache["all"]?.let { return it }
		return runCatching {
			val arr = webClient.httpGet("$apiBase/categories", apiHeaders()).parseJsonArray()
			val tags = LinkedHashSet<MangaTag>(arr.length())
			for (i in 0 until arr.length()) {
				val jo = arr.optJSONObject(i) ?: continue
				val id = jo.optInt("id", 0)
				val name = jo.optString("name").ifBlank { continue }
				if (id <= 0) continue
				tags.add(MangaTag(title = name, key = id.toString(), source = source))
			}
			tags
		}.getOrDefault(emptySet()).also { tagsCache["all"] = it }
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query?.takeIf { it.isNotBlank() }
		if (query != null) return searchManga(query, page)

		val url = buildString {
			append(apiBase).append("/manga?page=").append(page)
			append("&limit=").append(pageSize)
			append("&source=local")
			filter.tags.firstOrNull()?.key?.let { append("&category=").append(it) }
		}
		val json = webClient.httpGet(url, apiHeaders()).parseJson()
		val mangaArr = json.optJSONArray("manga") ?: return emptyList()
		val result = ArrayList<Manga>(mangaArr.length())
		for (i in 0 until mangaArr.length()) {
			val item = mangaArr.optJSONObject(i) ?: continue
			parseListItem(item)?.let { result.add(it) }
		}
		return result
	}

	private suspend fun searchManga(query: String, page: Int): List<Manga> {
		val url = "$apiBase/manga/search?query=${query.urlEncoded()}&page=$page&limit=$pageSize"
		val json = webClient.httpGet(url, apiHeaders()).parseJson()
		val local = json.optJSONArray("local") ?: return emptyList()
		val result = ArrayList<Manga>(local.length())
		for (i in 0 until local.length()) {
			val item = local.optJSONObject(i) ?: continue
			parseListItem(item)?.let { result.add(it) }
		}
		return result
	}

	private fun parseListItem(item: JSONObject): Manga? {
		val slug = item.optString("slug").ifBlank { return null }
		val title = item.optString("title").ifBlank { return null }
		val url = "/komik/$slug"
		val cover = item.optString("thumbnail").ifBlank { item.optString("cover") }
		val rating = item.optDouble("rating", Double.NaN)
			.takeIf { !it.isNaN() }
			?.let { (it / 10f).toFloat().coerceIn(0f, 1f) }
			?: RATING_UNKNOWN
		val status = item.optString("status")
		val state = parseState(status)
		val tagsArr = item.optJSONArray("genres")
		val tags = if (tagsArr != null) {
			val s = LinkedHashSet<MangaTag>(tagsArr.length())
			for (i in 0 until tagsArr.length()) {
				val g = tagsArr.optJSONObject(i) ?: continue
				val id = g.optInt("id", 0)
				val name = g.optString("name").ifBlank { continue }
				if (id <= 0) continue
				s.add(MangaTag(title = name, key = id.toString(), source = source))
			}
			s
		} else {
			emptySet()
		}
		val isSafe = optBoolOrInt(item, "is_safe", true)
		return Manga(
			id = generateUid(url),
			title = title,
			altTitles = setOfNotNull(item.optString("alternative_name").takeIf { it.isNotBlank() && it != "null" }),
			url = url,
			publicUrl = "https://$domain$url",
			rating = rating,
			contentRating = if (isSafe) ContentRating.SAFE else ContentRating.SUGGESTIVE,
			coverUrl = cover,
			tags = tags,
			state = state,
			authors = setOfNotNull(item.optString("author").takeIf { it.isNotBlank() && it != "Unknown" }),
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.trimEnd('/').substringAfterLast('/')
		val json = webClient.httpGet("$apiBase/manga/slug/$slug", apiHeaders()).parseJson()

		val title = json.optString("title").ifBlank { manga.title }
		val synopsis = json.optString("synopsis").takeIf { it.isNotBlank() }
		val cover = json.optString("thumbnail").ifBlank { json.optString("cover_background") }
		val state = parseState(json.optString("status"))
		val author = json.optString("author").takeIf { it.isNotBlank() && it != "Unknown" }
		val altName = json.optString("alternative_name").takeIf { it.isNotBlank() && it != "null" }
		val rating = json.optDouble("rating", Double.NaN)
			.takeIf { !it.isNaN() }
			?.let { (it / 10f).toFloat().coerceIn(0f, 1f) }
			?: RATING_UNKNOWN

		val tags = LinkedHashSet<MangaTag>()
		json.optJSONArray("genres")?.let { arr ->
			for (i in 0 until arr.length()) {
				val g = arr.optJSONObject(i) ?: continue
				val id = g.optInt("id", 0)
				val name = g.optString("name").ifBlank { continue }
				if (id > 0) tags.add(MangaTag(title = name, key = id.toString(), source = source))
			}
		}

		val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
			timeZone = TimeZone.getTimeZone("UTC")
		}
		val chaptersArr = json.optJSONArray("chapters")
		val chapters = if (chaptersArr != null) {
			val list = ArrayList<MangaChapter>(chaptersArr.length())
			for (i in 0 until chaptersArr.length()) {
				val c = chaptersArr.optJSONObject(i) ?: continue
				val id = c.optLong("id", 0L)
				val numStr = c.optString("chapter_number")
				val number = numStr.toFloatOrNull() ?: continue
				val chTitle = c.optString("title").ifBlank { "Chapter $numStr" }
				val chSlug = c.optString("slug").ifBlank { continue }
				val chUrl = "/chapter/$chSlug?id=$id"
				val date = isoFmt.parseSafe(c.optString("created_at"))
				list.add(
					MangaChapter(
						id = generateUid(chUrl),
						title = chTitle,
						url = chUrl,
						number = number,
						volume = 0,
						scanlator = null,
						uploadDate = date,
						branch = null,
						source = source,
					),
				)
			}
			list.sortedBy { it.number }
		} else {
			emptyList()
		}

		val isSafe = optBoolOrInt(json, "is_safe", true)

		return manga.copy(
			title = title,
			altTitles = setOfNotNull(altName),
			description = synopsis,
			coverUrl = cover ?: manga.coverUrl,
			state = state,
			authors = setOfNotNull(author),
			tags = if (tags.isEmpty()) manga.tags else tags,
			rating = rating,
			contentRating = if (isSafe) ContentRating.SAFE else ContentRating.SUGGESTIVE,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val id = chapter.url.substringAfter("?id=").substringBefore('&').ifBlank {
			throw IllegalStateException("Missing chapter id in url ${chapter.url}")
		}
		val arr = webClient.httpGet("$apiBase/chapters/$id/images", apiHeaders()).parseJsonArray()
		val pages = ArrayList<MangaPage>(arr.length())
		for (i in 0 until arr.length()) {
			val img = arr.optJSONObject(i) ?: continue
			val url = img.optString("image_path").ifBlank { continue }
			pages.add(
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				),
			)
		}
		return pages
	}

	private fun optBoolOrInt(jo: JSONObject, key: String, default: Boolean): Boolean {
		val v = jo.opt(key) ?: return default
		return when (v) {
			is Boolean -> v
			is Number -> v.toInt() != 0
			is String -> v.equals("true", true) || v == "1"
			else -> default
		}
	}

	private fun parseState(status: String?): MangaState? = when (status?.lowercase(Locale.ROOT)) {
		"ongoing" -> MangaState.ONGOING
		"completed", "tamat", "finished", "end" -> MangaState.FINISHED
		"hiatus", "paused" -> MangaState.PAUSED
		"dropped", "cancelled", "canceled" -> MangaState.ABANDONED
		else -> null
	}
}
