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
import java.util.*

@MangaSourceParser("SOFTKOMIK", "Softkomik", "id")
internal class Softkomik(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.SOFTKOMIK, pageSize = 20) {

	override val configKeyDomain = ConfigKey.Domain("softkomik.co")

	private val apiUrl = "https://v2.softdevices.my.id"
	private val coverCdn = "https://cover.softdevices.my.id/softkomik-cover"
	private val cdnUrls = listOf(
		"https://f1.softkomik.com/file/softkomik-image",
		"https://img.softdevices.my.id/softkomik-image",
		"https://image.softkomik.com/softkomik",
	)

	private var sessionToken: String? = null
	private var sessionSign: String? = null
	private var sessionExpiry: Long = 0

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.NEWEST,
		SortOrder.POPULARITY
	)

	override val filterCapabilities = MangaListFilterCapabilities(
		isMultipleTagsSupported = false,
		isTagsExclusionSupported = false,
		isSearchSupported = true,
		isSearchWithFiltersSupported = true
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
		return try {
			val url = if (!filter.query.isNullOrEmpty()) {
				// Search endpoint
				"$apiUrl/komik".toHttpUrl().newBuilder().apply {
					addQueryParameter("name", filter.query)
					addQueryParameter("search", "true")
					addQueryParameter("limit", "20")
					addQueryParameter("page", page.toString())
				}.build()
			} else {
				// Browse with sort
				"$apiUrl/komik".toHttpUrl().newBuilder().apply {
					addQueryParameter("limit", "20")
					addQueryParameter("page", page.toString())
					val sortBy = if (order == SortOrder.POPULARITY) "popular" else "newKomik"
					addQueryParameter("sortBy", sortBy)
				}.build()
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
					state = when (jo.optString("status")?.lowercase()) {
						"tamat" -> MangaState.FINISHED
						"ongoing" -> MangaState.ONGOING
						else -> null
					},
					authors = emptySet(),
					source = source,
					description = null
				))
			}
			return mangaList
		} catch (e: Exception) {
			emptyList()
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		return try {
			val detailUrl = "$apiUrl/komik/${manga.url}"
			val detailResponse = webClient.httpGet(detailUrl, getApiHeaders())
			val detailBody = detailResponse.body?.string() ?: return manga

			var description: String? = null
			var tags = emptySet<MangaTag>()
			var authors = emptySet<String>()
			var state = manga.state

			try {
				val detailJson = JSONObject(detailBody)
				val data = detailJson.optJSONObject("data")
				if (data != null) {
					description = data.optString("sinopsis").takeIf { it.isNotBlank() }
					state = when (data.optString("status")?.lowercase()) {
						"tamat" -> MangaState.FINISHED
						"ongoing" -> MangaState.ONGOING
						else -> manga.state
					}

					// Extract genres
					data.optJSONArray("Genre")?.let { arr ->
						tags = (0 until arr.length()).mapNotNull { i ->
							val name = arr.optString(i)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
							MangaTag(
								title = name,
								key = name.lowercase().replace(" ", "-").replace("'", ""),
								source = source
							)
						}.toSet()
					}

					// Extract author
					data.optString("author")?.takeIf { it.isNotBlank() }?.let {
						authors = setOf(it)
					}
				}
			} catch (e: Exception) {
				// Silently ignore JSON parse errors
			}

			// Fetch chapters
			val chapterUrl = "$apiUrl/komik/${manga.url}/chapter?limit=9999"
			val chapterResponse = webClient.httpGet(chapterUrl, getApiHeaders())
			val chapterBody = chapterResponse.body?.string() ?: ""
			val chaptersArray = try {
				JSONObject(chapterBody).optJSONArray("chapter") ?: JSONArray()
			} catch (e: Exception) {
				JSONArray()
			}

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

			manga.copy(
				description = description,
				tags = tags,
				authors = authors,
				state = state,
				chapters = chapters.sortedByDescending { it.number }
			)
		} catch (e: Exception) {
			manga
		}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		return try {
			val response = webClient.httpGet("https://$domain${chapter.url}")
			val html = response.body?.string() ?: return emptyList()

			// Try to extract __NEXT_DATA__ from HTML
			val nextDataStart = html.indexOf("<script id=\"__NEXT_DATA__\" type=\"application/json\">")
			if (nextDataStart != -1) {
				val dataStart = nextDataStart + "<script id=\"__NEXT_DATA__\" type=\"application/json\">".length
				val dataEnd = html.indexOf("</script>", dataStart)
				if (dataEnd != -1) {
					val nextDataStr = html.substring(dataStart, dataEnd)
					try {
						val props = JSONObject(nextDataStr)
							.getJSONObject("props")
							.getJSONObject("pageProps")

						// Data bisa di props.data atau langsung di props
						val data = props.optJSONObject("data") ?: props

						val images = data.optJSONArray("imageSrc") ?: return emptyList()
						val storageInter2 = data.optBoolean("storageInter2", false)

						val host = if (storageInter2) cdnUrls[2] else cdnUrls[0]

						val pages = mutableListOf<MangaPage>()
						for (j in 0 until images.length()) {
							val imgPath = images.getString(j).removePrefix("/")
							val fullUrl = "$host/$imgPath"
							pages.add(MangaPage(
								id = generateUid(fullUrl),
								url = fullUrl,
								preview = null,
								source = source
							))
						}
						if (pages.isNotEmpty()) {
							return pages
						}
					} catch (e: Exception) {
						// Fall through to HTML parsing
					}
				}
			}

			// Fallback: Parse images from HTML
			return parseImagesFromHtml(html)
		} catch (e: Exception) {
			emptyList()
		}
	}

	private fun parseImagesFromHtml(html: String): List<MangaPage> {
		val pages = mutableListOf<MangaPage>()
		val patterns = listOf(
			Regex("""https://f1\.softkomik\.com/file/softkomik-image/[^"'\s<>]+"""),
			Regex("""https://img\.softdevices\.my\.id/softkomik-image/[^"'\s<>]+"""),
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
