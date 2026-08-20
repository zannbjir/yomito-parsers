package org.koitharu.kotatsu.parsers.site.id

import org.json.JSONArray
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

@MangaSourceParser("MAKOTA", "Makota", "id")
internal class Makota(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MAKOTA, pageSize = 48) {

	override val configKeyDomain = ConfigKey.Domain("v1.makota.asia")

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.NEWEST,
		SortOrder.POPULARITY,
	)

	override val filterCapabilities = MangaListFilterCapabilities(
		isSearchSupported = true,
		isSearchWithFiltersSupported = false,
		isMultipleTagsSupported = false,
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query?.trim().orEmpty()
		if (query.isNotBlank()) {
			val json = webClient.httpGet(
				"https://$domain/api/search?q=${query.urlEncoded()}&limit=200",
			).parseJson()
			return parseSearchResults(json.optJSONArray("results"))
				.drop((page - 1) * pageSize)
				.take(pageSize)
		}

		val path = if (order == SortOrder.POPULARITY) "/popular" else "/latest"
		val doc = webClient.httpGet("https://$domain$path?page=$page").parseHtml()
		return parseCatalog(doc)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val title = doc.selectFirst("h1")?.text()?.trim().takeIf { !it.isNullOrBlank() } ?: manga.title
		val author = doc.selectFirst("h1 + p span")?.text()?.trim()
		val cover = doc.select("img[alt]").firstOrNull { it.attr("alt").equals(title, ignoreCase = true) }
			?.attr("src")?.trim()
			?.takeIf { it.isNotBlank() }?.toAbsoluteUrl(domain) ?: manga.coverUrl
		val status = doc.selectFirst("h1")?.parent()?.selectFirst("span")?.text()?.trim()
		val description = doc.selectFirst("p.whitespace-pre-line")?.text()?.trim()

		return manga.copy(
			title = title,
			coverUrl = cover,
			largeCoverUrl = cover,
			description = description,
			authors = setOfNotNull(author?.takeIf { it.isNotBlank() }),
			state = parseState(status),
			chapters = parseChapters(doc),
			contentRating = ContentRating.SAFE,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val pageUrls = doc.select("script")
			.flatMap { script ->
				Regex("""/api/img\?t=[^"\\]+""")
					.findAll(script.data())
					.map { it.value }
					.toList()
			}
			.distinct()

		return pageUrls.map { url ->
			val absoluteUrl = url.toAbsoluteUrl(domain)
			MangaPage(generateUid(absoluteUrl), absoluteUrl, null, source)
		}
	}

	private fun parseCatalog(doc: Document): List<Manga> = doc.select("a[href^='/manga/']")
		.filter { it.text().trim().equals("Read Now", ignoreCase = true) }
		.mapNotNull { link ->
			val href = link.attr("href").trim()
			val card = findCard(link) ?: return@mapNotNull null
			val title = card.selectFirst("h3")?.text()?.trim()
				?.takeIf { it.isNotBlank() } ?: card.selectFirst("img")?.attr("alt")?.trim()
			if (title.isNullOrBlank() || href.isBlank()) return@mapNotNull null
			val cover = card.selectFirst("img")?.attr("src")?.trim()
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
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}

	private fun findCard(link: Element): Element? {
		var current: Element? = link
		repeat(6) {
			current = current?.parent()
			if (current?.selectFirst("h3") != null && current?.selectFirst("img") != null) return current
		}
		return null
	}

	private fun parseSearchResults(array: JSONArray?): List<Manga> {
		if (array == null) return emptyList()
		return (0 until array.length()).mapNotNull { index ->
			val item = array.optJSONObject(index) ?: return@mapNotNull null
			val slug = item.optString("slug").trim()
			val title = item.optString("title").trim()
			if (slug.isBlank() || title.isBlank()) return@mapNotNull null
			val url = "/manga/$slug"
			val cover = item.optString("thumbnail").trim().toAbsoluteUrl(domain)
			Manga(
				id = generateUid(url),
				url = url,
				publicUrl = url.toAbsoluteUrl(domain),
				title = title,
				altTitles = emptySet(),
				coverUrl = cover,
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.SAFE,
				tags = item.optJSONArray("genre")?.toTags().orEmpty(),
				state = parseState(item.optInt("status").toString()),
				authors = setOfNotNull(item.optString("author").trim().takeIf { it.isNotBlank() }),
				source = source,
			)
		}
	}

	private fun JSONArray.toTags(): Set<MangaTag> = (0 until length())
		.mapNotNull { index ->
			val name = optString(index).trim()
			if (name.isBlank()) null else MangaTag(name, name.lowercase(Locale.ROOT), source)
		}
		.toSet()

	private fun parseChapters(doc: Document): List<MangaChapter> = doc.select("a[href^='/read/']")
		.mapNotNull { link ->
			val href = link.attr("href").trim()
			val match = Regex("(?i)/chapter-0*([\\d]+(?:\\.[\\d]+)?)").find(href)
				?: return@mapNotNull null
			val numberText = match.groupValues[1]
			val number = numberText.toFloatOrNull() ?: return@mapNotNull null
			MangaChapter(
				id = generateUid(href),
				url = href,
				title = "Chapter $numberText",
				number = number,
				uploadDate = 0L,
				volume = 0,
				branch = null,
				scanlator = null,
				source = source,
			)
		}
		.distinctBy { it.url }
		.sortedBy { it.number }

	private fun parseState(value: String?): MangaState? = when (value?.lowercase(Locale.ROOT)) {
		"ongoing", "1" -> MangaState.ONGOING
		"completed", "finished", "0" -> MangaState.FINISHED
		else -> null
	}
}
