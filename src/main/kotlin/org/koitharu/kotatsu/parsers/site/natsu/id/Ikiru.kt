package org.koitharu.kotatsu.parsers.site.natsu.id

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
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

@MangaSourceParser("IKIRU", "Ikiru", "id")
internal class Ikiru(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.IKIRU, pageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain(
		"09.ikiru.wtf",
		"08.ikiru.wtf",
		"07.ikiru.wtf",
		"06.ikiru.wtf",
		"ikiru.wtf",
	)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
		)

	override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
		.add("Accept", "application/json, text/plain, */*")
		.add("Referer", "https://$domain/")
		.build()

	override suspend fun getFavicons(): Favicons = Favicons.single("https://$domain/favicon-32x32.png")

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = fetchGenres(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA),
	)

	private suspend fun fetchGenres(): Set<MangaTag> {
		val data = webClient.httpGet("https://$domain/api/user/genres", getRequestHeaders())
			.parseJson().optJSONObject("data") ?: return emptySet()
		val array = data.optJSONArray("allGenres") ?: return emptySet()
		val result = LinkedHashSet<MangaTag>()
		for (i in 0 until array.length()) {
			val item = array.optJSONObject(i) ?: continue
			val slug = item.optString("slug").takeIf { it.isNotBlank() } ?: continue
			val name = item.optString("name").takeIf { it.isNotBlank() } ?: slug
			result += MangaTag(title = name.toTitleCase(), key = slug, source = source)
		}
		return result
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://$domain/api/public/library/search?page=").append(page)
			append("&limit=").append(pageSize)
			append("&order=").append(if (order == SortOrder.POPULARITY) "popular" else "latest")
			filter.query?.takeIf { it.isNotBlank() }?.let { append("&search=").append(it.urlEncoded()) }
			if (filter.tags.isNotEmpty()) {
				append("&genres=").append(filter.tags.joinToString(",") { it.key })
			}
			filter.states.oneOrThrowIfMany()?.let { state ->
				when (state) {
					MangaState.ONGOING -> append("&status=ONGOING")
					MangaState.FINISHED -> append("&status=COMPLETED")
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
		val data = webClient.httpGet(url, getRequestHeaders()).parseJson().optJSONObject("data")
			?: return emptyList()
		return parseList(data.optJSONArray("mangas"))
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
				url = "/manga/$slug",
				publicUrl = "https://$domain/manga/$slug",
				coverUrl = item.getStringOrNull("featuredImage"),
				largeCoverUrl = null,
				rating = RATING_UNKNOWN,
				contentRating = null,
				tags = parseGenres(item.optJSONObject("metadata")?.optJSONArray("genre")),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
		return result
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.substringAfterLast("/manga/").trim('/').substringBefore('/')
		val data = webClient.httpGet("https://$domain/api/public/manga/$slug", getRequestHeaders())
			.parseJson().getJSONObject("data")
		val metadata = data.optJSONObject("metadata")
		val chapters = ArrayList<MangaChapter>()
		data.optJSONObject("chapters")?.optJSONArray("chapters")?.let { array ->
			for (i in 0 until array.length()) {
				val item = array.optJSONObject(i) ?: continue
				val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
				val number = item.optString("number").toFloatOrNull() ?: continue
				val chapterTitle = item.optString("title").takeIf { it.isNotBlank() }
					?: "Chapter ${formatNumber(number)}"
				chapters += MangaChapter(
					id = generateUid(id),
					title = chapterTitle,
					number = number,
					volume = 0,
					url = "/manga/$slug/chapter-${formatNumber(number)}",
					scanlator = null,
					uploadDate = parseDate(
						item.getStringOrNull("createdAt") ?: item.getStringOrNull("updatedAt"),
					),
					branch = null,
					source = source,
				)
			}
		}
		return manga.copy(
			title = data.optString("title").takeIf { it.isNotBlank() } ?: manga.title,
			description = Jsoup.parse(data.optString("description")).text().takeIf { it.isNotBlank() } ?: "",
			coverUrl = data.getStringOrNull("featuredImage") ?: manga.coverUrl,
			largeCoverUrl = data.getStringOrNull("backgroundImage"),
			altTitles = metadata?.optJSONArray("alternateTitles")?.toStringList() ?: manga.altTitles,
			tags = parseGenres(metadata?.optJSONArray("genre")).ifEmpty { manga.tags },
			authors = setOfNotNull(metadata?.getStringOrNull("author")),
			state = parseState(data.getStringOrNull("status")) ?: manga.state,
			contentRating = if (data.optBoolean("isAdult", false)) ContentRating.ADULT else manga.contentRating,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val slug = chapter.url.substringAfterLast("/manga/").substringBefore("/chapter")
		val number = formatNumber(chapter.number)
		val payload = webClient.httpGet(
			"https://$domain/manga/$slug/chapter-$number/_payload.json",
			getRequestHeaders(),
		).parseRaw()
		val array = JSONArray(payload)
		val needle = "/h/$slug/$number/"
		val result = ArrayList<MangaPage>()
		for (i in 0 until array.length()) {
			val value = array.opt(i)
			if (value is String && value.startsWith("http") && needle in value) {
				result += MangaPage(
					id = generateUid(value),
					url = value,
					preview = null,
					source = source,
				)
			}
		}
		return result.distinctBy { it.id }
	}

	private fun parseGenres(array: JSONArray?): Set<MangaTag> {
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
		"COMPLETED", "FINISHED" -> MangaState.FINISHED
		"HIATUS" -> MangaState.PAUSED
		"DROP", "DROPPED" -> MangaState.ABANDONED
		else -> null
	}

	private fun formatNumber(number: Float): String =
		if (number % 1f == 0f) number.toInt().toString() else number.toString()

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