package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.jsoup.nodes.Document
import java.util.*

@MangaSourceParser("SOFTKOMIK", "Softkomik", "id")
internal class Softkomik(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.SOFTKOMIK, 20) {

	override val configKeyDomain = ConfigKey.Domain("softkomik.co")

	private val apiUrl = "https://v2.softdevices.my.id"
	private val coverCdn = "https://cover.softdevices.my.id/softkomik-cover"

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.NEWEST,
		SortOrder.POPULARITY
	)

	override val filterCapabilities = MangaListFilterCapabilities(
		isMultipleTagsSupported = false,
		isTagsExclusionSupported = false,
		isSearchSupported = true,
		isSearchWithFiltersSupported = false
	)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		return MangaListFilterOptions()
	}

	private fun getApiHeaders(): Headers {
		return Headers.Builder()
			.add("Accept", "application/json")
			.add("Referer", "https://$domain/")
			.add("Origin", "https://$domain")
			.add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
			.build()
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = if (!filter.query.isNullOrEmpty()) {
			"$apiUrl/komik?name=${filter.query.urlEncoded()}&search=true&limit=20&page=$page"
		} else {
			val sortBy = if (order == SortOrder.POPULARITY) "popular" else "newKomik"
			"$apiUrl/komik?limit=20&page=$page&sortBy=$sortBy"
		}

		val response = webClient.httpGet(url, getApiHeaders())
		val body = response.body?.string() ?: return emptyList()
		val json = JSONObject(body)
		val data = json.optJSONArray("data") ?: return emptyList()

		val mangaList = mutableListOf<Manga>()
		for (i in 0 until data.length()) {
			val jo = data.getJSONObject(i)
			val slug = jo.getString("title_slug")
			val img = jo.getString("gambar").removePrefix("/")

			mangaList.add(Manga(
				id = generateUid(slug),
				title = jo.getString("title").trim(),
				altTitles = emptySet(),
				url = slug,
				publicUrl = "https://$domain/komik/$slug",
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = "$coverCdn/$img",
				largeCoverUrl = "$coverCdn/$img",
				tags = emptySet(),
				state = if (jo.optString("status") == "tamat") MangaState.FINISHED else MangaState.ONGOING,
				authors = emptySet(),
				source = source,
				description = null
			))
		}
		return mangaList
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val detailUrl = "$apiUrl/komik/${manga.url}"
		val detailResponse = webClient.httpGet(detailUrl, getApiHeaders())
		val detailBody = detailResponse.body?.string()

		var description: String? = null
		var tags = emptySet<MangaTag>()
		var authors = emptySet<String>()
		var state = manga.state

		if (!detailBody.isNullOrEmpty()) {
			try {
				val detailJson = JSONObject(detailBody)
				val data = detailJson.optJSONObject("data")
				if (data != null) {
					description = data.optString("sinopsis").takeIf { it.isNotBlank() }
					state = when (data.optString("status")) {
						"tamat" -> MangaState.FINISHED
						"ongoing" -> MangaState.ONGOING
						else -> manga.state
					}

					data.optJSONArray("genre")?.let { arr ->
						tags = (0 until arr.length()).mapNotNull { i ->
							val name = arr.optString(i)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
							MangaTag(
								title = name,
								key = name.lowercase().replace(" ", "-").replace("'", ""),
								source = source
							)
						}.toSet()
					}

					data.optString("author")?.takeIf { it.isNotBlank() }?.let {
						authors = setOf(it)
					}
				}
			} catch (e: Exception) {
			}
		}

		val chapterUrl = "$apiUrl/komik/${manga.url}/chapter?limit=9999"
		val chapterResponse = webClient.httpGet(chapterUrl, getApiHeaders())
		val chapterBody = chapterResponse.body?.string() ?: ""
		val chaptersArray = JSONObject(chapterBody).optJSONArray("chapter") ?: JSONArray()

		val chapters = mutableListOf<MangaChapter>()
		for (i in 0 until chaptersArray.length()) {
			val ch = chaptersArray.getJSONObject(i)
			val chNum = ch.getString("chapter")
			val chTitle = ch.optString("chapter_title").takeIf { it.isNotBlank() } ?: "Chapter $chNum"
			val chUrl = "/komik/${manga.url}/chapter/$chNum"

			chapters.add(MangaChapter(
				id = generateUid(chUrl),
				title = chTitle,
				url = chUrl,
				number = chNum.toFloatOrNull() ?: 0f,
				scanlator = null,
				branch = null,
				source = source,
				volume = 0,
				uploadDate = 0L
			))
		}

		return manga.copy(
			description = description,
			tags = tags,
			authors = authors,
			state = state,
			chapters = chapters.sortedByDescending { it.number }
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val response = webClient.httpGet("https://$domain${chapter.url}")
		val html = response.body?.string() ?: return emptyList()

		val nextDataStr = html.substringAfter("<script id=\"__NEXT_DATA__\" type=\"application/json\">")
			.substringBefore("</script>")

		if (nextDataStr == html) {
			return parseImagesFromHtml(html)
		}

		val props = JSONObject(nextDataStr)
			.getJSONObject("props")
			.getJSONObject("pageProps")

		// Data bisa di props.data atau langsung di props
		val data = props.optJSONObject("data") ?: props

		val images = data.optJSONArray("imageSrc") ?: return emptyList()
		val storage2 = data.optBoolean("storageInter2", false)

		// Host gambar berdasarkan flag storageInter2
		val host = if (storage2) {
			"https://image.softkomik.com/softkomik"
		} else {
			"https://f1.softkomik.com/file/softkomik-image"
		}

		val pages = mutableListOf<MangaPage>()
		for (i in 0 until images.length()) {
			val imgPath = images.getString(i).removePrefix("/")
			val fullUrl = "$host/$imgPath"
			pages.add(MangaPage(
				id = generateUid(fullUrl),
				url = fullUrl,
				preview = null,
				source = source
			))
		}
		return pages
	}

	private fun parseImagesFromHtml(html: String): List<MangaPage> {
		val pages = mutableListOf<MangaPage>()
		val patterns = listOf(
			Regex("""https://f1\.softkomik\.com/file/softkomik-image/[^"'\s<>]+"""),
			Regex("""https://image\.softkomik\.com/softkomik/[^"'\s<>]+"""),
			Regex("""https://[^"'\s<>]*\.softdevices\.my\.id/[^"'\s<>]+\.(?:jpg|jpeg|png|webp)""")
		)

		val foundUrls = mutableSetOf<String>()
		for (pattern in patterns) {
			pattern.findAll(html).forEach { match ->
				val url = match.value
				if (url !in foundUrls) {
					foundUrls.add(url)
					pages.add(MangaPage(
						id = generateUid(url),
						url = url,
						preview = null,
						source = source
					))
				}
			}
		}

		return pages
	}
}
