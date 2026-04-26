package org.koitharu.kotatsu.parsers.site.mangareader.id

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.EnumSet

@MangaSourceParser("MGKOMIK", "MgKomik", "id")
internal class Mgkomik(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.MGKOMIK, "id.mgkomik.cc", pageSize = 20, searchPageSize = 10) {

	override val datePattern = "MMMM dd, yyyy"
	override val selectPage = "div#readerarea img"
	override val selectTestScript = "script:containsData(thisIsNeverFound)"
	override val listUrl = "/komik/"
	override val selectMangaList = ".utao .uta .imgu, .listupd .bs .bsx, .listo .bs .bsx"
	override val selectMangaListImg = "img"
	override val selectMangaListTitle = "a"
	override val selectChapter = "div.bxcl li, div.cl li, #chapterlist li, ul li:has(div.chbox):has(div.eph-num)"
	override val detailsDescriptionSelector = ".desc, .entry-content[itemprop=description]"

	override val filterCapabilities: MangaListFilterCapabilities
		get() = super.filterCapabilities.copy(
			isTagsExclusionSupported = false,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = getAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			if (!filter.query.isNullOrEmpty()) {
				append("/?s=")
				append(filter.query!!.urlEncoded())
				append("&post_type=manga")
			} else {
				append(listUrl)
				if (page > 1) {
					append("page/")
					append(page)
					append("/")
				}
				append("?")
				filter.states.oneOrThrowIfMany()?.let {
					append("status=")
					append(
						when (it) {
							MangaState.ONGOING -> "ongoing"
							MangaState.FINISHED -> "completed"
							else -> ""
						},
					)
					append("&")
				}
				filter.types.oneOrThrowIfMany()?.let {
					append("type=")
					append(
						when (it) {
							ContentType.MANGA -> "Manga"
							ContentType.MANHWA -> "Manhwa"
							ContentType.MANHUA -> "Manhua"
							else -> ""
						},
					)
					append("&")
				}
				filter.tags.forEach { tag ->
					append("genre[]=")
					append(tag.key)
					append("&")
				}
				append("order=")
				append(
					when (order) {
						SortOrder.POPULARITY -> "popular"
						SortOrder.NEWEST -> "latest"
						SortOrder.ALPHABETICAL -> "title"
						SortOrder.ALPHABETICAL_DESC -> "titlereverse"
						else -> "update"
					},
				)
			}
		}
		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	override fun parseMangaList(docs: Document): List<Manga> {
		return docs.select(selectMangaList).mapNotNull { element ->
			val a = element.selectFirst("a") ?: return@mapNotNull null
			val relativeUrl = a.attrAsRelativeUrl("href").toRelativeUrl(domain)
			val title = a.attr("title").trim().ifEmpty {
				element.selectFirst(".tt, h3, h4")?.text()?.trim()
					?: return@mapNotNull null
			}
			Manga(
				id = generateUid(relativeUrl),
				url = relativeUrl,
				title = title,
				altTitles = emptySet(),
				publicUrl = a.attrAsAbsoluteUrl("href"),
				rating = RATING_UNKNOWN,
				contentRating = if (isNsfwSource) ContentRating.ADULT else null,
				coverUrl = element.selectFirst(selectMangaListImg)?.src(),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val docs = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)

		val chapters = docs.select(selectChapter).mapChapters(reversed = true) { index, element ->
			val a = element.selectFirst("a") ?: return@mapChapters null
			val url = a.attrAsRelativeUrl("href").toRelativeUrl(domain)
			val title = element.select(".lch a, .chapternum").text().ifBlank { a.text() }.trim()
			val dateText = element.selectFirst(".chapterdate")?.text()
			MangaChapter(
				id = generateUid(url),
				title = title,
				url = url,
				number = index + 1f,
				volume = 0,
				scanlator = null,
				uploadDate = dateFormat.parseSafe(dateText),
				branch = null,
				source = source,
			)
		}

		return parseInfo(docs, manga, chapters)
	}

	override suspend fun parseInfo(docs: Document, manga: Manga, chapters: List<MangaChapter>): Manga {
		val detailsBlock = docs.selectFirst("div.bigcontent, div.animefull, div.main-info, div.postbody")

		val tags = detailsBlock?.select("div.gnr a, .mgen a, .seriestugenre a")
			?.mapNotNullToSet { el ->
				val tagTitle = el.text().trim()
				if (tagTitle.isBlank()) return@mapNotNullToSet null
				MangaTag(
					key = el.attr("href").substringAfter("/genre/").substringBefore("/"),
					title = tagTitle.toTitleCase(sourceLocale),
					source = source,
				)
			}.orEmpty()

		val statusText = detailsBlock
			?.selectFirst(".infotable tr:contains(Status) td:last-child, .tsinfo .imptdt:contains(Status) i, .tsinfo .imptdt:contains(status) i")
			?.text()?.trim()

		val state = when {
			statusText == null -> null
			statusText.contains("ongoing", ignoreCase = true) -> MangaState.ONGOING
			statusText.contains("completed", ignoreCase = true) ||
				statusText.contains("tamat", ignoreCase = true) -> MangaState.FINISHED
			statusText.contains("hiatus", ignoreCase = true) -> MangaState.PAUSED
			else -> null
		}

		val author = detailsBlock?.selectFirst(
			".infotable tr:contains(Author) td:last-child, " +
				".infotable tr:contains(Pengarang) td:last-child, " +
				".fmed b:contains(Author)+span, " +
				".fmed b:contains(Pengarang)+span",
		)?.text()?.trim()?.takeIf { it.isNotBlank() && it != "-" }

		val cover = detailsBlock?.selectFirst(".infomanga > div[itemprop=image] img, .thumb img")?.src()

		return manga.copy(
			description = docs.selectFirst(detailsDescriptionSelector)?.text()?.trim(),
			state = state,
			authors = setOfNotNull(author),
			contentRating = if (isNsfwSource) ContentRating.ADULT else ContentRating.SAFE,
			tags = tags,
			chapters = chapters,
			coverUrl = cover ?: manga.coverUrl,
		)
	}

	// Private, bukan override
	private suspend fun getAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain$listUrl").parseHtml()
		return doc.select("ul.genrez li").mapNotNullToSet { li ->
			val value = li.selectFirst("input[type=checkbox]")?.attr("value") ?: return@mapNotNullToSet null
			val name = li.selectFirst("label")?.text()?.trim() ?: return@mapNotNullToSet null
			MangaTag(key = value, title = name.toTitleCase(sourceLocale), source = source)
		}
	}
}
