package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("KOMIKAPK", "KomikApk", "id", ContentType.HENTAI)
internal class Komikapk(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.KOMIKAPK, 28) {

	override val configKeyDomain = ConfigKey.Domain("komikapk.app")

	private val cdnHost = "https://s1.cdn-guard.com"
	private val cdnChapterPrefix = "$cdnHost/komikapk2-chapter/"

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("Referer", "https://$domain/")
		.add("Origin", "https://$domain")
		.add(
			"User-Agent",
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
		)
		.add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
		.add("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
		.build()

	private fun noCacheHeaders(): Headers = getRequestHeaders().newBuilder()
		.set("Cache-Control", "no-cache, no-store, max-age=0")
		.set("Pragma", "no-cache")
		.set("x-sveltekit-invalidated", "1")
		.build()

	private val baseUrl: String get() = "https://$domain/"

	/**
	 * Banyak link di situs ini berupa path relatif (mis. `../../../../komik/slug`
	 * atau `./komik/slug`). Resolve jadi absolute path (`/komik/slug`) supaya
	 * selector maupun penyimpanan URL tetap konsisten.
	 */
	private fun resolvePath(href: String): String? {
		val trimmed = href.trim()
		if (trimmed.isEmpty()) return null
		return try {
			baseUrl.toHttpUrl().resolve(trimmed)?.encodedPath
		} catch (_: Exception) {
			null
		}
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = false,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	private suspend fun fetchTags(): Set<MangaTag> {
		return try {
			val doc = webClient.httpGet(
				"https://$domain/pustaka/semua/semua/terbaru/1?include_adult=true",
				noCacheHeaders(),
			).parseHtml()
			doc.select("a[href*='/pustaka/']").mapNotNull { a ->
				val path = resolvePath(a.attr("href")) ?: return@mapNotNull null
				val segments = path.trim('/').split('/')
				if (segments.size < 5 || segments[0] != "pustaka") return@mapNotNull null
				val tagSlug = segments[2]
				if (tagSlug.isBlank() || tagSlug == "semua") return@mapNotNull null
				MangaTag(
					key = tagSlug,
					title = a.text().trim().ifBlank { tagSlug.replace('-', ' ') },
					source = source,
				)
			}.distinctBy { it.key }.toSet()
		} catch (_: Exception) {
			emptySet()
		}
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildListUrl(page, filter, order)
		val doc = webClient.httpGet(url, noCacheHeaders()).parseHtml()
		return parseMangaList(doc)
	}

	private fun buildListUrl(page: Int, filter: MangaListFilter, order: SortOrder): String {
		if (!filter.query.isNullOrEmpty()) {
			return "https://$domain/pencarian?q=${filter.query.urlEncoded()}&page=$page&is-adult=on"
		}

		val type = when (filter.types.firstOrNull()) {
			ContentType.MANGA -> "manga"
			ContentType.MANHWA -> "manhwa"
			ContentType.MANHUA -> "manhua"
			else -> "semua"
		}
		val tag = filter.tags.firstOrNull()?.key ?: "semua"

		return "https://$domain/pustaka/$type/$tag/terbaru/$page?include_adult=true"
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select("a[href*='komik/']").mapNotNull { element ->
			val rawHref = element.attr("href")
			if (rawHref.contains('?')) return@mapNotNull null
			val path = resolvePath(rawHref) ?: return@mapNotNull null
			if (!path.startsWith("/komik/")) return@mapNotNull null

			val slug = path.removePrefix("/komik/").trim('/')
			if (slug.isBlank() || slug.contains('/') || slug == "register") return@mapNotNull null

			val coverUrl = element.selectFirst("img")?.src()
				?.takeIf { it.isNotBlank() }
				?: "$cdnHost/komikapk2-cover/$slug.webp"

			val title = element.selectFirst("div.font-display")?.text()?.trim()
				?: element.selectFirst("div[class*=font-display]")?.text()?.trim()
				?: element.attr("title").takeIf { it.isNotBlank() }
				?: slug.replace('-', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }

			Manga(
				id = generateUid(path),
				title = title,
				altTitles = emptySet(),
				url = path,
				publicUrl = "https://$domain$path",
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
		val doc = webClient.httpGet(manga.publicUrl, noCacheHeaders()).parseHtml()

		val title = doc.selectFirst("h1.font-label")?.text()?.trim()
			?: doc.selectFirst("h1")?.text()?.trim()
			?: manga.title

		val cover = doc.selectFirst("img[src*='komikapk2-cover']")?.src()
			?: doc.selectFirst("img.h-\\[200px\\]")?.src()
			?: manga.coverUrl

		val description = doc.selectFirst("div.font-display.mt-5.text-center")?.text()?.trim()
			?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
			?: ""

		val tags = doc.select("a[href*='/pustaka/']").mapNotNull { a ->
			val path = resolvePath(a.attr("href")) ?: return@mapNotNull null
			val segments = path.trim('/').split('/')
			if (segments.size < 3 || segments[0] != "pustaka") return@mapNotNull null
			val tagSlug = segments[2]
			if (tagSlug.isBlank() || tagSlug == "semua") return@mapNotNull null
			MangaTag(
				title = a.text().trim().ifBlank { tagSlug.replace('-', ' ') },
				key = tagSlug,
				source = source,
			)
		}.toSet()

		val htmlLower = doc.html().lowercase()
		val state = when {
			"tamat" in htmlLower || "completed" in htmlLower || "selesai" in htmlLower -> MangaState.FINISHED
			else -> MangaState.ONGOING
		}

		val adultKeywords = setOf(
			"adult", "mature", "smut", "ecchi", "hentai", "18+", "nakadashi",
			"rape", "incest", "milf", "loli", "shota", "futanari", "gangbang",
			"creampie", "ntr", "netorare",
		)
		val contentRating = if (tags.any { tag -> adultKeywords.any { it in tag.title.lowercase() } }) {
			ContentRating.ADULT
		} else {
			ContentRating.SAFE
		}

		val mangaSlug = manga.url.removePrefix("/komik/").trimEnd('/').substringBefore('/')

		val chapters = doc.select("a[href*='komik/']").mapNotNull { a ->
			val path = resolvePath(a.attr("href")) ?: return@mapNotNull null
			if (!path.startsWith("/komik/$mangaSlug/")) return@mapNotNull null

			val segments = path.trim('/').split('/')
			if (segments.size < 4) return@mapNotNull null

			val uploaderSlug = segments[2]
			val chapterName = segments[3]
			val titleText = a.text().trim().ifBlank { "Chapter $chapterName" }

			val number = parseChapterNumber(titleText)
				?: chapterName.toFloatOrNull()
				?: chapterName.filter { it.isDigit() || it == '.' }.toFloatOrNull()
				?: 0f

			MangaChapter(
				id = generateUid(path),
				title = titleText,
				url = path,
				number = number,
				volume = 0,
				scanlator = uploaderSlug,
				uploadDate = 0L,
				branch = null,
				source = source,
			)
		}.distinctBy { it.url }
			.sortedBy { it.number }

		return manga.copy(
			title = title,
			description = description,
			coverUrl = cover,
			largeCoverUrl = cover,
			tags = tags,
			state = state,
			contentRating = contentRating,
			chapters = chapters,
		)
	}

	private fun parseChapterNumber(name: String): Float? {
		val regex = Regex("""(?:chapter|ch\.?|bab|episode|ep\.?)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
		return regex.find(name)?.groupValues?.get(1)?.toFloatOrNull()
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain).trimEnd('/')
		val doc = webClient.httpGet("$chapterUrl?_=${System.currentTimeMillis()}", noCacheHeaders()).parseHtml()
		return parsePagesFromHtml(doc)
	}

	private fun rewriteImageUrl(raw: String): String {
		if (raw.isBlank()) return raw
		if (raw.startsWith(cdnHost)) return raw
		val idx = raw.indexOf("komikapk2-chapter/")
		if (idx >= 0) return cdnHost + "/" + raw.substring(idx)
		if (raw.startsWith("//")) return "https:$raw"
		if (raw.startsWith("/")) return "https://$domain$raw"
		return raw
	}

	private fun parsePagesFromHtml(doc: Document): List<MangaPage> {
		val selectors = listOf(
			"img[alt*='image-komik']",
			"img[src*='komikapk2-chapter']",
			"img[data-src*='komikapk2-chapter']",
			"img[src*='cdn-guard']",
			"section img",
		)

		for (sel in selectors) {
			val pages = doc.select(sel).mapNotNull { img ->
				val src = listOf("src", "data-src", "data-lazy-src", "data-original")
					.map { img.attr(it).trim() }
					.firstOrNull { it.isNotBlank() }
					?: return@mapNotNull null
				if (src.contains("loading.gif") || src.contains("placeholder")) return@mapNotNull null
				val url = rewriteImageUrl(src)
				MangaPage(id = generateUid(url), url = url, preview = null, source = source)
			}.distinctBy { it.url }
			if (pages.isNotEmpty()) return pages
		}
		return emptyList()
	}
}
