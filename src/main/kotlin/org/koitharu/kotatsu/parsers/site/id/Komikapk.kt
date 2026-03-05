package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("KOMIKAPK", "KomikApk", "id")
internal class Komikapk(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.KOMIKAPK, 20) {

	override val configKeyDomain = ConfigKey.Domain("komikapk.app")

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("Referer", "https://$domain/")
		.add("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
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

	// Genre/Tag mapping
	private val tagsMap = mapOf(
		"action" to "Action",
		"adventure" to "Adventure",
		"comedy" to "Comedy",
		"drama" to "Drama",
		"ecchi" to "Ecchi",
		"fantasy" to "Fantasy",
		"harem" to "Harem",
		"horror" to "Horror",
		"isekai" to "Isekai",
		"martial-arts" to "Martial Arts",
		"mature" to "Mature",
		"mystery" to "Mystery",
		"psychological" to "Psychological",
		"romance" to "Romance",
		"school-life" to "School Life",
		"sci-fi" to "Sci-Fi",
		"seinen" to "Seinen",
		"shoujo" to "Shoujo",
		"shounen" to "Shounen",
		"slice-of-life" to "Slice of Life",
		"supernatural" to "Supernatural",
		"thriller" to "Thriller",
		"tragedy" to "Tragedy",
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = tagsMap.map { (key, title) ->
			MangaTag(title = title, key = key, source = source)
		}.toSet(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = EnumSet.of(
			MangaContentType.MANGA,
			MangaContentType.MANHWA,
			MangaContentType.MANHUA,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildListUrl(page, filter, order)
		val doc = webClient.httpGet(url).parseHtml()
		return parseMangaList(doc)
	}

	private fun buildListUrl(page: Int, filter: MangaListFilter, order: SortOrder): String {
		// Search URL
		if (!filter.query.isNullOrEmpty()) {
			return "https://$domain/pencarian?q=${filter.query.urlEncoded()}&page=$page"
		}

		// Build pustaka URL
		val type = when (filter.contentType) {
			MangaContentType.MANGA -> "manga"
			MangaContentType.MANHWA -> "manhwa"
			MangaContentType.MANHUA -> "manhua"
			else -> "semua"
		}

		val tag = filter.tags.firstOrNull()?.key ?: "semua"
		val sort = when (order) {
			SortOrder.POPULARITY -> "populer"
			else -> "terbaru"
		}

		return "https://$domain/pustaka/$type/$tag/$sort/$page"
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select("a[href^='/komik/']").mapNotNull { element ->
			val href = element.attr("href")
			val slug = href.removePrefix("/komik/").removeSuffix("/")

			// Get cover
			val coverImg = element.selectFirst("img[src*='cdn-guard']")
			val coverUrl = coverImg?.src() ?: return@mapNotNull null

			// Get title
			val titleEl = element.selectFirst("div.font-display")
			val title = titleEl?.text()?.trim() ?: return@mapNotNull null

			Manga(
				id = generateUid(slug),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = "https://$domain$href",
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = coverUrl,
				largeCoverUrl = coverUrl,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}.distinctBy { it.id }
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.publicUrl).parseHtml()

		// Title
		val title = doc.selectFirst("h1.font-label")?.text()?.trim() ?: manga.title

		// Cover
		val cover = doc.selectFirst("img.h-\\[200px\\]")?.src() ?: manga.coverUrl

		// Description
		val description = doc.selectFirst("div.font-display.mt-5.text-center")?.text()?.trim()

		// Tags/Genres
		val tags = doc.select("a[href^='/pustaka/semua/'][href*='/terbaru/']").mapNotNull { a ->
			val tagSlug = a.attr("href").split("/").getOrNull(3) ?: return@mapNotNull null
			MangaTag(
				title = a.text().trim(),
				key = tagSlug,
				source = source,
			)
		}.toSet()

		// State - check for completed status
		val state = when {
			doc.html().contains("completed", ignoreCase = true) ||
			doc.html().contains("tamat", ignoreCase = true) -> MangaState.FINISHED
			else -> MangaState.ONGOING
		}

		// Author
		val authors = doc.selectFirst("div:contains(BY:)")
			?.text()?.removePrefix("BY:")?.trim()
			?.let { setOf(it) } ?: emptySet()

		// NSFW detection
		val contentRating = if (tags.any { it.title.lowercase() in listOf("mature", "smut", "ecchi", "adult") }) {
			ContentRating.ADULT
		} else {
			null
		}

		// Chapters
		val chapters = doc.select("a[href^='/komik/'][href*='/kmapk/']").mapNotNull { a ->
			val chapterUrl = a.attr("href")
			val chapterSlug = chapterUrl.split("/").lastOrNull() ?: return@mapNotNull null

			val chapterTitle = a.text().trim()
			val number = chapterSlug.toFloatOrNull() ?: parseChapterNumber(chapterTitle)

			MangaChapter(
				id = generateUid(chapterUrl),
				title = chapterTitle,
				url = chapterUrl,
				number = number,
				volume = 0,
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				source = source,
			)
		}.distinctBy { it.url }.sortedByDescending { it.number }

		return manga.copy(
			title = title,
			description = description,
			coverUrl = cover,
			largeCoverUrl = cover,
			tags = tags,
			state = state,
			authors = authors,
			contentRating = contentRating,
			chapters = chapters,
		)
	}

	private fun parseChapterNumber(name: String): Float {
		val regex = Regex("""(?:chapter|ch\.?)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
		val match = regex.find(name)
		return match?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()

		return doc.select("section img[src*='.jpg'], section img[src*='.png'], section img[src*='.webp']").mapNotNull { img ->
			val imageUrl = img.src() ?: return@mapNotNull null

			if (imageUrl.isBlank() || !imageUrl.contains("cdn-guard")) {
				return@mapNotNull null
			}

			MangaPage(
				id = generateUid(imageUrl),
				url = imageUrl,
				preview = null,
				source = source,
			)
		}.distinctBy { it.id }
	}
}
