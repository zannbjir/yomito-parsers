package org.koitharu.kotatsu.parsers.site.id

import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("RYZUKOMIK", "Ryzukomik", "id")
internal class Ryzukomik(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.RYZUKOMIK, 50) {

	override val configKeyDomain = ConfigKey.Domain("ryzukomik.my.id")

	// Base path setelah redesign situs
	private val basePath = "/komiku"

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = false,
			isSearchSupported = true,
			isSearchWithFiltersSupported = false,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = buildGenreList(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			append(basePath)
			append("/browse?ajax=1&page=")
			append(page)
			when {
				!filter.query.isNullOrEmpty() -> {
					append("&s=")
					append(filter.query!!.urlEncoded())
				}
				filter.tags.isNotEmpty() -> {
					val tag = filter.tags.oneOrThrowIfMany()
					if (tag != null) {
						append("&genre=")
						append(tag.key.urlEncoded())
					}
				}
				else -> {
					when (order) {
						SortOrder.NEWEST -> append("&orderby=new")
						else -> append("&orderby=update")
					}
				}
			}
		}

		val json = webClient.httpGet(url).parseJson()
		val gridHtml = json.getString("grid")
		val doc = gridHtml.parseHtml()

		return doc.select("div.group").mapNotNull { el ->
			parseMangaFromCard(el)
		}
	}

	private fun parseMangaFromCard(el: Element): Manga? {
		val anchor = el.selectFirst("a[href*=\"/komik/\"]") ?: return null
		val href = anchor.attr("href")
		val slug = href.trimEnd('/').substringAfterLast('/')
		if (slug.isBlank()) return null

		val url = "$basePath/komik/$slug"
		val title = el.selectFirst("h3")?.text()?.trim() ?: return null
		val cover = el.selectFirst("img")?.attr("src")?.ifBlank { null }

		return Manga(
			id = generateUid(url),
			url = url,
			publicUrl = "https://$domain$url/",
			title = title,
			altTitles = emptySet(),
			rating = RATING_UNKNOWN,
			contentRating = null,
			coverUrl = cover,
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet("https://$domain${manga.url}").parseHtml()

		val title = doc.selectFirst("h1")?.text()?.trim() ?: manga.title
		val coverUrl = doc.selectFirst("img[src*=\"thumbnail.komiku\"]")?.attr("src")?.ifBlank { null }

		val labels = doc.select("span.text-neutral-500")
		var status: MangaState? = null
		var author: String? = null
		val genres = mutableSetOf<MangaTag>()

		for (label in labels) {
			when (label.text().trim()) {
				"Status" -> {
					val value = label.nextElementSibling()?.text()?.trim().orEmpty()
					status = when {
						value.contains("ongoing", ignoreCase = true) -> MangaState.ONGOING
						value.contains("completed", ignoreCase = true) ||
							value.contains("tamat", ignoreCase = true) ||
							value.contains("selesai", ignoreCase = true) -> MangaState.FINISHED
						else -> null
					}
				}
				"Author" -> {
					author = label.nextElementSibling()?.text()?.trim()?.ifBlank { null }
				}
				"Genre" -> {
					label.nextElementSibling()?.select("a[href*=genre]")?.forEach { a ->
						val genreHref = a.attr("href")
						val genreKey = genreHref.substringAfterLast("=").trim()
						val genreName = a.text().trim()
						if (genreKey.isNotBlank() && genreName.isNotBlank()) {
							genres.add(MangaTag(key = genreKey, title = genreName, source = source))
						}
					}
				}
			}
		}

		val description = doc.getElementById("synopsisFull")?.text()?.trim()?.ifBlank {
			doc.getElementById("synopsisShort")?.text()?.trim()
		}

		val altTitle = doc.select("span.text-neutral-500").firstOrNull {
			it.text().trim() == "Alternatif"
		}?.nextElementSibling()?.text()?.trim()?.ifBlank { null }

		val chapters = parseChapterList(doc, manga.url)

		return manga.copy(
			title = title,
			altTitles = setOfNotNull(altTitle),
			description = description,
			state = status,
			authors = setOfNotNull(author),
			contentRating = ContentRating.SAFE,
			tags = genres,
			chapters = chapters,
			coverUrl = coverUrl ?: manga.coverUrl,
		)
	}

	private fun parseChapterList(doc: org.jsoup.nodes.Document, mangaUrl: String): List<MangaChapter> {
		val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT)
		val chapterItems = doc.select("a.chapter-item")

		return chapterItems.mapNotNull { a ->
			val href = a.attr("href").trim()
			if (href.isBlank()) return@mapNotNull null
			// href: /komiku/chapter/{slug}
			val chapterSlug = href.trimEnd('/').substringAfterLast('/')
			if (chapterSlug.isBlank()) return@mapNotNull null

			val chapterUrl = "$basePath/chapter/$chapterSlug"
			// text format: "{number} Chapter {number} {dd/MM/yyyy}"
			val fullText = a.text().trim()

			// Extract chapter title (e.g. "Chapter 133")
			val titleMatch = Regex("""(Chapter\s+[\d.]+(?:\s+\S+)*)""", RegexOption.IGNORE_CASE).find(fullText)
			val chapterTitle = titleMatch?.value?.trim() ?: chapterSlug

			val number = Regex("""Chapter\s+([\d.]+)""", RegexOption.IGNORE_CASE)
				.find(fullText)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f

			val dateStr = Regex("""\d{2}/\d{2}/\d{4}""").find(fullText)?.value
			val uploadDate = dateStr?.let {
				runCatching { dateFormat.parse(it)?.time ?: 0L }.getOrDefault(0L)
			} ?: 0L

			MangaChapter(
				id = generateUid(chapterUrl),
				title = chapterTitle,
				url = chapterUrl,
				number = number,
				volume = 0,
				scanlator = null,
				uploadDate = uploadDate,
				branch = null,
				source = source,
			)
		}.sortedBy { it.number }
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet("https://$domain${chapter.url}").parseHtml()

		val scriptContent = doc.select("script").firstOrNull { script ->
			script.data().contains("originalImages")
		}?.data() ?: return emptyList()

		val imagesJson = Regex("""const originalImages\s*=\s*(\[.*?\])""", RegexOption.DOT_MATCHES_ALL)
			.find(scriptContent)?.groupValues?.get(1) ?: return emptyList()

		val imageUrls = Regex(""""(https?://[^"]+)"""").findAll(imagesJson)
			.map { it.groupValues[1] }
			.toList()

		return imageUrls.mapIndexed { index, url ->
			MangaPage(
				id = generateUid("$index-${chapter.url}"),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private fun buildGenreList(): Set<MangaTag> = listOf(
		"action" to "Action", "adventure" to "Adventure", "comedy" to "Comedy",
		"crime" to "Crime", "drama" to "Drama", "fantasy" to "Fantasy",
		"girls-love" to "Girls Love", "harem" to "Harem", "historical" to "Historical",
		"horror" to "Horror", "isekai" to "Isekai", "magical-girls" to "Magical Girls",
		"mecha" to "Mecha", "medical" to "Medical", "music" to "Music",
		"mystery" to "Mystery", "philosophical" to "Philosophical",
		"psychological" to "Psychological", "romance" to "Romance", "sci-fi" to "Sci-Fi",
		"school-life" to "School Life", "slice-of-life" to "Slice of Life",
		"sports" to "Sports", "thriller" to "Thriller", "tragedy" to "Tragedy",
		"wuxia" to "Wuxia", "yuri" to "Yuri",
	).map { (key, title) -> MangaTag(key = key, title = title, source = source) }.toSet()
}
