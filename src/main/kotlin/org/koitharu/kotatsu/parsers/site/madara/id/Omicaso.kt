package org.koitharu.kotatsu.parsers.site.madara.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("OMICASO", "Omicaso", "id")
internal class Omicaso(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.OMICASO, 20) {

	override val configKeyDomain = ConfigKey.Domain("omicaso.org")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	private val dateLocale = Locale("id")
	private val dateFormat = SimpleDateFormat("MMMM dd, yyyy", dateLocale)

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
	)

	override val filterCapabilities = MangaListFilterCapabilities(
		isMultipleTagsSupported = true,
		isSearchSupported = true,
		isSearchWithFiltersSupported = true,
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
		),
	)

	init {
		paginator.firstPage = 1
		searchPaginator.firstPage = 1
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)

			if (!filter.query.isNullOrEmpty()) {
				// Search uses the WordPress /?s= endpoint
				append("/?s=")
				append(filter.query.urlEncoded())
				if (page > 1) {
					append("&paged=")
					append(page)
				}
			} else {
				append("/comik/?")
				if (page > 1) {
					append("page=")
					append(page)
					append("&")
				}
				// Genre filter uses numeric IDs (e.g. genre[]=210 for Action)
				if (filter.tags.isNotEmpty()) {
					filter.tags.forEach { tag ->
						append("genre[]=")
						append(tag.key)
						append("&")
					}
				}
				// Status filter
				filter.states.firstOrNull()?.let { state ->
					append("status=")
					append(
						when (state) {
							MangaState.ONGOING -> "ongoing"
							MangaState.FINISHED -> "completed"
							MangaState.PAUSED -> "hiatus"
							else -> ""
						},
					)
					append("&")
				}
				// Sort order
				append("order=")
				append(
					when (order) {
						SortOrder.UPDATED -> "update"
						SortOrder.NEWEST -> "latest"
						SortOrder.POPULARITY -> "popular"
						SortOrder.ALPHABETICAL -> "title"
						SortOrder.ALPHABETICAL_DESC -> "titlereverse"
						else -> "update"
					},
				)
			}
		}

		val doc = webClient.httpGet(url).parseHtml()
		return doc.select("div.bsx").mapNotNull { div ->
			val a = div.selectFirst("a") ?: return@mapNotNull null
			val href = a.attrAsRelativeUrl("href")
			val img = a.selectFirst("img")
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				title = a.attr("title").ifEmpty { img?.attr("alt").orEmpty() },
				altTitles = emptySet(),
				coverUrl = img?.src(),
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				authors = emptySet(),
				state = null,
				source = source,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val fullUrl = manga.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()

		// Status from infotable
		val statusText = doc.select(".infotable tr")
			.firstOrNull { row ->
				row.select("td").firstOrNull()?.text()?.contains("Status", ignoreCase = true) == true
			}
			?.select("td")?.lastOrNull()
			?.ownText()?.lowercase()
			.orEmpty()

		val state = when {
			"ongoing" in statusText -> MangaState.ONGOING
			"completed" in statusText -> MangaState.FINISHED
			"hiatus" in statusText -> MangaState.PAUSED
			else -> null
		}

		// Genres from .seriestugenre links (slug-based keys for display)
		val tags = doc.select(".seriestugenre a").mapNotNullToSet { a ->
			val key = a.attr("href").removeSuffix("/").substringAfterLast('/')
			if (key.isEmpty()) return@mapNotNullToSet null
			MangaTag(key = key, title = a.text().toTitleCase(), source = source)
		}

		val description = doc.selectFirst(".entry-content")?.html().orEmpty()
		val altTitle = doc.selectFirst(".seriestualt")?.textOrNull()
		val coverUrl = doc.selectFirst(".thumb img")?.src() ?: manga.coverUrl
		val author = doc.select(".infotable tr")
			.firstOrNull { row ->
				val label = row.select("td").firstOrNull()?.text().orEmpty()
				"author" in label.lowercase() || "artist" in label.lowercase()
			}
			?.select("td")?.lastOrNull()?.textOrNull()

		val chapters = doc.select(".eplister ul li").mapChapters(reversed = true) { i, li ->
			val a = li.selectFirstOrThrow("a")
			val href = a.attrAsRelativeUrl("href")
			val chapterTitle = li.selectFirst(".chapternum")?.text().orEmpty()
			val dateText = li.selectFirst(".chapterdate")?.text()
			MangaChapter(
				id = generateUid(href),
				title = chapterTitle,
				number = i + 1f,
				volume = 0,
				url = href,
				uploadDate = dateFormat.parseSafe(dateText) ?: 0L,
				source = source,
				scanlator = null,
				branch = null,
			)
		}

		return manga.copy(
			title = doc.selectFirst("h1.entry-title")?.textOrNull() ?: manga.title,
			altTitles = setOfNotNull(altTitle),
			description = description,
			tags = tags,
			state = state,
			authors = setOfNotNull(author),
			coverUrl = coverUrl,
			chapters = chapters,
			contentRating = if (isNsfwSource) ContentRating.ADULT else null,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		return doc.select("#readerarea img").map { img ->
			val url = img.requireSrc().toRelativeUrl(domain)
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/comik/").parseHtml()
		return doc.select("input[name='genre[]']").mapNotNullToSet { input ->
			val id = input.attr("value").takeIf { it.isNotEmpty() } ?: return@mapNotNullToSet null
			val labelText = doc.selectFirst("label[for='${input.attr("id")}']")
				?.text()?.trim()?.takeIf { it.isNotEmpty() }
				?: return@mapNotNullToSet null
			MangaTag(key = id, title = labelText.toTitleCase(), source = source)
		}
	}
}
