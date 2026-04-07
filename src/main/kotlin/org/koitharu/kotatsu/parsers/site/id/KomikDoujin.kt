package org.koitharu.kotatsu.parsers.site.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("KOMIKDOUJIN", "KomikDoujin", "id", ContentType.HENTAI)
internal class KomikDoujinParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.KOMIKDOUJIN, pageSize = 20) {

	override val configKeyDomain = ConfigKey.Domain("komikdoujin.com")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = false,
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
			if (page > 1) {
				addPathSegments("page/$page/")
			}
			if (!filter.query.isNullOrEmpty()) {
				addQueryParameter("s", filter.query)
			} else {
				when (order) {
					SortOrder.UPDATED -> addQueryParameter("orderby", "modified")
					SortOrder.POPULARITY -> addQueryParameter("orderby", "popular")
					SortOrder.NEWEST -> addQueryParameter("orderby", "date")
					SortOrder.ALPHABETICAL -> addQueryParameter("orderby", "title")
					else -> addQueryParameter("orderby", "date")
				}
				filter.tags.forEach { tag ->
					addQueryParameter("genre", tag.key)
				}
				filter.types.oneOrThrowIfMany()?.let {
					addQueryParameter("type", when (it) {
						ContentType.MANGA -> "manga"
						ContentType.MANHWA -> "manhwa"
						ContentType.MANHUA -> "manhua"
						else -> null
					} ?: return@let)
				}
				filter.states.oneOrThrowIfMany()?.let {
					addQueryParameter("status", when (it) {
						MangaState.ONGOING -> "ongoing"
						MangaState.FINISHED -> "completed"
						else -> null
					} ?: return@let)
				}
			}
		}.build()

		val doc = webClient.httpGet(url).parseHtml()

		return doc.select("article.komik-card").mapNotNull { el ->
			val href = el.selectFirst(".thumb-wrap")?.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val titleEl = el.selectFirst(".card-title a") ?: return@mapNotNull null
			Manga(
				id = generateUid(href),
				title = titleEl.text(),
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.ADULT,
				coverUrl = el.selectFirst(".thumb-wrap img")?.src(),
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
		val infoContainer = doc.selectFirst("div.series-info") ?: doc

		val stateText = infoContainer.select(".meta-item").find {
			it.text().contains("Status", ignoreCase = true)
		}?.selectLast("span, a, div")?.text()

		val state = when (stateText?.trim()?.lowercase()) {
			"ongoing", "berlangsung" -> MangaState.ONGOING
			"completed", "tamat", "ended" -> MangaState.FINISHED
			else -> null
		}

		val authorText = infoContainer.select(".meta-item").find {
			it.text().contains("Author", ignoreCase = true)
		}?.selectLast("span, a, div")?.text()

		val description = doc.selectFirst("div.series-synopsis, .series-meta .desc, .series-info .desc")
			?.html()

		val tags = doc.select("div.genre-list a.genre-tag, a.genre-tag").mapToSet {
			MangaTag(
				key = it.attrAsRelativeUrlOrNull("href")
					?.removePrefix("/genre/")
					?.removeSuffix("/")
					?: it.text(),
				title = it.text().trim(),
				source = source,
			)
		}

		val chapterDateFormat = SimpleDateFormat("dd/MM/yyyy", sourceLocale)
		val chapters = doc.select("div.chapter-grid .chapter-row").mapChapters(reversed = true) { index, el ->
			val chapterLink = el.selectFirst("a.chapter-link") ?: return@mapChapters null
			val url = chapterLink.attrAsRelativeUrlOrNull("href") ?: return@mapChapters null
			MangaChapter(
				id = generateUid(url),
				title = el.selectFirst(".chap-title")?.textOrNull(),
				number = index + 1f,
				volume = 0,
				url = url,
				scanlator = null,
				uploadDate = el.selectFirst(".chap-date")?.text()?.let { chapterDateFormat.parseSafe(it) } ?: 0L,
				branch = null,
				source = source,
			)
		}

		val coverUrl = infoContainer.selectFirst("div.series-thumb img")
			?.src()

		return manga.copy(
			coverUrl = coverUrl ?: manga.coverUrl,
			authors = setOfNotNull(authorText?.trim()),
			state = state,
			description = description,
			tags = tags,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.select("div.img-protector img.chapter-img, img.chapter-img").map {
			val url = it.attrAsAbsoluteUrlOrNull("src") ?: return@map null
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}.filterNotNull()
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("/".toAbsoluteUrl(domain)).parseHtml()
		return doc.select("div.genre-list a.genre-tag, a.genre-tag").mapToSet {
			MangaTag(
				key = it.attrAsRelativeUrlOrNull("href")
					?.removePrefix("/genre/")
					?.removeSuffix("/")
					?: it.text(),
				title = it.text().trim(),
				source = source,
			)
		}
	}
}
