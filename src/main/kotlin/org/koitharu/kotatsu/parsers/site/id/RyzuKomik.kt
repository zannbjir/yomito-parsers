package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("RYZUKOMIK", "Ryzukomik", "id")
internal class Ryzukomik(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.RYZUKOMIK, pageSize = 50) {

	override val configKeyDomain = ConfigKey.Domain("baca.ryzukomik.space")

	private val browsePath = "/ki-browse"

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.ALPHABETICAL)

	override val filterCapabilities = MangaListFilterCapabilities(
		isSearchSupported = true,
		isSearchWithFiltersSupported = false,
		isMultipleTagsSupported = false,
	)

	private val genres = listOf(
		"action", "adult", "adventure", "arts", "boys-love", "comedy", "crime", "demons",
		"drama", "drama-supernatural", "ecchi", "fantasy", "gender-bender", "girls-love",
		"harem", "historical", "horror", "isekai", "josei", "life", "magical-girls", "martial",
		"martial-arts", "mature", "mecha", "medical", "music", "mystery", "philosophical",
		"psychological", "reincarnation", "romance", "school", "school-life", "sci-fi", "seinen",
		"shoujo", "shoujo-ai", "shounen", "shounen-ai", "slice-of-life", "smut", "sports",
		"superhero", "supernatural", "thriller", "tragedy", "wuxia", "yuri",
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = genres.map { MangaTag(it.replace('-', ' ').replaceFirstChar { char -> char.uppercase() }, it, source) }.toSet(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://$domain$browsePath?ajax=1&page=").append(page)
			filter.query?.trim()?.takeIf { it.isNotBlank() }?.let { append("&title=").append(it.urlEncoded()) }
			filter.tags.firstOrNull()?.key?.takeIf { it.isNotBlank() }?.let { append("&genre=").append(it.urlEncoded()) }
		}
		val json = webClient.httpGet(url).parseJson()
		return parseBrowseItems(json.optJSONArray("dt"))
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val title = doc.selectFirst("main h1")?.text()?.trim().takeIf { !it.isNullOrBlank() } ?: manga.title
		val cover = doc.selectFirst("main article aside img")?.attr("src")?.trim().takeIf { !it.isNullOrBlank() }
			?: manga.coverUrl
		val metadata = doc.select("main .space-y-2 > div.flex.justify-between")
		val author = metadataValue(metadata, "Pengarang") ?: metadataValue(metadata, "Author")
		val state = parseState(metadataValue(metadata, "Status"))
		val altTitle = doc.selectFirst("main article h1 + p")?.text()?.trim().takeIf { !it.isNullOrBlank() }
		val tags = doc.select("a[href*='ki-browse?genre=']").mapNotNull { tag ->
			val name = tag.text().trim()
			val key = tag.attr("href").substringAfter("genre=").substringBefore('&').trim()
			if (name.isNotBlank() && key.isNotBlank()) MangaTag(name, key, source) else null
		}.toSet()
		return manga.copy(
			title = title,
			altTitles = setOfNotNull(altTitle),
			coverUrl = cover,
			largeCoverUrl = cover,
			description = doc.getElementById("synopsisText")?.text()?.trim(),
			authors = setOfNotNull(author),
			tags = tags,
			state = state,
			chapters = parseChapterList(doc),
			contentRating = ContentRating.SAFE,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val script = doc.select("script").firstOrNull { it.data().contains("originalImages") }?.data() ?: return emptyList()
		val raw = Regex("const originalImages\\s*=\\s*(\\[.*?])", RegexOption.DOT_MATCHES_ALL)
			.find(script)?.groupValues?.getOrNull(1) ?: return emptyList()
		val images = runCatching { JSONArray(raw.replace("\\/", "/")) }.getOrNull() ?: return emptyList()
		return (0 until images.length()).mapNotNull { index ->
			val url = images.optString(index, "").trim()
			if (url.isBlank()) {
				null
			} else {
				// The reader itself uses DuckDuckGo because the image origins reject direct requests.
				val pageUrl = if (url.startsWith("https://proxy.duckduckgo.com/iu/?u=")) {
					url
				} else {
					"https://proxy.duckduckgo.com/iu/?u=${url.urlEncoded()}"
				}
				MangaPage(generateUid(pageUrl), pageUrl, null, source)
			}
		}
	}

	private fun parseBrowseItems(array: JSONArray?): List<Manga> {
		if (array == null) return emptyList()
		return (0 until array.length()).mapNotNull { index ->
			val item = array.optJSONObject(index) ?: return@mapNotNull null
			val slug = item.optString("sl", "").trim()
			if (slug.isBlank()) return@mapNotNull null
			val url = "/komik/$slug"
			Manga(
				id = generateUid(url),
				url = url,
				publicUrl = "https://$domain$url",
				title = item.optString("jd", "Untitled"),
				altTitles = emptySet(),
				coverUrl = item.optString("gm", ""),
				rating = parseRating(item.optString("rt")),
				contentRating = ContentRating.SAFE,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	private fun parseChapterList(doc: Document): List<MangaChapter> = doc.select("a.chapter-item").mapNotNull { link ->
			val href = link.attr("href").trim()
			val numberText = link.selectFirst("span:not(.ch-title)")?.text()?.trim()
				?: Regex("(?i)chapter\\s+([\\d.]+)").find(link.text())?.groupValues?.getOrNull(1)
			val number = numberText?.toFloatOrNull() ?: return@mapNotNull null
			if (href.isBlank()) return@mapNotNull null
			MangaChapter(
				id = generateUid(href),
				title = link.selectFirst(".ch-title")?.text()?.trim() ?: "Chapter $numberText",
				url = href.substringAfter(domain).ifEmpty { href },
				number = number,
				uploadDate = 0L,
				source = source,
				scanlator = null,
				branch = null,
				volume = 0,
			)
		}.sortedBy { it.number }

	private fun metadataValue(elements: List<org.jsoup.nodes.Element>, label: String): String? = elements
		.firstOrNull { it.selectFirst("span")?.text()?.trim().equals(label, ignoreCase = true) }
		?.select("span")?.lastOrNull()?.text()?.trim()?.takeIf { it.isNotBlank() }

	private fun parseState(value: String?): MangaState? = when (value?.lowercase(Locale.ROOT)) {
		"ongoing", "berjalan" -> MangaState.ONGOING
		"completed", "finished", "tamat", "selesai" -> MangaState.FINISHED
		else -> null
	}

	private fun parseRating(value: String?): Float {
		val rating = value?.toFloatOrNull() ?: return RATING_UNKNOWN
		return if (rating > 1f) rating / 10f else rating
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val response = chain.proceed(chain.request())
		if (CloudFlareHelper.checkResponseForProtection(response) != CloudFlareHelper.PROTECTION_NOT_DETECTED) {
			response.close()
			context.requestBrowserAction(this, chain.request().url.toString())
		}
		return response
	}
}
