package org.koitharu.kotatsu.parsers.site.madara.id

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.EnumSet

@MangaSourceParser("MGKOMIK", "MgKomik", "id")
internal class Mgkomik(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MGKOMIK, 20) {

	override val configKeyDomain = ConfigKey.Domain("web.mgkomik.cc")

	private fun buildHeaders(): Headers = Headers.Builder()
		.add("User-Agent", "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36")
		.add("Referer", "https://$domain/")
		.add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
		.add("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
	)

	override val filterCapabilities = MangaListFilterCapabilities(
		isSearchSupported = true,
		isMultipleTagsSupported = false,
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = emptySet(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildListUrl(page, order, filter)
		val doc = webClient.httpGet(url, buildHeaders()).parseHtml()

		return doc.select("div.manga-item, article.series, .manga__item, .series-item").mapNotNull { el ->
			val a = el.selectFirst("a[href*='/komik/'], a[href*='/series/']") ?: return@mapNotNull null
			val href = a.attrAsRelativeUrl("href")
			val title = el.selectFirst("h2, h3, .title, .manga-title")?.text()?.trim()
				?: return@mapNotNull null
			val cover = el.selectFirst("img")?.let {
				it.attr("data-src").ifBlank { it.src() }
			}
			Manga(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = a.attrAsAbsoluteUrl("href"),
				coverUrl = cover,
				largeCoverUrl = cover,
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.ADULT,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}.distinctBy { it.id }
	}

	private fun buildListUrl(page: Int, order: SortOrder, filter: MangaListFilter): String {
		val base = "https://$domain"
		if (filter is MangaListFilter.Search && !filter.query.isNullOrEmpty()) {
			return "$base/page/$page/?s=${filter.query.urlEncoded()}"
		}
		val sort = when (order) {
			SortOrder.POPULARITY -> "popular"
			SortOrder.NEWEST -> "latest"
			else -> "update"
		}
		return "$base/komik/page/$page/?order=$sort"
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.publicUrl ?: manga.url.toAbsoluteUrl(domain), buildHeaders()).parseHtml()

		val title = doc.selectFirst("h1.entry-title, h1.title, .manga-title")?.text()?.trim()
			?: manga.title
		val description = doc.selectFirst(".description, .summary__content, .entry-content")
			?.text()?.trim().orEmpty()

		val statusText = doc.selectFirst(".status, .manga-status")?.text().orEmpty()
		val state = when {
			statusText.contains("ongoing", ignoreCase = true) -> MangaState.ONGOING
			statusText.contains("completed", ignoreCase = true) ||
				statusText.contains("tamat", ignoreCase = true) -> MangaState.FINISHED
			doc.body().text().contains("tamat", ignoreCase = true) -> MangaState.FINISHED
			else -> MangaState.ONGOING
		}

		val chapters = doc.select(
			"li.chapter-item, .wp-manga-chapter, .chapter-link, a[href*='/chapter/']",
		).mapNotNull { el ->
			val a = el.selectFirst("a") ?: return@mapNotNull null
			val url = a.attrAsRelativeUrl("href")
			val chapterTitle = a.text().trim()
			val number = Regex("""[0-9]+(\.[0-9]+)?""").find(chapterTitle)?.value?.toFloatOrNull() ?: 0f
			MangaChapter(
				id = generateUid(url),
				title = chapterTitle,
				url = url,
				number = number,
				volume = 0,
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				source = source,
			)
		}.sortedByDescending { it.number }

		return manga.copy(
			title = title,
			description = description,
			state = state,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain), buildHeaders()).parseHtml()
		return doc.select(
			"img.wp-manga-chapter-img, .reading-content img, .page-break img, .chapter-image img",
		).mapNotNull { img ->
			val url = img.attr("data-src").ifBlank { img.attr("src") }.trim()
			if (url.isBlank() || url.contains("placeholder")) return@mapNotNull null
			MangaPage(
				id = generateUid(url),
				url = url.toAbsoluteUrl(domain),
				preview = null,
				source = source,
			)
		}
	}
}
