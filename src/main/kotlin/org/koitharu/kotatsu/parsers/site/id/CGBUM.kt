package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.HttpStatusException
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.toJSONObjectOrNull
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.EnumSet

@MangaSourceParser("CGBUM", "CGBUM", "id", ContentType.HENTAI)
internal class CgBum(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.CGBUM, pageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain("cgbum.com")

	private val random = SecureRandom()

	@Volatile
	private var accessSid: String? = null

	@Volatile
	private var accessGrant: String? = null

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("User-Agent", config[userAgentKey])
		.add("Referer", "https://$domain/")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.NEWEST_ASC,
	)

	override val filterCapabilities = MangaListFilterCapabilities(
		isMultipleTagsSupported = true,
		isSearchSupported = true,
		isSearchWithFiltersSupported = false,
	)

	private val availableTags = suspendLazy(initializer = ::fetchTags)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = availableTags.get(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
			ContentType.HENTAI,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query
		val url = buildString {
			append("https://").append(domain)
			if (!query.isNullOrEmpty()) {
				append("/cari?q=").append(query.urlEncoded())
				append("&page=").append(page)
				return@buildString
			}
			append("/daftar-komik?page=").append(page)
			append("&sort=").append(
				when (order) {
					SortOrder.POPULARITY -> "views"
					SortOrder.NEWEST -> "newest"
					SortOrder.NEWEST_ASC -> "oldest"
					else -> "latest"
				},
			)
			filter.types.firstOrNull()?.let { type ->
				append("&type=").append(
					when (type) {
						ContentType.MANHWA -> "manhwa"
						ContentType.MANHUA -> "manhua"
						ContentType.HENTAI -> "pornhwa"
						else -> "manga"
					},
				)
			}
			filter.states.firstOrNull()?.let { state ->
				append("&status=").append(if (state == MangaState.FINISHED) "tamat" else "ongoing")
			}
			filter.tags.take(3).forEach { tag ->
				append("&genres%5B%5D=").append(tag.key.urlEncoded())
			}
		}
		val doc = webClient.httpGet(url).parseHtml()
		return doc.select(".comic-grid article.comic-card").mapNotNull { parseCard(it) }
	}

	private fun parseCard(card: Element): Manga? {
		val a = card.selectFirst(".comic-card-title a") ?: card.selectFirst("a.comic-card-cover") ?: return null
		val href = a.attrAsRelativeUrlOrNull("href") ?: return null
		return Manga(
			id = generateUid(href),
			url = href,
			publicUrl = href.toAbsoluteUrl(domain),
			title = a.text().trim().ifEmpty { card.selectFirst("img")?.attr("alt").orEmpty() },
			altTitles = emptySet(),
			coverUrl = card.selectFirst("img")?.src(),
			rating = RATING_UNKNOWN,
			tags = emptySet(),
			state = parseState(card.selectFirst(".badge-status")),
			authors = emptySet(),
			contentRating = if (card.isAdult()) ContentRating.ADULT else null,
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val info = doc.selectFirstOrThrow("article.comic-detail")
		val ld = doc.select("script[type=\"application/ld+json\"]").firstNotNullOfOrNull { script ->
			script.data().toJSONObjectOrNull()?.takeIf { it.optString("@type") == "ComicSeries" }
		}
		val cover = info.selectFirst(".comic-cover")
		val title = ld?.optString("name")?.takeIf { it.isNotBlank() }
			?: info.selectFirst("h1")?.text()?.removeSuffix(" Bahasa Indonesia")?.trim()?.takeIf { it.isNotEmpty() }
			?: manga.title
		val author = info.metaRow("Author")
		val tags = info.select(".comic-genres a.genre-pill").mapNotNullToSet { a ->
			val name = a.text().trim()
			if (name.isEmpty()) null else MangaTag(title = name.toTitleCase(sourceLocale), key = name, source = source)
		}
		val description = info.selectFirst(".synopsis-content")?.html()
			?.replace(Regex("""\s*~\s*CGBUM\s*$"""), "")
		return manga.copy(
			title = title,
			altTitles = setOfNotNull(info.selectFirst(".comic-alt-title")?.text()?.trim()?.takeIf { it.isNotEmpty() }),
			coverUrl = cover?.selectFirst("img")?.src() ?: manga.coverUrl,
			authors = author?.split(',')?.mapNotNullToSet { it.trim().takeIf { a -> a.isNotEmpty() && a != "-" } }
				.orEmpty(),
			tags = tags,
			state = parseState(cover?.selectFirst(".badge-status")) ?: manga.state,
			description = description,
			contentRating = if (cover?.isAdult() == true) ContentRating.ADULT else manga.contentRating,
			chapters = getChapters(doc),
		)
	}

	private suspend fun getChapters(doc: Document): List<MangaChapter> {
		val list = doc.selectFirst(".chapter-list") ?: return emptyList()
		val chapters = ArrayList<MangaChapter>()
		list.select(".chapter-grid a.ch-grid-item").mapNotNullTo(chapters) { a ->
			val href = a.attrAsRelativeUrlOrNull("href") ?: return@mapNotNullTo null
			createChapter(
				url = href,
				chapter = a.attr("data-chapter"),
				sort = a.attr("data-chapter-sort").toDoubleOrNull(),
				label = a.text(),
			)
		}
		val total = list.attr("data-ch-total").toIntOrNull() ?: 0
		val slug = list.attr("data-ch-slug")
		val endpoint = list.attrAsAbsoluteUrlOrNull("data-ch-endpoint")
		var offset = list.attr("data-ch-offset").toIntOrNull() ?: chapters.size
		while (offset < total && slug.isNotEmpty() && endpoint != null) {
			val json = webClient.httpGet("$endpoint?slug=${slug.urlEncoded()}&offset=$offset&limit=100").parseJson()
			val items = json.optJSONArray("items") ?: break
			for (i in 0 until items.length()) {
				val item = items.optJSONObject(i) ?: continue
				chapters += parseBatchChapter(item, slug) ?: continue
			}
			val next = json.optInt("next_offset", offset + items.length())
			if (items.length() == 0 || next <= offset || !json.optBoolean("has_more", false)) break
			offset = next
		}
		return chapters.distinctBy { it.url }.reversed()
	}

	private fun parseBatchChapter(item: JSONObject, slug: String): MangaChapter? {
		val chapter = item.optString("chapter").trim()
		if (chapter.isEmpty()) return null
		val url = item.optString("url").takeIf { it.isNotBlank() }?.toRelativeUrl(domain)
			?: "/baca/$slug/chapter/$chapter"
		val title = item.optString("title").trim()
		return createChapter(
			url = url,
			chapter = chapter,
			sort = if (item.has("chapter_sort")) item.optDouble("chapter_sort") else null,
			label = item.optString("label").ifBlank {
				if (title.isEmpty()) "Ch. $chapter" else "Ch. $chapter - $title"
			},
		)
	}

	private fun createChapter(url: String, chapter: String, sort: Double?, label: String): MangaChapter {
		val number = Regex("""^(\d+)[-.](\d+)$""").find(chapter)?.let { m ->
			"${m.groupValues[1]}.${m.groupValues[2]}".toFloatOrNull()
		} ?: sort?.takeIf { !it.isNaN() }?.toFloat()
		?: Regex("""\d+(\.\d+)?""").find(chapter)?.value?.toFloatOrNull()
		?: 0f
		return MangaChapter(
			id = generateUid(url),
			title = label.trim().ifEmpty { "Ch. $chapter" },
			number = number,
			volume = 0,
			url = url,
			scanlator = null,
			uploadDate = 0L,
			branch = null,
			source = source,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val readerUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(readerUrl).parseHtml()
		val access = doc.select("script").firstNotNullOfOrNull { script ->
			ACCESS_REGEX.find(script.data())?.groupValues?.getOrNull(1)?.toJSONObjectOrNull()
		} ?: throw ParseException("Reader access config not found", readerUrl)
		val pageToken = access.optString("page").takeIf { it.isNotEmpty() }
			?: throw ParseException("Reader access token not found", readerUrl)
		val endpoint = access.optString("endpoint", "/reader-access.php").toAbsoluteUrl(domain)
		val urls = requestImageUrls(endpoint, pageToken, readerUrl)
		if (urls.isEmpty()) {
			throw ParseException("Reader image URLs not found", readerUrl)
		}
		return urls.map { url ->
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun requestImageUrls(
		endpoint: String,
		pageToken: String,
		readerUrl: String,
	): List<String> {
		val headers = Headers.Builder()
			.add("Accept", "application/json, text/plain, */*")
			.add("Origin", "https://$domain")
			.add("Referer", readerUrl)
			.add("X-Reader-Access", "1")
			.add("User-Agent", config[userAgentKey])
			.build()
		var sid = accessSid ?: newSid().also { accessSid = it }
		var grant = accessGrant
		var json: JSONObject
		try {
			json = webClient.httpPost(
				endpoint.toHttpUrl(),
				accessBody(sid, pageToken, grant),
				headers,
			).parseJson()
		} catch (e: HttpStatusException) {
			if (grant == null) throw e
			grant = null
			accessGrant = null
			sid = newSid().also { accessSid = it }
			json = webClient.httpPost(
				endpoint.toHttpUrl(),
				accessBody(sid, pageToken, null),
				headers,
			).parseJson()
		}
		if (json.has("challenge")) {
			val challenge = json.getJSONObject("challenge")
			val nonce = solvePow(
				seed = challenge.getString("seed"),
				difficulty = challenge.optInt("difficulty", 12),
			)
			json = webClient.httpPost(
				endpoint.toHttpUrl(),
				accessBody(sid, pageToken, null)
					.put("ticket", challenge.getString("ticket"))
					.put("nonce", nonce),
				headers,
			).parseJson()
		}
		json.optJSONObject("grant")?.optString("token")?.takeIf { it.isNotEmpty() }?.let {
			accessGrant = it
		}
		return decodeImages(json, pageToken)
	}

	private fun accessBody(sid: String, pageToken: String, grant: String?): JSONObject = JSONObject()
		.put("sid", sid)
		.put("page", pageToken)
		.put("token", grant.orEmpty())

	private fun decodeImages(json: JSONObject, pageToken: String): List<String> {
		val signature = pageToken.takeLast(64)
		json.optString("resources").takeIf { it.startsWith("2.") }?.let {
			return decodeResources(it, signature)
		}
		val array = json.optJSONArray("images") ?: return emptyList()
		val urls = (0 until array.length()).mapNotNull { i ->
			array.optString(i).takeIf { it.isNotEmpty() }
		}
		val layout = json.optString("layout")
		return if (layout.isEmpty()) urls else applyLayout(urls, layout)
	}

	private fun decodeResources(value: String, signature: String): List<String> {
		if (signature.length != 64) error("Invalid reader signature")
		val key = ByteArray(32) { i -> signature.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
		var encoded = value.substring(2).replace('-', '+').replace('_', '/')
		while (encoded.length % 4 != 0) {
			encoded += '='
		}
		val bytes = Base64.getDecoder().decode(encoded)
		for (i in bytes.indices) {
			bytes[i] = (bytes[i].toInt() xor key[i % 32].toInt()).toByte()
		}
		val packed = JSONArray(String(bytes, Charsets.UTF_8))
		if (packed.length() != 3 || packed.getJSONArray(1).length() == 0) {
			error("Invalid reader resource payload")
		}
		val urls = packed.getJSONArray(0)
		val entries = packed.getJSONArray(1)
		if (packed.getString(2) != signature) {
			error("Reader resource signature mismatch")
		}
		val result = ArrayList<String>(entries.length())
		for (i in 0 until entries.length()) {
			val entry = entries.optJSONArray(i) ?: continue
			val index = entry.optInt(0, -1)
			if (index !in 0 until urls.length()) {
				error("Invalid reader resource index")
			}
			result += urls.getString(index) + entry.optString(1)
		}
		return result
	}

	private fun applyLayout(urls: List<String>, layout: String): List<String> {
		if (!LAYOUT_REGEX.matches(layout)) {
			error("Invalid reader layout")
		}
		val seed = layout.substring(2).toLong(16).toInt()
		var count = 0
		var gapsCount = 0
		for (d in 1..4) {
			val size = urls.size - d
			if (size > 0 && amount(size, seed) == d) {
				count = size
				gapsCount = d
				break
			}
		}
		if (count == 0) {
			return urls
		}
		var state = if (seed != 0) seed else 0x9e3779b9.toInt()
		val span = count - 1
		val gaps = HashSet<Int>(gapsCount)
		fun step(): Long {
			state = state xor (state shl 13)
			state = state xor (state ushr 17)
			state = state xor (state shl 5)
			return state.toLong() and 0xFFFFFFFFL
		}
		for (i in 0 until gapsCount) {
			val lo = 1 + (i * span) / gapsCount
			val hi = ((i + 1) * span) / gapsCount
			val index = if (count == 1) {
				1 + i
			} else {
				lo + (step() % (hi - lo + 1)).toInt() + i
			}
			gaps += index
		}
		return urls.filterIndexed { index, _ -> index !in gaps }
	}

	private fun amount(size: Int, seed: Int): Int = when {
		size < 1 -> 0
		size <= 5 -> 1
		size <= 20 -> 2
		else -> 3 + (seed and 1)
	}

	private fun solvePow(seed: String, difficulty: Int): String {
		if (difficulty <= 0) return "0"
		val bits = difficulty.coerceIn(1, 24)
		val digest = MessageDigest.getInstance("SHA-256")
		val seedBytes = seed.toByteArray(Charsets.UTF_8)
		for (n in 0 until MAX_POW_ATTEMPTS) {
			digest.reset()
			digest.update(seedBytes)
			digest.update(n.toString().toByteArray(Charsets.UTF_8))
			val hash = digest.digest()
			var matched = true
			for (bit in 0 until bits) {
				if ((hash[bit ushr 3].toInt() ushr (7 - (bit and 7)) and 1) != 0) {
					matched = false
					break
				}
			}
			if (matched) {
				return n.toString()
			}
		}
		error("Unable to solve reader proof of work")
	}

	private fun newSid(): String {
		val bytes = ByteArray(24)
		random.nextBytes(bytes)
		return buildString(48) {
			bytes.forEach { b ->
				append((b.toInt() and 0xFF).toString(16).padStart(2, '0'))
			}
		}
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/daftar-komik").parseHtml()
		return doc.select("#genreList input[name='genres[]']")
			.mapNotNull { it.attrOrNull("value") }
			.distinctBy { it.lowercase(sourceLocale) }
			.mapTo(LinkedHashSet()) { name ->
				MangaTag(title = name.toTitleCase(sourceLocale), key = name, source = source)
			}
	}

	private fun Element.isAdult(): Boolean =
		attr("data-adult") == "1" || selectFirst(".badge-pornhwa") != null

	private fun Element.metaRow(label: String): String? = select(".comic-meta-simple .meta-row")
		.firstOrNull { it.selectFirst(".meta-label")?.text()?.equals(label, ignoreCase = true) == true }
		?.selectFirst(".meta-value")?.text()?.trim()?.takeIf { it.isNotEmpty() && it != "-" }

	private fun parseState(badge: Element?): MangaState? {
		badge ?: return null
		val text = badge.className() + " " + badge.text()
		return when {
			text.contains("completed", ignoreCase = true) || text.contains("tamat", ignoreCase = true) -> MangaState.FINISHED
			text.contains("ongoing", ignoreCase = true) -> MangaState.ONGOING
			text.contains("hiatus", ignoreCase = true) -> MangaState.PAUSED
			else -> null
		}
	}

	private companion object {
		private const val MAX_POW_ATTEMPTS = 2_000_000
		private val ACCESS_REGEX = Regex("""__READER_ACCESS\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
		private val LAYOUT_REGEX = Regex("""^1\.[a-f0-9]{8}$""")
	}
}
