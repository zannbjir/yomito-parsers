package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

/**
 * v2.doujindesu.fun — Next.js App Router SPA, not the same stack as doujindesu.tv.
 *
 * Data is embedded as an RSC (React Server Component) stream in the initial HTML:
 *   <script>self.__next_f.push([1, "<escaped-json-fragment>"])</script>
 * On list pages the stream contains a `"mangas":[...]` array; on detail pages it
 * contains a `"chapters":[...]` array plus manga metadata.
 *
 * Chapter image URLs are NOT present in the initial SSR HTML — the reader page
 * renders them client-side, fetching from the site's /image-proxy route. We
 * extract them from the post-JS HTML via the browser-like user agent; if nothing
 * is found we fall back to RSC endpoint.
 */
@MangaSourceParser("DOUJINDESUFUN", "DoujinDesu.fun", "id", ContentType.HENTAI)
internal class DoujinDesuFunParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.DOUJINDESUFUN, pageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain("v2.doujindesu.fun")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("Referer", "https://$domain/")
		.add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

	override val filterCapabilities = MangaListFilterCapabilities(
		isMultipleTagsSupported = false,
		isSearchSupported = true,
		isSearchWithFiltersSupported = false,
	)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = buildGenres(),
		availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.DOUJINSHI),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = urlBuilder().apply {
			addPathSegment("manga")
			if (page > 1) addQueryParameter("page", page.toString())
			filter.query?.takeIf { it.isNotBlank() }?.let { addQueryParameter("q", it) }
			filter.tags.firstOrNull()?.key?.let { addQueryParameter("genre", it) }
			filter.types.firstOrNull()?.let {
				val type = when (it) {
					ContentType.MANGA -> "Manga"
					ContentType.MANHWA -> "Manhwa"
					ContentType.DOUJINSHI -> "Doujinshi"
					else -> null
				}
				if (type != null) addQueryParameter("type", type)
			}
			filter.states.firstOrNull()?.let {
				val status = when (it) {
					MangaState.ONGOING -> "Publishing"
					MangaState.FINISHED -> "Finished"
					else -> null
				}
				if (status != null) addQueryParameter("status", status)
			}
		}.build().toString()

		val raw = webClient.httpGet(url, getRequestHeaders()).parseRaw()
		val payload = decodeRscPayload(raw)
		return parseMangaList(payload)
	}

	private fun parseMangaList(payload: String): List<Manga> {
		val out = ArrayList<Manga>()
		val seen = HashSet<String>()
		// Each manga object contains these keys in order: "slug", ..., "thumb" or "coverImage", "title", ...
		// Match each entry by locating "slug":"<slug>" and collecting the next fields in a window.
		val entryRegex = Regex(
			"\"_id\":\"[^\"]+\",\"slug\":\"([^\"]+)\"[\\s\\S]{0,1200}?\"title\":\"([^\"]*)\""
		)
		for (m in entryRegex.findAll(payload)) {
			val slug = m.groupValues[1]
			if (!seen.add(slug)) continue
			val title = m.groupValues[2].unescapeJsonString()
			val window = payload.substring(m.range.first, (m.range.last + 500).coerceAtMost(payload.length))
			val cover = Regex("\"(?:coverImage|thumb)\":\"([^\"]+)\"").find(window)?.groupValues?.get(1)
			val rating = Regex("\"rating\":([0-9]+(?:\\.[0-9]+)?)[,}]").find(window)?.groupValues?.get(1)?.toFloatOrNull()
			val type = Regex("\"type\":\"([^\"]+)\"").find(window)?.groupValues?.get(1)
			val status = Regex("\"status\":\"([^\"]+)\"").find(window)?.groupValues?.get(1)
			val author = Regex("\"author\":\"([^\"]*)\"").find(window)?.groupValues?.get(1)
				?.takeIf { it.isNotBlank() && it != "Unknown" }
			val urlPath = "/manga/$slug"
			out.add(
				Manga(
					id = generateUid(urlPath),
					title = title.ifBlank { slug.replace('-', ' ').replaceFirstChar { it.uppercase() } },
					altTitles = emptySet(),
					url = urlPath,
					publicUrl = "https://$domain$urlPath",
					rating = rating?.div(10f)?.coerceIn(0f, 1f) ?: RATING_UNKNOWN,
					contentRating = ContentRating.ADULT,
					coverUrl = cover,
					tags = emptySet(),
					state = parseStatus(status),
					authors = setOfNotNull(author),
					largeCoverUrl = null,
					description = null,
					source = source,
				),
			)
		}
		return out
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val raw = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseRaw()
		val payload = decodeRscPayload(raw)

		val slug = manga.url.trimEnd('/').substringAfterLast('/')
		// Title, synopsis, author, rating, status, type, cover
		val title = Regex("\"slug\":\"${Regex.escape(slug)}\"[\\s\\S]{0,2000}?\"title\":\"([^\"]+)\"")
			.find(payload)?.groupValues?.get(1)?.unescapeJsonString() ?: manga.title
		val synopsis = Regex("\"synopsis\":\"([^\"]*)\"").find(payload)?.groupValues?.get(1)?.unescapeJsonString()
		val cover = Regex("\"coverImage\":\"([^\"]+)\"").find(payload)?.groupValues?.get(1)
			?: Regex("\"thumb\":\"([^\"]+)\"").find(payload)?.groupValues?.get(1)
		val author = Regex("\"author\":\"([^\"]*)\"").find(payload)?.groupValues?.get(1)
			?.takeIf { it.isNotBlank() && it != "Unknown" }
		val status = Regex("\"status\":\"([^\"]+)\"").find(payload)?.groupValues?.get(1)
		val rating = Regex("\"rating\":([0-9]+(?:\\.[0-9]+)?)[,}]").find(payload)?.groupValues?.get(1)?.toFloatOrNull()
		val altTitle = Regex("\"alternativeTitle\":\"([^\"]*)\"").find(payload)?.groupValues?.get(1)?.unescapeJsonString()
			?.takeIf { it.isNotBlank() }

		// Tags: detail page renders tags as links like "/manga?genre=<value>"
		val tagRegex = Regex("\"href\":\"/manga\\?genre=([^\"]+)\",[^}]{0,200}?\"children\":\"([^\"]+)\"")
		val tags = LinkedHashSet<MangaTag>()
		for (tm in tagRegex.findAll(payload)) {
			val key = tm.groupValues[1].unescapePercent()
			val label = tm.groupValues[2].unescapeJsonString()
			if (key.isBlank() || label.isBlank()) continue
			tags.add(MangaTag(title = label, key = key, source = source))
		}

		val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
			timeZone = TimeZone.getTimeZone("UTC")
		}

		// Chapters array:
		//   {"_id":"...","slug":"<manga>-chapter-N","chapter_index":N,"createdAt":"ISO","title":"N"}
		val chapterRegex = Regex(
			"\"_id\":\"[^\"]+\",\"slug\":\"(${Regex.escape(slug)}-chapter-[^\"]+)\",\"chapter_index\":([0-9]+(?:\\.[0-9]+)?),\"createdAt\":\"([^\"]+)\",\"title\":\"([^\"]*)\""
		)
		val seen = HashSet<String>()
		val chapters = chapterRegex.findAll(payload).mapNotNull { m ->
			val chSlug = m.groupValues[1]
			if (!seen.add(chSlug)) return@mapNotNull null
			val number = m.groupValues[2].toFloatOrNull() ?: return@mapNotNull null
			val createdAt = m.groupValues[3]
			val chTitle = m.groupValues[4].ifBlank { number.toInt().toString() }
			val urlPath = "/read/$slug/$chSlug"
			MangaChapter(
				id = generateUid(urlPath),
				title = "Chapter $chTitle",
				url = urlPath,
				number = number,
				volume = 0,
				scanlator = null,
				uploadDate = parseDate(isoFmt, createdAt),
				branch = null,
				source = source,
			)
		}.toList().sortedBy { it.number }

		return manga.copy(
			title = title,
			altTitles = setOfNotNull(altTitle),
			description = synopsis,
			coverUrl = cover ?: manga.coverUrl,
			state = parseStatus(status),
			authors = setOfNotNull(author),
			tags = if (tags.isEmpty()) manga.tags else tags,
			rating = rating?.div(10f)?.coerceIn(0f, 1f) ?: RATING_UNKNOWN,
			contentRating = ContentRating.ADULT,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val url = chapter.url.toAbsoluteUrl(domain)
		val raw = webClient.httpGet(url, getRequestHeaders()).parseRaw()

		// Strategy 1: reader page rendered HTML contains <img alt="Page N" src="/image-proxy?...">
		val imgRegex = Regex("""<img[^>]+alt="Page\s*\d+"[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE)
		val seen = LinkedHashSet<String>()
		for (m in imgRegex.findAll(raw)) {
			val src = m.groupValues[1]
			if (seen.add(src)) Unit
		}
		if (seen.isEmpty()) {
			// Strategy 2: look inside the RSC payload for pages array / image-proxy URLs
			val payload = decodeRscPayload(raw)
			val proxyRegex = Regex("""/image-proxy\?[^"\s<>]+""")
			for (m in proxyRegex.findAll(payload)) {
				seen.add(m.value)
			}
			// Strategy 3: Any cdn-images.doujindesu.fun images that look like chapter pages
			if (seen.isEmpty()) {
				val cdnRegex = Regex("""https://[a-zA-Z0-9.-]*doujindesu\.fun/[^\s"']+\.(?:jpg|jpeg|png|webp)""")
				for (m in cdnRegex.findAll(payload)) {
					val v = m.value
					if (!v.contains("/covers/") && !v.contains("/logo") && !v.contains("og-image")) {
						seen.add(v)
					}
				}
			}
		}
		return seen.mapIndexed { _, src ->
			val full = if (src.startsWith("/")) "https://$domain$src" else src
			MangaPage(
				id = generateUid(full),
				url = full,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun buildGenres(): Set<MangaTag> = runCatching {
		val url = "https://$domain/genres"
		val raw = webClient.httpGet(url, getRequestHeaders()).parseRaw()
		val payload = decodeRscPayload(raw)
		val result = LinkedHashSet<MangaTag>()
		// Genre list is rendered as links /manga?genre=<value>. Capture value + label.
		val linkRegex = Regex("\"href\":\"/manga\\?genre=([^\"]+)\",[^}]{0,200}?\"children\":\"([^\"]+)\"")
		for (m in linkRegex.findAll(payload)) {
			val key = m.groupValues[1].unescapePercent()
			val label = m.groupValues[2].unescapeJsonString()
			if (key.isBlank() || label.isBlank()) continue
			result.add(MangaTag(title = label, key = key, source = source))
		}
		result
	}.getOrDefault(emptySet())

	/** Concatenate all self.__next_f.push([1, "<escaped-string>"]) strings and unescape. */
	private fun decodeRscPayload(html: String): String {
		val sb = StringBuilder(html.length)
		val pushRegex = Regex("""self\.__next_f\.push\(\[1,"((?:\\.|[^"\\])*)"]\)""")
		for (m in pushRegex.findAll(html)) {
			sb.append(m.groupValues[1].unescapeJsonString())
		}
		return sb.toString()
	}

	private fun String.unescapeJsonString(): String {
		if (indexOf('\\') < 0) return this
		val out = StringBuilder(length)
		var i = 0
		while (i < length) {
			val c = this[i]
			if (c == '\\' && i + 1 < length) {
				val n = this[i + 1]
				when (n) {
					'n' -> { out.append('\n'); i += 2 }
					't' -> { out.append('\t'); i += 2 }
					'r' -> { out.append('\r'); i += 2 }
					'"' -> { out.append('"'); i += 2 }
					'\\' -> { out.append('\\'); i += 2 }
					'/' -> { out.append('/'); i += 2 }
					'u' -> {
						if (i + 5 < length) {
							val hex = substring(i + 2, i + 6)
							val code = hex.toIntOrNull(16)
							if (code != null) {
								out.append(code.toChar()); i += 6
							} else {
								out.append(c); i++
							}
						} else {
							out.append(c); i++
						}
					}
					else -> { out.append(n); i += 2 }
				}
			} else {
				out.append(c); i++
			}
		}
		return out.toString()
	}

	private fun String.unescapePercent(): String = try {
		java.net.URLDecoder.decode(this, "UTF-8")
	} catch (_: Exception) {
		this
	}

	private fun parseStatus(s: String?): MangaState? = when (s?.lowercase(Locale.ROOT)) {
		"publishing", "ongoing" -> MangaState.ONGOING
		"finished", "completed", "tamat" -> MangaState.FINISHED
		"hiatus" -> MangaState.PAUSED
		"dropped", "cancelled", "canceled" -> MangaState.ABANDONED
		else -> null
	}

	private fun parseDate(fmt: SimpleDateFormat, raw: String): Long {
		if (raw.isBlank()) return 0L
		return try { fmt.parse(raw)?.time ?: 0L } catch (_: Exception) { 0L }
	}
}
