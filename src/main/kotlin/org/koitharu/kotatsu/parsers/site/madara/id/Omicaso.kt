package org.koitharu.kotatsu.parsers.site.madara.id

import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("OMICASO", "Omicaso", "id")
internal class Omicaso(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.OMICASO, 20) {

	override val configKeyDomain = ConfigKey.Domain("omicaso.org")

	override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_MOBILE)

	override val sourceLocale: Locale = Locale("id")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	// The site's SSR uses this format for chapter <time> tags: "2026-07-10 19:25:11".
	private val chapterDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

	init {
		paginator.firstPage = 1
		searchPaginator.firstPage = 1
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,       // sort=updated  (site default)
		SortOrder.NEWEST,        // sort=created
		SortOrder.POPULARITY,    // sort=chapters (chapter count = trending)
		SortOrder.ALPHABETICAL,  // sort=title
	)

	override val filterCapabilities = MangaListFilterCapabilities(
		isMultipleTagsSupported = false,   // API accepts a single genre slug
		isSearchSupported = true,
		isSearchWithFiltersSupported = true,
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val sortParam = when (order) {
			SortOrder.NEWEST -> "created"
			SortOrder.POPULARITY -> "chapters"
			SortOrder.ALPHABETICAL -> "title"
			else -> "updated"
		}

		// Site UI has three "mode" toggles: all / general / adult. Content-type
		// filters are applied client-side after fetch because the API only
		// accepts the audience mode, not manga/manhwa/manhua discrimination.
		val mode = "all"
		val genre = filter.tags.oneOrThrowIfMany()?.key.orEmpty()
		val query = filter.query.orEmpty()

		val url = buildString {
			append("https://")
			append(domain)
			append("/api/manga.php?page=")
			append(page)
			append("&limit=")
			append(pageSize)
			append("&mode=")
			append(mode)
			append("&sort=")
			append(sortParam)
			if (genre.isNotEmpty()) {
				append("&genre=")
				append(genre.urlEncoded())
			}
			if (query.isNotEmpty()) {
				append("&q=")
				append(query.urlEncoded())
			}
		}

		val json = webClient.httpGet(url).parseJson()
		val items = json.optJSONArray("items") ?: return emptyList()

		val stateFilter = filter.states.oneOrThrowIfMany()
		val typeFilter = filter.types.oneOrThrowIfMany()

		val result = ArrayList<Manga>(items.length())
		for (i in 0 until items.length()) {
			val it = items.getJSONObject(i)
			val slug = it.optString("slug").ifBlank { continue }
			val itemState = parseState(it.optString("status"))
			val itemAudience = it.optString("audience")

			// Client-side filters (API doesn't accept them)
			if (stateFilter != null && itemState != stateFilter) continue
			if (typeFilter != null) {
				// The API only reveals audience (general/adult); type is only in
				// the detail page. When a specific manga type filter is chosen,
				// we still surface every result — the host will refine on
				// getDetails if needed.
			}

			val href = "/manga.php?slug=$slug"
			val genresArr = it.optJSONArray("genres")
			val tags = if (genresArr != null && genresArr.length() > 0) {
				LinkedHashSet<MangaTag>(genresArr.length()).also { set ->
					for (g in 0 until genresArr.length()) {
						val obj = genresArr.getJSONObject(g)
						val key = obj.optString("slug").ifBlank { continue }
						set.add(
							MangaTag(
								key = key,
								title = obj.optString("name").ifBlank { key }.toTitleCase(sourceLocale),
								source = source,
							),
						)
					}
				}
			} else {
				emptySet()
			}

			result.add(
				Manga(
					id = generateUid(href),
					url = href,
					publicUrl = "https://$domain$href",
					title = it.optString("title"),
					altTitles = it.optString("alternative_name")
						.split("/", ",", ";", "、", "／")
						.mapNotNullToSet { s -> s.trim().takeIf { t -> t.isNotEmpty() } },
					coverUrl = it.optString("cover").takeIf { c -> c.isNotBlank() }.orEmpty(),
					rating = it.optJSONObject("rating")?.optDouble("average")?.let { avg ->
						if (avg > 0) (avg / 10f).toFloat().coerceIn(0f, 1f) else RATING_UNKNOWN
					} ?: RATING_UNKNOWN,
					tags = tags,
					authors = emptySet(),
					state = itemState,
					source = source,
					contentRating = when {
						itemAudience == "adult" -> ContentRating.ADULT
						isNsfwSource -> ContentRating.ADULT
						else -> null
					},
				),
			)
		}
		return result
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()

		val statsRows: Map<String, String> = doc.select(".detail-stat-grid > div").associate { div ->
			val label = div.selectFirst("span")?.text()?.trim().orEmpty()
			val value = div.selectFirst("strong")?.text()?.trim().orEmpty()
			label.lowercase(sourceLocale) to value
		}

		val state = parseState(statsRows["status"].orEmpty())
		val author = statsRows["author"]?.takeIf { it.isNotBlank() }
		val artist = statsRows["artist"]?.takeIf { it.isNotBlank() }

		// Genres appear both in a chip block on the page and as inline links.
		val tags = doc.select("a[href*='?genre=']").mapNotNullToSet { a ->
			val href = a.attr("href")
			val key = href.substringAfter("?genre=").substringBefore("&").trim()
				.ifEmpty { return@mapNotNullToSet null }
			val title = a.text().trim().ifEmpty { key }
			MangaTag(key = key, title = title.toTitleCase(sourceLocale), source = source)
		}

		// Alt title strip: the block starts with a literal "Alternative Name" span
		val altTitleBlock = doc.selectFirst(".detail-alt-title")
		val altTitles = altTitleBlock?.let { block ->
			val raw = block.ownText().trim().ifEmpty {
				// Fallback: everything except the first <span>
				block.text().removePrefix(block.selectFirst("span")?.text().orEmpty()).trim()
			}
			raw.split("/", ",", ";", "、", "／")
				.mapNotNull { s -> s.trim().takeIf { it.isNotEmpty() } }
				.toSet()
		}.orEmpty()

		// Synopsis: <p> after the "Synopsis" heading.
		val description = doc.selectFirst("h2:matchesOwn((?i)^Synopsis$) + *")?.html()
			?: doc.selectFirst(".detail-synopsis, .detail-premium-copy p")?.html()

		val cover = doc.selectFirst(".detail-poster img")?.src() ?: manga.coverUrl

		val ratingWidget = doc.selectFirst("[data-rating-widget]")
		val rating = ratingWidget?.attr("data-rating-average")?.toFloatOrNull()?.let { avg ->
			if (avg > 0f) (avg / 10f).coerceIn(0f, 1f) else RATING_UNKNOWN
		} ?: manga.rating

		val chapterRows = doc.select("a.chapter-row")
		val chapters = ArrayList<MangaChapter>(chapterRows.size)
		// The DOM lists chapters ascending (Ch. 1 → latest). Their index doubles
		// as the chapter number, so no need to parse "Ch. N" manually.
		chapterRows.forEachIndexed { index, a ->
			val href = a.attrAsRelativeUrl("href")
			val label = a.selectFirst("strong")?.text()?.trim().orEmpty()
			val dt = a.selectFirst("time")?.attr("datetime")?.trim()
			chapters.add(
				MangaChapter(
					id = generateUid(href),
					title = label,
					number = (index + 1).toFloat(),
					volume = 0,
					url = href,
					uploadDate = chapterDateFormat.parseSafe(dt),
					scanlator = null,
					branch = null,
					source = source,
				),
			)
		}

		val type = statsRows["type"]?.lowercase(sourceLocale)
		val contentRating = when {
			type != null && "hentai" in type -> ContentRating.ADULT
			manga.contentRating != null -> manga.contentRating
			isNsfwSource -> ContentRating.ADULT
			else -> null
		}

		return manga.copy(
			title = doc.selectFirst("h1")?.text()?.trim().takeIf { !it.isNullOrEmpty() } ?: manga.title,
			altTitles = altTitles,
			description = description,
			tags = tags,
			state = state ?: manga.state,
			authors = setOfNotNull(author, artist?.takeIf { it != author }),
			coverUrl = cover,
			rating = rating,
			chapters = chapters,
			contentRating = contentRating,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		return doc.select(".reader-frame img[data-reader-image], .reader-frame img").mapNotNull { img: Element ->
			val src = img.src() ?: img.attr("data-src").takeIf { it.isNotBlank() } ?: return@mapNotNull null
			MangaPage(
				id = generateUid(src),
				url = src,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/").parseHtml()
		return doc.select("select[name='genre'] option").mapNotNullToSet { opt ->
			val key = opt.attr("value").trim().ifEmpty { return@mapNotNullToSet null }
			// Strip the trailing "(count)" the site appends to each label.
			val title = opt.text().trim()
				.replace(Regex("""\s*\(\d+\)\s*$"""), "")
				.ifEmpty { return@mapNotNullToSet null }
			MangaTag(key = key, title = title.toTitleCase(sourceLocale), source = source)
		}
	}

	private fun parseState(raw: String): MangaState? = when (raw.trim().lowercase(sourceLocale)) {
		"ongoing", "on-going", "on going" -> MangaState.ONGOING
		"completed", "finished", "end" -> MangaState.FINISHED
		"hiatus", "paused" -> MangaState.PAUSED
		else -> null
	}
}
