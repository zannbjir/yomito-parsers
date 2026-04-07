package org.koitharu.kotatsu.parsers.site.mangareader.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANHWADESU", "ManhwaDesu", "id", ContentType.HENTAI)
internal class ManhwaDesuParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANHWADESU, pageSize = 20) {

	override val configKeyDomain = ConfigKey.Domain("manhwadesu.tech")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = urlBuilder().apply {
			if (!filter.query.isNullOrEmpty()) {
				addPathSegment("page").addPathSegment(page.toString())
				addQueryParameter("s", filter.query)
			} else {
				addPathSegment("manga")
				if (page > 1) {
					addPathSegment("page").addPathSegment(page.toString())
				}
				addQueryParameter(
					"order",
					when (order) {
						SortOrder.UPDATED -> "update"
						SortOrder.POPULARITY -> "popular"
						SortOrder.NEWEST -> "latest"
						SortOrder.ALPHABETICAL -> "title"
						else -> "latest"
					},
				)
				filter.tags.forEach { tag ->
					addQueryParameter("genre[]", tag.key)
				}
				filter.states.oneOrThrowIfMany()?.let {
					addQueryParameter(
						"status",
						when (it) {
							MangaState.ONGOING -> "ongoing"
							MangaState.FINISHED -> "completed"
							else -> ""
						},
					)
				}
				filter.types.oneOrThrowIfMany()?.let {
					addQueryParameter(
						"type",
						when (it) {
							ContentType.MANGA -> "manga"
							ContentType.MANHWA -> "manhwa"
							ContentType.MANHUA -> "manhua"
							else -> ""
						},
					)
				}
			}
		}.build()

		val doc = webClient.httpGet(url).parseHtml()

		return doc.select(".listupd .bsx, .upd .bs .bsx, article.manga-card").mapNotNull { el ->
			val linkEl = el.selectFirst("a") ?: return@mapNotNull null
			val href = linkEl.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			Manga(
				id = generateUid(href),
				title = el.selectFirst(".tt a, .post-title a, .entry-title a")?.text()
					?: linkEl.attr("title").orEmpty(),
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.ADULT,
				coverUrl = el.selectFirst("img")?.src(),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				largeCoverUrl = null,
				description = null,
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

		val infoTable = doc.selectFirst("table.infotable, .manga-info table, .infox table")

		val state = infoTable?.select("td:contains(Status)")?.let {
			val stateTd = it.nextElementSibling() ?: it.parent()?.selectLast("td")
			when (stateTd?.text()?.trim()?.lowercase()) {
				"ongoing", "berlangsung", "publishing" -> MangaState.ONGOING
				"completed", "tamat", "finished", "ended" -> MangaState.FINISHED
				else -> null
			}
		}

		val author = infoTable?.select("td:contains(Author)")?.let {
			it.nextElementSibling()?.text()?.trim()
		}

		val description = doc.selectFirst(
			".entry-content, .manga-desc, .description, .summary, .series-synopsis",
		)?.html()

		val tags = doc.select(
			".seriestugenre a, .genre-list a, .manga-genres a, .tags a",
		).mapToSet {
			MangaTag(
				key = it.attrAsRelativeUrlOrNull("href")
					?.removePrefix("/genre/")
					?.removePrefix("/manga-genre/")
					?.removeSuffix("/")
					?: it.text(),
				title = it.text().trim(),
				source = source,
			)
		}

		val chapterDateFormat = SimpleDateFormat("dd MMM yyyy", sourceLocale)
		val chapters = doc.select(
			"#chapter_list li, .chapter-list li, .cl ul li, #chapterlist ul li",
		).mapChapters(reversed = true) { index, el ->
			val chapterLink = el.selectFirst("a") ?: return@mapChapters null
			val url = chapterLink.attrAsRelativeUrlOrNull("href") ?: return@mapChapters null
			val dateText = el.selectFirst(
				".chapter-date, .date, .new-relese, .epsleft .date",
			)?.text()
			MangaChapter(
				id = generateUid(url),
				title = chapterLink.textOrNull(),
				number = index + 1f,
				volume = 0,
				url = url,
				scanlator = null,
				uploadDate = chapterDateFormat.parseSafe(dateText),
				branch = null,
				source = source,
			)
		}

		val coverUrl = doc.selectFirst(
			".seriestucontl .thumb img, .bigcover img, .manga-thumb img, .series-thumb img, img.wp-post-image",
		)?.src()

		return manga.copy(
			coverUrl = coverUrl ?: manga.coverUrl,
			authors = setOfNotNull(author),
			state = state,
			description = description,
			tags = tags,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

		// Try direct image extraction first
		val pages = doc.select(
			".reader-area img, .reading-content img, #reader img, .chapter-content img, img.chapter-img, .img-protector img",
		).mapNotNull {
			val url = it.attrAsAbsoluteUrlOrNull("src") ?: return@mapNotNull null
			if (url.contains("data:", ignoreCase = true)) return@mapNotNull null
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}

		// If no images found, try to look for lazy-loaded images
		if (pages.isEmpty()) {
			return doc.select("img[data-src], img[data-lazy-src], img[data-original]").mapNotNull {
				val url = it.attrAsAbsoluteUrlOrNull("data-src")
					?: it.attrAsAbsoluteUrlOrNull("data-lazy-src")
					?: it.attrAsAbsoluteUrlOrNull("data-original")
					?: return@mapNotNull null
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			}
		}

		return pages
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		// Try genre listing page first
		val doc = runCatching {
			webClient.httpGet("/genre/".toAbsoluteUrl(domain)).parseHtml()
		}.getOrElse {
			// Fallback: extract genres from the main page filter/sidebar
			webClient.httpGet("/".toAbsoluteUrl(domain)).parseHtml()
		}

		return doc.select(
			".genre-list a, ul.genrez li a, .widget_tag_cloud a, .tag-cloud a, .seriestugenre a",
		).mapToSet {
			MangaTag(
				key = it.attrAsRelativeUrlOrNull("href")
					?.removePrefix("/genre/")
					?.removePrefix("/manga-genre/")
					?.removeSuffix("/")
					?: it.text(),
				title = it.text().trim(),
				source = source,
			)
		}
	}
}
