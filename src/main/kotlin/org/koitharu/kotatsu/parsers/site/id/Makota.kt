package org.koitharu.kotatsu.parsers.site.id

import org.json.JSONArray
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.util.*

@MangaSourceParser("MAKOTA", "Makota", "id")
internal class Makota(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MAKOTA, pageSize = 100) {

	override val configKeyDomain = ConfigKey.Domain("v1.makota.asia")

	override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_MOBILE)

	override val sourceLocale: Locale = Locale("id")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	init {
		// The site paginates `/latest?page=1`, `?page=2`, ...
		paginator.firstPage = 1
		searchPaginator.firstPage = 1
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

	override val filterCapabilities = MangaListFilterCapabilities(
		isMultipleTagsSupported = false,
		isSearchSupported = true,
		isSearchWithFiltersSupported = false, // /api/search doesn't accept extra filters
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = emptySet(), // no listable genre index; genres appear as free-form chips only
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	// ----------------------------------------------------------------------
	// LIST + SEARCH
	// ----------------------------------------------------------------------

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query.orEmpty().trim()

		// /api/search only accepts q >= 2 chars; below that we drop into the
		// regular /latest catalog.
		return if (query.length >= 2) {
			searchApi(query, page, filter.states.oneOrThrowIfMany())
		} else {
			listLatest(page, filter.states.oneOrThrowIfMany())
		}
	}

	private suspend fun listLatest(page: Int, stateFilter: MangaState?): List<Manga> {
		val url = "https://$domain/latest?page=$page"
		val doc = webClient.httpGet(url).parseHtml()

		// Every card has an <a href="/manga/<slug>"> and a chapter chip <a
		// href="/manga/<slug>">Read Now</a>. We only want the outer card
		// container so we can pick up the cover + latest-chapter label.
		val cardSelector = "div.grid > *:has(a[href^=\"/manga/\"])"
		val cards = doc.select(cardSelector).ifEmpty {
			// Older layout fallback
			doc.select("a[href^=\"/manga/\"]").mapNotNull { a -> a.parent() }
		}

		val result = ArrayList<Manga>(cards.size)
		val seenSlugs = HashSet<String>()
		for (card in cards) {
			val link = card.selectFirst("a[href^=\"/manga/\"]") ?: continue
			val href = link.attr("href").substringBefore('?').trimEnd('/')
			val slug = href.removePrefix("/manga/").takeIf { it.isNotEmpty() } ?: continue
			if (!seenSlugs.add(slug)) continue

			val img = card.selectFirst("img")
			val title = img?.attr("alt")?.trim().orEmpty().ifEmpty {
				link.text().trim()
			}
			val cover = img?.let { extractMediaUrl(it) }.orEmpty()

			result.add(
				Manga(
					id = generateUid(href),
					url = href,
					publicUrl = "https://$domain$href",
					title = title,
					altTitles = emptySet(),
					coverUrl = cover,
					rating = RATING_UNKNOWN,
					tags = emptySet(),
					authors = emptySet(),
					state = null,
					source = source,
					contentRating = if (isNsfwSource) ContentRating.ADULT else null,
				),
			)
		}

		// Client-side state filter (the /latest endpoint doesn't accept it).
		return if (stateFilter != null) {
			result.filter { it.state == null || it.state == stateFilter }
		} else {
			result
		}
	}

	private suspend fun searchApi(query: String, page: Int, stateFilter: MangaState?): List<Manga> {
		// /api/search returns everything at once (capped at &limit=), so we
		// slice the result client-side to satisfy Kotatsu's paging contract.
		val limit = 200
		val url = "https://$domain/api/search?q=${query.urlEncoded()}&limit=$limit"
		val json = webClient.httpGet(url).parseJson()
		val results = json.optJSONArray("results") ?: return emptyList()

		val all = ArrayList<Manga>(results.length())
		for (i in 0 until results.length()) {
			val item = results.getJSONObject(i)
			val slug = item.optString("slug").ifBlank { continue }
			val itemState = parseNumericState(item.optInt("status", -1))
			if (stateFilter != null && itemState != null && itemState != stateFilter) continue

			val href = "/manga/$slug"
			val genresJson = item.optJSONArray("genre")
			val tags: Set<MangaTag> = if (genresJson != null) {
				buildGenreTagSet(genresJson)
			} else {
				emptySet()
			}
			val rating = item.optString("rating")
			val author = item.optString("author").takeIf { it.isNotBlank() }
			val cover = item.optString("localThumbnail").ifBlank { item.optString("thumbnail") }

			all.add(
				Manga(
					id = generateUid(href),
					url = href,
					publicUrl = "https://$domain$href",
					title = item.optString("title"),
					altTitles = emptySet(),
					coverUrl = absoluteMediaUrl(cover),
					rating = RATING_UNKNOWN,
					tags = tags,
					authors = setOfNotNull(author),
					state = itemState,
					source = source,
					contentRating = when {
						isAdultRating(rating) -> ContentRating.ADULT
						isNsfwSource -> ContentRating.ADULT
						else -> null
					},
				),
			)
		}

		val from = (page - 1) * pageSize
		if (from >= all.size) return emptyList()
		return all.subList(from, minOf(from + pageSize, all.size)).toList()
	}

	private fun buildGenreTagSet(json: JSONArray): Set<MangaTag> {
		val set = LinkedHashSet<MangaTag>(json.length())
		for (i in 0 until json.length()) {
			val raw = json.optString(i).trim().ifEmpty { continue }
			val slug = raw.lowercase(sourceLocale).replace(' ', '-')
			set.add(
				MangaTag(
					key = slug,
					title = raw.toTitleCase(sourceLocale),
					source = source,
				),
			)
		}
		return set
	}

	// ----------------------------------------------------------------------
	// DETAILS
	// ----------------------------------------------------------------------

	override suspend fun getDetails(manga: Manga): Manga {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()

		val title = doc.selectFirst("h1")?.text()?.trim() ?: manga.title

		// Author sits under the H1 as <p>By <span>Author</span></p>.
		val author = doc.selectFirst("h1 ~ p span, h1 + p span")?.text()?.trim()
			?.takeIf { it.isNotBlank() }

		// Genres are chip <span> nodes in the "mb-5 flex flex-wrap gap-2" row
		// which sits between the stats row and the synopsis.
		val genreChips = doc.select("div.mb-5.flex.flex-wrap.gap-2 > span")
		val tags = LinkedHashSet<MangaTag>(genreChips.size)
		for (chip in genreChips) {
			val name = chip.text().trim().ifEmpty { continue }
			tags.add(
				MangaTag(
					key = name.lowercase(sourceLocale).replace(' ', '-'),
					title = name.toTitleCase(sourceLocale),
					source = source,
				),
			)
		}

		// The status pill lives in the first pill row on the page. Look at all
		// short badge spans and match against known labels.
		val statusText = doc.select("span.rounded")
			.map { it.text().trim() }
			.firstOrNull { it.equals("Ongoing", true) || it.equals("Completed", true) || it.equals("Hiatus", true) }
			.orEmpty()

		val state = parseTextState(statusText)

		// Synopsis: first <p class="whitespace-pre-line ...">.
		val description = doc.selectFirst("p.whitespace-pre-line")?.html()

		// Cover comes from the top preload link or the first hero <img>.
		val cover = doc.selectFirst("link[rel=preload][as=image]")?.attr("href")
			?.let { absoluteMediaUrl(it) }
			?: doc.selectFirst("img[alt]")?.let { extractMediaUrl(it) }
			?: manga.coverUrl

		// Age-rating badge (e.g. "⭐ 13+", "17+"). The star is decorative.
		val ratingLabel = doc.select("span.rounded")
			.map { it.text().trim().removePrefix("⭐").trim() }
			.firstOrNull { it.matches(Regex("^\\d{1,2}\\+$")) }
			.orEmpty()

		val chapterAnchors = doc.select("div.max-h-80 a[href^=\"/read/\"]")
			.ifEmpty { doc.select("a[href^=\"/read/\"]") }
			.filter { !it.text().contains("Mulai Baca", ignoreCase = true) }

		val slug = manga.url.removePrefix("/manga/").trimEnd('/')
		val chapters = ArrayList<MangaChapter>(chapterAnchors.size)
		chapterAnchors.forEachIndexed { index, a ->
			val href = a.attr("href")
			// Chapter titles live in <span class="truncate text-sm font-medium ...">
			val chTitle = a.selectFirst("span.truncate")?.text()?.trim()
				?: a.ownText().trim().ifEmpty { "Chapter ${index + 1}" }
			// The URL slug is chapter-XXXX; use its digits as the canonical number.
			val padded = href.substringAfterLast('/')
			val number = padded.removePrefix("chapter-").toIntOrNull()?.toFloat()
				?: extractChapterNumber(chTitle)
				?: (index + 1f)
			chapters.add(
				MangaChapter(
					id = generateUid(href),
					title = chTitle,
					number = number,
					volume = 0,
					url = href,
					scanlator = null,
					uploadDate = 0L, // no upload date exposed on the page
					branch = null,
					source = source,
				),
			)
		}
		chapters.sortBy { it.number }

		return manga.copy(
			title = title,
			description = description,
			coverUrl = cover,
			tags = tags,
			state = state ?: manga.state,
			authors = setOfNotNull(author),
			chapters = chapters,
			contentRating = when {
				isAdultRating(ratingLabel) -> ContentRating.ADULT
				manga.contentRating != null -> manga.contentRating
				isNsfwSource -> ContentRating.ADULT
				else -> null
			},
		)
	}

	// ----------------------------------------------------------------------
	// PAGES
	// ----------------------------------------------------------------------

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val html = webClient.httpGet(fullUrl).parseHtml().html()

		// The reader HTML embeds a JSON array under `\"pages\":[...]` inside the
		// RSC payload. Extract it, unescape the backslash-quoted strings, and
		// resolve every URL against the site origin.
		val arrayJson = extractRawArray(html, "pages") ?: return emptyList()
		val jsonText = arrayJson.replace("\\\"", "\"").replace("\\/", "/")
		val arr = try {
			JSONArray(jsonText)
		} catch (_: Exception) {
			return emptyList()
		}

		return (0 until arr.length()).mapNotNull { i ->
			val raw = arr.optString(i).ifBlank { return@mapNotNull null }
			val absolute = if (raw.startsWith("http")) raw else "https://$domain$raw"
			MangaPage(
				id = generateUid(absolute),
				url = absolute,
				preview = null,
				source = source,
			)
		}
	}

	/**
	 * Best-effort extractor for a `\"<key>\":[ ... ]` array embedded in the
	 * RSC-serialized HTML payload. Matches until the balanced closing bracket.
	 */
	private fun extractRawArray(html: String, key: String): String? {
		val marker = "\\\"$key\\\":"
		val start = html.indexOf(marker)
		if (start < 0) return null
		val bracket = html.indexOf('[', start)
		if (bracket < 0) return null

		var depth = 0
		var i = bracket
		while (i < html.length) {
			val c = html[i]
			when (c) {
				'[' -> depth++
				']' -> {
					depth--
					if (depth == 0) return html.substring(bracket, i + 1)
				}
			}
			i++
		}
		return null
	}

	// ----------------------------------------------------------------------
	// Helpers
	// ----------------------------------------------------------------------

	/**
	 * Extracts a real `/api/media?...` URL from either the img's `src` or the
	 * next.js image loader's `srcset`. Returns absolute or an empty string.
	 */
	private fun extractMediaUrl(img: org.jsoup.nodes.Element): String {
		// Prefer explicit src, fall back to srcset first candidate.
		val direct = img.attr("src").takeIf { it.isNotBlank() }
		val fromSrcset = img.attr("srcset").split(',').firstOrNull()
			?.trim()?.substringBefore(' ')
			?.takeIf { it.isNotBlank() }

		val raw = direct ?: fromSrcset ?: return ""
		// /_next/image?url=%2Fapi%2Fmedia%3F... unwraps to /api/media?...
		if (raw.contains("/_next/image")) {
			val urlParam = raw.substringAfter("url=").substringBefore('&')
			return absoluteMediaUrl(java.net.URLDecoder.decode(urlParam, "UTF-8"))
		}
		return absoluteMediaUrl(raw)
	}

	private fun absoluteMediaUrl(path: String): String = when {
		path.isBlank() -> ""
		path.startsWith("http") -> path
		path.startsWith("/") -> "https://$domain$path"
		else -> "https://$domain/$path"
	}

	private fun parseNumericState(status: Int): MangaState? = when (status) {
		1 -> MangaState.ONGOING
		2 -> MangaState.FINISHED
		else -> null
	}

	private fun parseTextState(raw: String): MangaState? = when (raw.trim().lowercase(sourceLocale)) {
		"ongoing", "on-going", "on going" -> MangaState.ONGOING
		"completed", "finished", "end", "tamat" -> MangaState.FINISHED
		"hiatus", "paused" -> MangaState.PAUSED
		else -> null
	}

	/**
	 * `17+` and `19+` are the platform's mature buckets. `13+`/`15+` are
	 * treated as SFW.
	 */
	private fun isAdultRating(raw: String?): Boolean {
		val label = raw?.removePrefix("⭐")?.trim().orEmpty()
		return label == "17+" || label == "19+" || label == "21+"
	}

	private fun extractChapterNumber(title: String): Float? {
		return Regex("""\d+(?:[.,]\d+)?""").find(title)
			?.value?.replace(',', '.')?.toFloatOrNull()
	}
}
