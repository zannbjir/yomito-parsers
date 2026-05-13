package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.json.JSONArray
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("KAIKOMIK", "Kaikomik", "id")
internal class Kaikomik(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.KAIKOMIK, 20) {

	override val configKeyDomain = ConfigKey.Domain("kaikomik.my.id")

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36")
		.add("Referer", "https://$domain/")
		.add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED, SortOrder.NEWEST, SortOrder.POPULARITY, SortOrder.ALPHABETICAL
	)

	override val filterCapabilities = MangaListFilterCapabilities(
		isSearchSupported = true,
		isMultipleTagsSupported = false,
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = setOf(
			"Action", "Adult", "Adventure", "Comedy", "Drama", "Ecchi",
			"Fantasy", "Gender Bender", "Gore", "Harem", "Historical",
			"Horor", "Isekai", "Josei", "Magic", "Martial Arts", "Mature",
			"Mystery", "One-shot", "Psychological", "Reincarnation",
			"Romance", "School Life", "Sci-fi", "Seinen", "Shoujo",
			"Shounen", "Slice of Life", "Supernatural", "Survival",
			"Thriller", "Time Travel", "Tragedy", "Villainess", "Yuri"
		).map { MangaTag(it, it, source) }.toSet(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {

		if (!filter.query.isNullOrEmpty()) {
			return searchManga(filter.query)
		}

		val sortParam = when (order) {
			SortOrder.UPDATED -> "updatedAt"
			SortOrder.NEWEST -> "createdAt"
			SortOrder.ALPHABETICAL -> "title"
			SortOrder.POPULARITY -> "views"
			else -> "updatedAt"
		}

		val genreParams = if (filter.tags.isNotEmpty()) {
			filter.tags.joinToString("") { "&genres=${it.key}" }
		} else {
			""
		}

		val url = "https://$domain/comics?sort=$sortParam&page=$page$genreParams"
		val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()

		return parseMangaListFromHtml(doc)
	}

	private suspend fun searchManga(query: String): List<Manga> {
		val url = "https://$domain/api/search?q=${query.urlEncoded()}"
		return try {
			val response = webClient.httpGet(url, getRequestHeaders())
			val jsonArray = response.parseJsonArray()
			val results = mutableListOf<Manga>()

			for (i in 0 until jsonArray.length()) {
				val item = jsonArray.optJSONObject(i) ?: continue
				val mangaId = item.getStringOrNull("_id") ?: continue
				val title = item.getStringOrNull("title") ?: continue

				// Cover can be in 'cover' or 'image' field
				var coverUrl = item.getStringOrNull("cover")
					?: item.getStringOrNull("image")
					?: ""

				// If cover URL uses wsrv.nl proxy, extract the actual URL
				if (coverUrl.contains("wsrv.nl/?url=")) {
					coverUrl = coverUrl.substringAfter("wsrv.nl/?url=")
						.substringBefore("&")
						.substringBefore("?")
					// Decode URL-encoded characters
					coverUrl = java.net.URLDecoder.decode(coverUrl, "UTF-8")
				}

				results.add(
					Manga(
						id = generateUid("/manga/$mangaId"),
						title = title,
						altTitles = emptySet(),
						url = "/manga/$mangaId",
						publicUrl = "https://$domain/manga/$mangaId",
						coverUrl = coverUrl,
						largeCoverUrl = null,
						rating = RATING_UNKNOWN,
						contentRating = null,
						tags = emptySet(),
						state = null,
						authors = emptySet(),
						source = source,
					)
				)
			}
			results
		} catch (e: Exception) {
			// Fallback: search via comics page
			try {
				val doc = webClient.httpGet("https://$domain/comics?q=${query.urlEncoded()}", getRequestHeaders()).parseHtml()
				parseMangaListFromHtml(doc)
			} catch (e2: Exception) {
				emptyList()
			}
		}
	}

	private fun parseMangaListFromHtml(doc: org.jsoup.nodes.Document): List<Manga> {
		val mangaList = mutableListOf<Manga>()
		val seen = mutableSetOf<String>()

		// Kaikomik /comics page has manga cards with onclick="window.location.href='/manga/{id}'"
		doc.select("[onclick*='/manga/']").forEach { el ->
			val onclick = el.attr("onclick")
			val mangaPath = onclick.substringAfter("='").substringBefore("'")
			if (mangaPath.isBlank() || mangaPath in seen) return@forEach
			seen.add(mangaPath)

			val title = el.selectFirst("h2, h3, [class*=title], .font-bold")?.text()?.trim()
				?: el.selectFirst("span")?.text()?.trim()
				?: return@forEach

			var coverUrl = el.selectFirst("img")?.let { img ->
				img.attr("src").ifBlank {
					img.attr("data-src").ifBlank {
						img.attr("data-lazy-src")
					}
				}
			}?.ifBlank { null } ?: ""

			// Clean up wsrv.nl proxy URLs to get the actual image URL
			if (coverUrl.contains("wsrv.nl/?url=")) {
				val actualUrl = coverUrl.substringAfter("wsrv.nl/?url=")
					.substringBefore("&")
					.substringBefore("?")
				coverUrl = java.net.URLDecoder.decode(actualUrl, "UTF-8")
			}

			mangaList.add(
				Manga(
					id = generateUid(mangaPath),
					title = title,
					altTitles = emptySet(),
					url = mangaPath,
					publicUrl = "https://$domain$mangaPath",
					coverUrl = coverUrl,
					largeCoverUrl = null,
					rating = RATING_UNKNOWN,
					contentRating = null,
					tags = emptySet(),
					state = null,
					authors = emptySet(),
					source = source,
				)
			)
		}

		// Also try standard <a href="/manga/{id}"> pattern
		if (mangaList.isEmpty()) {
			doc.select("a[href*='/manga/']").forEach { a ->
				val href = a.attrAsRelativeUrlOrNull("href") ?: return@forEach
				if (!href.startsWith("/manga/") || href in seen) return@forEach
				seen.add(href)

				val title = a.selectFirst("h2, h3, [class*=title], .font-bold")?.text()?.trim()
					?: a.text()?.trim()?.ifBlank { null }
					?: return@forEach

				var coverUrl = a.selectFirst("img")?.let { img ->
					img.attr("src").ifBlank {
						img.attr("data-src").ifBlank {
							img.attr("data-lazy-src")
						}
					}
				}?.ifBlank { null } ?: ""

				if (coverUrl.contains("wsrv.nl/?url=")) {
					val actualUrl = coverUrl.substringAfter("wsrv.nl/?url=")
						.substringBefore("&")
						.substringBefore("?")
					coverUrl = java.net.URLDecoder.decode(actualUrl, "UTF-8")
				}

				mangaList.add(
					Manga(
						id = generateUid(href),
						title = title,
						altTitles = emptySet(),
						url = href,
						publicUrl = a.attrAsAbsoluteUrl("href"),
						coverUrl = coverUrl,
						largeCoverUrl = null,
						rating = RATING_UNKNOWN,
						contentRating = null,
						tags = emptySet(),
						state = null,
						authors = emptySet(),
						source = source,
					)
				)
			}
		}

		return mangaList
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()

		val title = doc.selectFirst("h1")?.text()?.trim() ?: manga.title
		val description = doc.selectFirst("#displayDesc, [class*=synopsis] p, [class*=desc] p")?.text()?.trim()
		val tags = doc.select("a[href*='genres='], .genre-badge").mapNotNullToSet { a ->
			val tagTitle = a.text().trim()
			if (tagTitle.isNotBlank()) {
				MangaTag(title = tagTitle, key = tagTitle.lowercase(), source = source)
			} else null
		}

		// Detail rows: Status, Format/Type, Author, etc.
		var state: MangaState? = null
		var author: String? = null
		val detailRows = doc.select(".detail-row, [class*=detail-row]")
		for (row in detailRows) {
			val label = row.selectFirst(".detail-label")?.text()?.trim()?.lowercase() ?: continue
			val value = row.selectFirst(".detail-value")?.text()?.trim() ?: continue

			when {
				label.contains("status") -> {
					state = when {
						value.contains("ongoing", ignoreCase = true) ||
						value.contains("berjalan", ignoreCase = true) -> MangaState.ONGOING
						value.contains("completed", ignoreCase = true) ||
						value.contains("tamat", ignoreCase = true) -> MangaState.FINISHED
						value.contains("hiatus", ignoreCase = true) -> MangaState.PAUSED
						else -> null
					}
				}
				label.contains("author") -> {
					author = value
				}
			}
		}

		var coverUrl = doc.selectFirst("img[src*='cloudinary'], img[src*='imgbox']")?.let { img ->
			img.attr("src").ifBlank { img.attr("data-src") }
		}?.ifBlank { null } ?: manga.coverUrl

		// Clean up wsrv.nl proxy URLs
		if (coverUrl.contains("wsrv.nl/?url=")) {
			val actualUrl = coverUrl.substringAfter("wsrv.nl/?url=")
				.substringBefore("&")
				.substringBefore("?")
			coverUrl = java.net.URLDecoder.decode(actualUrl, "UTF-8")
		}

		// Also try og:image meta tag for cover
		if (coverUrl.isBlank() || coverUrl == manga.coverUrl) {
			doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { ogImage ->
				if (ogImage.isNotBlank()) {
					coverUrl = if (ogImage.contains("wsrv.nl/?url=")) {
						val actualUrl = ogImage.substringAfter("wsrv.nl/?url=")
							.substringBefore("&")
							.substringBefore("?")
						java.net.URLDecoder.decode(actualUrl, "UTF-8")
					} else {
						ogImage
					}
				}
			}
		}

		val chapters = doc.select("a[href*='/read/']").mapChapters(reversed = true) { index, el ->
			val chUrl = el.attrAsRelativeUrlOrNull("href") ?: return@mapChapters null
			val dataNum = el.attr("data-num").toFloatOrNull()
			val chTitle = el.selectFirst("span, .font-black, [class*=chapter]")?.text()?.trim()
				?: el.text()?.trim()
				?: "Chapter ${index + 1}"

			val chNum = dataNum
				?: chTitle.substringAfterLast(" ").toFloatOrNull()
				?: (index + 1f)

			MangaChapter(
				id = generateUid(chUrl),
				title = chTitle,
				url = chUrl,
				number = chNum,
				volume = 0,
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				source = source,
			)
		}

		return manga.copy(
			title = title,
			description = description,
			tags = tags,
			state = state,
			authors = setOfNotNull(author),
			coverUrl = coverUrl,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain), getRequestHeaders()).parseHtml()

		return doc.select("img[src*='westmanga'], img[src*='imgbox'], img[src*='cloudinary'], " +
			"img[src*='.webp'], img[src*='.jpg'], img[src*='.png']")
			.filter { img ->
				val src = img.attr("src").lowercase()
				// Include manga page images
				(src.contains("chapter") || src.contains("westmanga") ||
					src.contains("imgbox") || src.contains("cloudinary")) &&
				// Exclude UI images
				!src.contains("avatar") && !src.contains("logo") &&
				!src.contains("icon") && !src.contains("badge") &&
				!src.contains("flag") && !src.contains("favicon")
			}
			.mapNotNull { img ->
				val url = img.attr("src").ifBlank {
					img.attr("data-src").ifBlank {
						img.attr("data-lazy-src")
					}
				}.trim()
				if (url.isNotBlank() && url.startsWith("http")) {
					MangaPage(
						id = generateUid(url),
						url = url,
						preview = null,
						source = source,
					)
				} else null
			}
	}
}
