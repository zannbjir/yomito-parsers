package org.koitharu.kotatsu.parsers.site.madara.id

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.util.EnumSet


@MangaSourceParser("KOMIKU_ASIA", "Komiku.asia", "id")
internal class KomikuAsia(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.KOMIKU_ASIA, "01.komiku.asia", pageSize = 20) {

	override val mangaUrlDirectory = "/manga"
	override val datePattern = "MMMM dd, yyyy"
	override val selectMangaList = ".utao .uta .imgu, .listupd .bs .bsx, .listo .bs .bsx"
	override val selectMangaListImg = "img"
	override val selectMangaListTitle = "a"
	override val selectChapter = "div.bxcl li, div.cl li, #chapterlist li, ul li:has(div.chbox):has(div.eph-num)"
	override val detailsDescriptionSelector = ".desc, .entry-content[itemprop=description]"

	override val filterCapabilities: MangaListFilterCapabilities
		get() = super.filterCapabilities.copy(
			isTagsExclusionSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
			MangaState.PAUSED,
			MangaState.ABANDONED,
		),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
			ContentType.COMICS,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = "https://$domain${mangaUrlDirectory}/".toHttpUrl().newBuilder().apply {
			addQueryParameter("page", page.toString())

			when (filter) {
				is MangaListFilter.Search -> {
					addQueryParameter("title", filter.query)
				}
				is MangaListFilter.Advanced -> {
					filter.states.oneOrThrowIfMany()?.let {
						addQueryParameter(
							"status",
							when (it) {
								MangaState.ONGOING -> "ongoing"
								MangaState.FINISHED -> "completed"
								MangaState.PAUSED -> "hiatus"
								MangaState.ABANDONED -> "dropped"
								else -> ""
							},
						)
					}
					filter.types.oneOrThrowIfMany()?.let {
						addQueryParameter(
							"type",
							when (it) {
								ContentType.MANGA -> "Manga"
								ContentType.MANHWA -> "Manhwa"
								ContentType.MANHUA -> "Manhua"
								ContentType.COMICS -> "Comic"
								else -> ""
							},
						)
					}
					filter.tags.forEach { tag ->
						addQueryParameter(
							"genre[]",
							if (tag.key.startsWith("-")) tag.key else tag.key,
						)
					}
					addQueryParameter(
						"order",
						when (order) {
							SortOrder.ALPHABETICAL -> "title"
							SortOrder.ALPHABETICAL_DESC -> "titlereverse"
							SortOrder.UPDATED -> "update"
							SortOrder.NEWEST -> "latest"
							SortOrder.POPULARITY -> "popular"
							else -> ""
						},
					)
				}
				else -> {
					addQueryParameter(
						"order",
						when (order) {
							SortOrder.UPDATED -> "update"
							SortOrder.POPULARITY -> "popular"
							else -> "update"
						},
					)
				}
			}
		}.build()

		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	override fun parseMangaList(docs: Document): List<Manga> {
		return docs.select(selectMangaList).mapNotNull { element ->
			val a = element.selectFirst("a") ?: return@mapNotNull null
			val relativeUrl = a.attrAsRelativeUrl("href").toRelativeUrl(domain)
			Manga(
				id = generateUid(relativeUrl),
				url = relativeUrl,
				title = a.attr("title").trim().ifEmpty {
					element.selectFirst(selectMangaListTitle)?.text()?.trim() ?: return@mapNotNull null
				},
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
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

		val detailsBlock = doc.selectFirst("div.bigcontent, div.animefull, div.main-info, div.postbody")

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
			?.selectFirst(".infotable tr:contains(status) td:last-child, .tsinfo .imptdt:contains(status) i")
			?.text()?.trim()

		val state = when {
			statusText == null -> null
			statusText.contains("ongoing", ignoreCase = true) -> MangaState.ONGOING
			statusText.contains("completed", ignoreCase = true) ||
				statusText.contains("tamat", ignoreCase = true) -> MangaState.FINISHED
			statusText.contains("hiatus", ignoreCase = true) -> MangaState.PAUSED
			statusText.contains("dropped", ignoreCase = true) ||
				statusText.contains("cancelled", ignoreCase = true) -> MangaState.ABANDONED
			else -> null
		}

		val author = detailsBlock?.selectFirst(
			".infotable tr:contains(Author) td:last-child, .infotable tr:contains(Pengarang) td:last-child, " +
				".fmed b:contains(Author)+span, .fmed b:contains(Pengarang)+span",
		)?.text()?.trim()?.takeIf { it.isNotBlank() && it != "-" }

		val description = detailsBlock?.select(detailsDescriptionSelector)
			?.joinToString("\n") { it.text() }?.trim()

		val cover = detailsBlock?.selectFirst(".infomanga > div[itemprop=image] img, .thumb img")?.src()
			?: doc.selectFirst(".infomanga > div[itemprop=image] img, .thumb img")?.src()

		// Chapters
		val chapters = doc.select(selectChapter).mapChapters(reversed = true) { index, element ->
			val a = element.selectFirst("a") ?: return@mapChapters null
			val url = a.attrAsRelativeUrl("href")
			val title = element.select(".lch a, .chapternum").text().ifBlank { a.text() }.trim()
			val dateText = element.selectFirst(".chapterdate")?.text()
			MangaChapter(
				id = generateUid(url),
				title = title,
				url = url,
				number = index + 1f,
				volume = 0,
				scanlator = null,
				uploadDate = parseChapterDate(dateText),
				branch = null,
				source = source,
			)
		}

		return manga.copy(
			description = description,
			state = state,
			authors = setOfNotNull(author),
			contentRating = if (isNsfwSource) ContentRating.ADULT else ContentRating.SAFE,
			tags = tags,
			chapters = chapters,
			coverUrl = cover ?: manga.coverUrl,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

		// MangaThemesia reader area
		val images = doc.select("div#readerarea img")
			.filter { it.src() != null }
			.mapIndexed { i, img ->
				MangaPage(
					id = generateUid("$i-${chapter.url}"),
					url = img.src()!!,
					preview = null,
					source = source,
				)
			}

		if (images.isNotEmpty()) return images

		// Fallback: images embedded in JS
		val scriptData = doc.toString()
		val match = Regex("\"images\"\\s*:\\s*(\\[.*?])").find(scriptData)
		return match?.groupValues?.get(1)?.let { jsonArr ->
			try {
				val arr = org.json.JSONArray(jsonArr)
				(0 until arr.length()).map { i ->
					MangaPage(
						id = generateUid("$i-${chapter.url}"),
						url = arr.getString(i),
						preview = null,
						source = source,
					)
				}
			} catch (_: Exception) {
				emptyList()
			}
		} ?: emptyList()
	}

	private fun parseChapterDate(dateText: String?): Long {
		if (dateText.isNullOrBlank()) return 0L
		return try {
			java.text.SimpleDateFormat(datePattern, sourceLocale).parse(dateText)?.time ?: 0L
		} catch (_: Exception) {
			0L
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain${mangaUrlDirectory}/").parseHtml()
		return doc.select("ul.genrez li").mapNotNullToSet { li ->
			val value = li.selectFirst("input[type=checkbox]")?.attr("value") ?: return@mapNotNullToSet null
			val name = li.selectFirst("label")?.text()?.trim() ?: return@mapNotNullToSet null
			MangaTag(key = value, title = name.toTitleCase(sourceLocale), source = source)
		}
	}
}
