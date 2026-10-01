package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrlOrNull
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.util.EnumSet

@MangaSourceParser("CGBUM", "CGBUM", "id")
internal class Cgbum(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.CGBUM, 24) {

	override val configKeyDomain = ConfigKey.Domain("cgbum.com")

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36")
		.add("Referer", "https://$domain/")
		.add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
		.add("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
		)

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val tags = runCatching {
			webClient.httpGet("https://$domain/daftar-komik", getRequestHeaders())
				.parseHtml()
				.select("input[name=\"genres[]\"]")
				.mapNotNullToSet { input ->
					val value = input.attr("value").trim()
					if (value.isEmpty()) null else MangaTag(value, value.lowercase(), source)
				}
		}.getOrDefault(emptySet())
		return MangaListFilterOptions(
			availableTags = tags,
			availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
			availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA),
		)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val params = ArrayList<String>(6)
		val url = buildString {
			append("https://")
			append(domain)
			if (!filter.query.isNullOrEmpty()) {
				append("/cari")
				params += "q=" + filter.query.urlEncoded()
			} else {
				append("/daftar-komik")
				params += "sort=" + when (order) {
					SortOrder.NEWEST -> "newest"
					SortOrder.POPULARITY -> "views"
					else -> "latest"
				}
				when (filter.states.firstOrNull()) {
					MangaState.ONGOING -> params += "status=ongoing"
					MangaState.FINISHED -> params += "status=tamat"
					else -> Unit
				}
				when (filter.types.firstOrNull()) {
					ContentType.MANGA -> params += "type=manga"
					ContentType.MANHWA -> params += "type=manhwa"
					ContentType.MANHUA -> params += "type=manhua"
					else -> Unit
				}
				filter.tags.forEach { params += "genres[]=" + it.key.urlEncoded() }
			}
			params += "page=$page"
			append('?')
			append(params.joinToString("&"))
		}
		val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
		return parseMangaList(doc)
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		val result = ArrayList<Manga>(24)
		doc.select("article.comic-card").forEach { card ->
			val link = card.selectFirst("a[href*=\"/komik/\"]") ?: return@forEach
			val relUrl = link.attrAsRelativeUrlOrNull("href") ?: return@forEach
			if (!relUrl.startsWith("/komik/")) return@forEach
			val title = card.selectFirst(".comic-card-title")?.text()?.trim()
				?: link.selectFirst("img")?.attr("alt")?.trim()
				?: return@forEach
			if (title.isEmpty()) return@forEach
			val cover = card.selectFirst("img")?.let { img ->
				img.attr("data-src").ifBlank { img.attr("src") }
			}?.ifBlank { null }
			val isAdult = card.attr("data-adult") == "1" ||
				card.selectFirst(".badge-pornhwa, .adult-sensitive-cover") != null
			result += Manga(
				id = generateUid(relUrl),
				title = title,
				altTitles = emptySet(),
				url = relUrl,
				publicUrl = relUrl.toAbsoluteUrl(domain),
				coverUrl = cover,
				largeCoverUrl = null,
				rating = RATING_UNKNOWN,
				contentRating = if (isAdult) ContentRating.ADULT else null,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
		return result
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain), getRequestHeaders()).parseHtml()

		val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: manga.title

		val altTitles = doc.selectFirst(".comic-alt-title")?.text()
			?.split(',')
			?.map { it.trim() }
			?.filter { it.isNotEmpty() }
			?.toSet()
			?: emptySet()

		val cover = doc.selectFirst(".comic-cover img")?.let { img ->
			img.attr("data-src").ifBlank { img.attr("src") }
		}?.ifBlank { null }
			?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
			?: manga.coverUrl

		val tags = doc.select(".comic-genres a.genre-pill, .comic-genres a").mapNotNullToSet { a ->
			val tagTitle = a.text().trim()
			if (tagTitle.isEmpty()) null else MangaTag(tagTitle, tagTitle.lowercase(), source)
		}

		val description = doc.selectFirst(".comic-synopsis .synopsis-content")
			?.text()?.trim()?.ifBlank { null }
			?: doc.selectFirst(".comic-synopsis")?.text()?.trim()?.ifBlank { null }

		val stateText = doc.selectFirst(".badge-status")?.text()?.trim()?.lowercase()
		val state = when {
			stateText == null -> null
			stateText.contains("ongoing") -> MangaState.ONGOING
			stateText.contains("tamat") || stateText.contains("completed") || stateText.contains("end") -> MangaState.FINISHED
			stateText.contains("hiatus") -> MangaState.PAUSED
			else -> null
		}

		var author: String? = null
		doc.select(".meta-row").forEach { row ->
			val label = row.selectFirst(".meta-label")?.text()?.trim()?.lowercase() ?: return@forEach
			val value = row.selectFirst(".meta-value")?.text()?.trim() ?: return@forEach
			if (label.contains("author") && value != "-") {
				author = value
			}
		}

		val isAdult = doc.selectFirst(".badge-pornhwa, .adult-sensitive-cover") != null

		val chapters = doc.select(".chapter-grid a.ch-grid-item").mapChapters(reversed = true) { index, el ->
			val chUrl = el.attrAsRelativeUrlOrNull("href") ?: return@mapChapters null
			val chTitle = el.text().trim().ifBlank { "Chapter ${index + 1}" }
			val number = el.attr("data-chapter-sort").toFloatOrNull()
				?: el.attr("data-chapter").toFloatOrNull()
				?: chTitle.substringAfterLast(' ').toFloatOrNull()
				?: (index + 1).toFloat()
			MangaChapter(
				id = generateUid(chUrl),
				title = chTitle,
				url = chUrl,
				number = number,
				volume = 0,
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				source = source,
			)
		}

		return manga.copy(
			title = title,
			altTitles = altTitles,
			coverUrl = cover,
			largeCoverUrl = cover,
			description = description,
			tags = tags,
			state = state,
			authors = setOfNotNull(author),
			contentRating = if (isAdult) ContentRating.ADULT else manga.contentRating,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain), getRequestHeaders()).parseHtml()
		return doc.select("#readerImages img").mapNotNull { img ->
			val url = img.attr("data-src").ifBlank { img.attr("src") }.trim()
			if (url.startsWith("http")) {
				MangaPage(
					id = generateUid(url),
					url = url,
					preview = null,
					source = source,
				)
			} else {
				null
			}
		}
	}
}
