package org.koitharu.kotatsu.parsers.site.id

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("YURIBASE", "YuriBase", "id")
internal class YuriBase(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.YURIBASE, pageSize = 25) {

	override val configKeyDomain = ConfigKey.Domain("yuribase.top")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST)

	override val filterCapabilities = MangaListFilterCapabilities(
		isSearchSupported = true,
		isSearchWithFiltersSupported = false,
		isMultipleTagsSupported = false,
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = webClient.httpGet("https://$domain/manga").parseHtml()
			.select("a[href*='/manga/genre/']")
			.mapNotNull { link ->
				val name = link.text().trim()
				val key = link.attr("href").substringAfterLast('/').trim()
				if (name.isBlank() || key.isBlank()) null else MangaTag(name, key, source)
			}
			.toSet(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val sitePage = page.coerceAtLeast(1)
		val query = filter.query?.trim().orEmpty()
		val tag = filter.tags.firstOrNull()?.key?.trim().orEmpty()

		if (query.isBlank()) {
			return parseCatalog(loadCatalog(sitePage, tag))
		}

		// Search is a client-side Firestore lookup on the website. Search the small
		// paginated public archive instead of depending on its private client SDK.
		val matches = buildList {
			for (archivePage in 1..100) {
				val items = parseCatalog(loadCatalog(archivePage, tag))
				addAll(items.filter { it.title.contains(query, ignoreCase = true) })
				if (items.isEmpty()) break
			}
		}
		return matches.drop((sitePage - 1) * pageSize).take(pageSize)
	}

	private suspend fun loadCatalog(page: Int, tag: String): Document {
		val path = if (tag.isBlank()) {
			"/manga/update"
		} else {
			// The site's genre links already contain the encoded route segment.
			"/manga/genre/$tag"
		}
		return webClient.httpGet("https://$domain$path?page=$page").parseHtml()
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val title = doc.selectFirst("h1")?.text()?.trim().takeIf { !it.isNullOrBlank() } ?: manga.title
		val cover = doc.select("img[src*='/banner/']").firstOrNull()?.attr("src")?.trim()
			?.takeIf { it.isNotBlank() }?.toAbsoluteUrl(domain) ?: manga.coverUrl
		val author = doc.selectFirst("a[href^='https://x.com/'] span")?.text()?.trim()
		val synopsis = doc.select("p").firstOrNull {
			it.parents().any { parent -> parent.attr("class").contains("group/synopsis") }
		}?.text()?.trim()
		val tags = doc.select("a[href*='/manga/genre/']").mapNotNull { link ->
			val name = link.text().trim()
			val key = link.attr("href").substringAfterLast('/').trim()
			if (name.isBlank() || key.isBlank()) null else MangaTag(name, key, source)
		}.toSet()

		return manga.copy(
			title = title,
			coverUrl = cover,
			largeCoverUrl = cover,
			description = synopsis,
			authors = setOfNotNull(author?.takeIf { it.isNotBlank() }),
			tags = tags,
			state = parseState(doc),
			chapters = parseChapters(doc, manga.url),
			contentRating = ContentRating.SAFE,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.select("img[src*='/chapter/']")
			.mapNotNull { image -> image.attr("src").trim().takeIf { it.isNotBlank() } }
			.distinct()
			.map { url -> MangaPage(generateUid(url), url, null, source) }
	}

	private fun parseCatalog(doc: Document): List<Manga> = doc.select("a[href^='/manga/']")
		.filter { link ->
			val path = link.attr("href").substringBefore('?').substringBefore('#')
			path.matches(Regex("/manga/[^/]+"))
		}
		.mapNotNull { link ->
			val href = link.attr("href").trim()
			val title = link.selectFirst("p.font-semibold")?.text()?.trim()
				?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
			val cover = link.selectFirst("img")?.attr("src")?.trim()
				?.takeIf { it.isNotBlank() }?.toAbsoluteUrl(domain).orEmpty()
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				title = title,
				altTitles = emptySet(),
				coverUrl = cover,
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.SAFE,
				tags = parseCardTags(link),
				state = parseState(link.text()),
				authors = emptySet(),
				source = source,
			)
		}
		.distinctBy { it.url }

	private fun parseCardTags(link: Element): Set<MangaTag> = link.select("p").firstOrNull {
		it.attr("class").contains("uppercase")
	}?.text()?.split(',')
		.orEmpty()
		.map { it.trim() }
		.filter { it.isNotBlank() }
		.map { MangaTag(it, it.lowercase(Locale.ROOT), source) }
		.toSet()

	private fun parseChapters(doc: Document, mangaUrl: String): List<MangaChapter> {
		val scripts = doc.select("script").joinToString("\n") { it.data() }
		val normalized = scripts.replace("\\\"", "\"")
		val chapterBlock = Regex("\"chapters\":\\[(.*?)]", RegexOption.DOT_MATCHES_ALL)
			.find(normalized)?.groupValues?.get(1).orEmpty()
		val matches = Regex(
			"\"id\":\"([^\"]+)\".*?\"chapterNumber\":([0-9]+(?:\\.[0-9]+)?)",
			RegexOption.DOT_MATCHES_ALL,
		).findAll(chapterBlock).toList()
		return matches.mapNotNull { match ->
			val id = match.groupValues[1].trim()
			val numberText = match.groupValues[2]
			val number = numberText.toFloatOrNull() ?: return@mapNotNull null
			val url = "${mangaUrl.trimEnd('/')}/chapter/$id"
			MangaChapter(
				id = generateUid(url),
				url = url,
				title = "Chapter $numberText",
				number = number,
				uploadDate = 0L,
				volume = 0,
				branch = null,
				scanlator = null,
				source = source,
			)
		}.distinctBy { it.url }.sortedByDescending { it.number }
	}

	private fun parseState(value: String): MangaState? = when {
		Regex("\\b(ongoing|berjalan)\\b", RegexOption.IGNORE_CASE).containsMatchIn(value) -> MangaState.ONGOING
		Regex("\\b(complete|completed|finished|selesai)\\b", RegexOption.IGNORE_CASE).containsMatchIn(value) -> MangaState.FINISHED
		else -> null
	}

	private fun parseState(doc: Document): MangaState? = parseState(
		doc.selectFirst("h1")?.parent()?.text().orEmpty(),
	)
}
