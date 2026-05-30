package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("RYUKOMIK", "Ryukomik", "id")
internal class Ryukomik(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.RYUKOMIK, 20) {

	override val configKeyDomain = ConfigKey.Domain("www.ryukomik.my.id")

	private val apiDomain = "api.komiku.id"

	private fun buildHeaders(): Headers = Headers.Builder()
		.add("Referer", "https://$domain/")
		.add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
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
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://").append(apiDomain)
			when {
				!filter.query.isNullOrEmpty() -> {
					append("/?post_type=manga&s=").append(filter.query!!.urlEncoded())
					append("&page=").append(page)
				}
				filter.tags.isNotEmpty() -> {
					val tag = filter.tags.oneOrThrowIfMany()
					if (tag != null) {
						append("/genre/").append(tag.key).append("/page/").append(page).append("/")
					}
				}
				else -> {
					when (order) {
						SortOrder.POPULARITY -> append("/manga/popular/page/").append(page).append("/")
						SortOrder.ALPHABETICAL -> append("/manga/list-mode/?page=").append(page)
						else -> append("/manga/?page=").append(page) // UPDATED (default)
					}
				}
			}
		}

		val doc = webClient.httpGet(url, buildHeaders()).parseHtml()
		return parseMangaList(doc)
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		// komiku.id pakai WordPress dengan custom theme
		return doc.select("div.bge, div.ls3, article.manga").mapNotNull { el ->
			val a = el.selectFirst("a") ?: return@mapNotNull null
			val href = a.attr("href").ifBlank { return@mapNotNull null }
			val slug = href.trimEnd('/').substringAfterLast('/')
			val url = "/komiku/$slug"

			val title = (el.selectFirst("h3, h4, .judul") ?: a)
				.text().removePrefix("Komik ").trim()
				.ifBlank { return@mapNotNull null }

			val cover = el.selectFirst("img")?.run {
				attr("data-src").ifBlank { attr("src") }
			}?.ifBlank { null }

			Manga(
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
	}

	override suspend fun getDetails(manga: Manga): Manga {
		// URL pattern: /komiku/slug → api: api.komiku.id/manga/slug/
		val slug = manga.url.trimEnd('/').substringAfterLast('/')
		val url = "https://$apiDomain/manga/$slug/"
		val doc = webClient.httpGet(url, buildHeaders()).parseHtml()

		val title = doc.selectFirst("h1.jdl")?.text()
			?.removePrefix("Komik ")?.trim() ?: manga.title

		val cover = doc.selectFirst("div.ims img")?.run {
			attr("data-src").ifBlank { attr("src") }
		}?.ifBlank { manga.coverUrl }

		val desc = doc.selectFirst("div.desc, div.entry-content p")?.text()?.trim()

		val infoTable = doc.selectFirst("table.inftable, div.infox")
		val statusText = infoTable?.selectFirst("tr:has(td:contains(Status)) td:last-child, 
			span.imptdt:contains(Status)")?.text().orEmpty()
		val state = when {
			statusText.contains("Ongoing", ignoreCase = true) -> MangaState.ONGOING
			statusText.contains("Completed", ignoreCase = true) ||
				statusText.contains("Tamat", ignoreCase = true) -> MangaState.FINISHED
			else -> null
		}

		val authorText = infoTable?.selectFirst("tr:has(td:contains(Pengarang)) td:last-child,
			span.imptdt:contains(Author)")?.text()?.trim().orEmpty()
		val authors = if (authorText.isNotBlank()) setOf(authorText) else emptySet()

		val tags = doc.select("div.gnr a, div.mgen a").map { a ->
			MangaTag(
				key = a.attr("href").trimEnd('/').substringAfterLast('/'),
				title = a.text().trim(),
				source = source,
			)
		}.toSet()

		val chapters = doc.select("div.bxcl li, #chapterlist li").mapNotNull { li ->
			val a = li.selectFirst("a") ?: return@mapNotNull null
			val href = a.attr("href").ifBlank { return@mapNotNull null }
			val chSlug = href.trimEnd('/').substringAfterLast('/')
			val chUrl = "/chapter/$chSlug"
			val chTitle = a.selectFirst("span.chapter, span.chapternum")?.text()
				?: a.text().trim()
			val dateText = li.selectFirst("span.date, span.chapterdate")?.text().orEmpty().trim()
			val number = Regex("""(\d+(?:\.\d+)?)""").find(chTitle)?.value?.toFloatOrNull() ?: 0f

			MangaChapter(
				id = generateUid(chUrl),
				title = chTitle,
				url = chUrl,
				number = number,
				volume = 0,
				scanlator = null,
				uploadDate = parseDate(dateText),
				branch = null,
				source = source,
			)
		}.sortBy { it.number }

		return manga.copy(
			title = title,
			description = desc,
			state = state,
			authors = authors,
			contentRating = ContentRating.SAFE,
			tags = tags,
			chapters = chapters,
			coverUrl = cover ?: manga.coverUrl,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		// URL chapter: /chapter/slug → api: api.komiku.id/ch/slug/
		val slug = chapter.url.trimEnd('/').substringAfterLast('/')
		val url = "https://$apiDomain/ch/$slug/"
		val doc = webClient.httpGet(url, buildHeaders()).parseHtml()

		return doc.select("div#Baca_Komik img, div.entry-content img").mapNotNull { img ->
			val src = img.attr("data-src").ifBlank { img.attr("src") }.ifBlank { return@mapNotNull null }
			// Skip placeholder / spacer
			if (src.endsWith("spacer.gif") || src.contains("loading")) return@mapNotNull null
			MangaPage(
				id = generateUid(src),
				url = src,
				preview = null,
				source = source,
			)
		}
	}

	private fun parseDate(text: String): Long {
		if (text.isBlank()) return 0L
		// Coba format standar dulu
		val formats = arrayOf("MMMM dd, yyyy", "dd MMM yyyy", "yyyy-MM-dd")
		for (fmt in formats) {
			try {
				return SimpleDateFormat(fmt, Locale.ENGLISH).parse(text)?.time ?: continue
			} catch (_: Exception) {}
		}
		// Fallback ke relative date
		val lower = text.lowercase(Locale.ROOT)
		val num = Regex("""(\d+)""").find(lower)?.value?.toLongOrNull() ?: 1L
		val now = System.currentTimeMillis()
		return when {
			lower.contains("menit") || lower.contains("minute") -> now - num * 60_000L
			lower.contains("jam") || lower.contains("hour") -> now - num * 3_600_000L
			lower.contains("hari") || lower.contains("day") -> now - num * 86_400_000L
			lower.contains("minggu") || lower.contains("week") -> now - num * 604_800_000L
			lower.contains("bulan") || lower.contains("month") -> now - num * 2_592_000_000L
			lower.contains("tahun") || lower.contains("year") -> now - num * 31_536_000_000L
			else -> 0L
		}
	}

	private fun buildGenreList(): Set<MangaTag> = listOf(
		"action" to "Action", "adventure" to "Adventure", "comedy" to "Comedy",
		"drama" to "Drama", "fantasy" to "Fantasy", "historical" to "Historical",
		"horror" to "Horror", "isekai" to "Isekai", "mecha" to "Mecha",
		"mystery" to "Mystery", "psychological" to "Psychological",
		"romance" to "Romance", "sci-fi" to "Sci-Fi", "slice-of-life" to "Slice of Life",
		"sports" to "Sports", "superhero" to "Superhero", "thriller" to "Thriller",
		"tragedy" to "Tragedy", "wuxia" to "Wuxia", "ecchi" to "Ecchi",
		"school-life" to "School Life", "supernatural" to "Supernatural",
		"josei" to "Josei", "seinen" to "Seinen", "shoujo" to "Shoujo", "shounen" to "Shounen",
	).map { (key, title) -> MangaTag(key = key, title = title, source = source) }.toSet()
}
