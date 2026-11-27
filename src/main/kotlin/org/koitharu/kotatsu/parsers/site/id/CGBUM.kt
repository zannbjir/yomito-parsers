package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrlOrNull
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.urlEncoded
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.EnumSet

@MangaSourceParser("CGBUM", "CGBUM", "id")
internal class Cgbum(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.CGBUM, 24) {

	override val configKeyDomain = ConfigKey.Domain("cgbum.com")

	private val random = SecureRandom()

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36")
		.add("Referer", "https://$domain/")
		.add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
		.add("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
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

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val tags = runCatching { fetchTags() }.getOrDefault(emptySet())
		return MangaListFilterOptions(
			availableTags = tags,
			availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
			availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA),
		)
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val result = LinkedHashSet<MangaTag>()
		webClient.httpGet("https://$domain/daftar-komik", getRequestHeaders())
			.parseHtml()
			.select("input[name=\"genres[]\"]")
			.mapNotNull { it.attr("value").trim().ifEmpty { null } }
			.distinctBy { it.lowercase() }
			.forEach { value ->
				result += MangaTag(
					title = value.replaceFirstChar { it.uppercaseChar() },
					key = value,
					source = source,
				)
			}
		return result
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val params = ArrayList<String>(6)
		val url = buildString {
			append("https://")
			append(domain)
			if (!filter.query.isNullOrEmpty()) {
				append("/cari")
				params += "q=" + filter.query.urlEncoded()
			} else {
				append("/daftar-komik")
				params += "sort=" + when (order) {
					SortOrder.NEWEST -> "newest"
					SortOrder.POPULARITY -> "views"
					else -> "latest"
				}
				when (filter.states.firstOrNull()) {
					MangaState.ONGOING -> params += "status=ongoing"
					MangaState.FINISHED -> params += "status=tamat"
					else -> Unit
				}
				when (filter.types.firstOrNull()) {
					ContentType.MANGA -> params += "type=manga"
					ContentType.MANHWA -> params += "type=manhwa"
					ContentType.MANHUA -> params += "type=manhua"
					else -> Unit
				}
				filter.tags.firstOrNull()?.let { params += "genres[]=" + it.key.urlEncoded() }
			}
			params += "page=$page"
			append('?')
			append(params.joinToString("&"))
		}
		val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
		return parseMangaList(doc)
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		val result = ArrayList<Manga>(24)
		doc.select("article.comic-card").forEach { card ->
			val link = card.selectFirst("a[href*=\"/komik/\"]") ?: return@forEach
			val relUrl = link.attrAsRelativeUrlOrNull("href") ?: return@forEach
			if (!relUrl.startsWith("/komik/")) return@forEach
			val title = card.selectFirst(".comic-card-title")?.text()?.trim()
				?: link.selectFirst("img")?.attr("alt")?.trim()
				?: return@forEach
			if (title.isEmpty()) return@forEach
			val cover = card.selectFirst("img")?.let { img ->
				img.attr("data-src").ifBlank { img.attr("src") }
			}?.ifBlank { null }
			val isAdult = card.attr("data-adult") == "1" ||
				card.selectFirst(".badge-pornhwa, .adult-sensitive-cover") != null
			result += Manga(
				id = generateUid(relUrl),
				title = title,
				altTitles = emptySet(),
				url = relUrl,
				publicUrl = relUrl.toAbsoluteUrl(domain),
				coverUrl = cover,
				largeCoverUrl = null,
				rating = RATING_UNKNOWN,
				contentRating = if (isAdult) ContentRating.ADULT else null,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
		return result
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain), getRequestHeaders()).parseHtml()

		val title = doc.selectFirst("h1")?.text()?.trim()
			?.removeSuffix(" Bahasa Indonesia")
			?.removeSuffix(" Indonesia")
			?.takeIf { it.isNotEmpty() }
			?: manga.title

		val altTitles = doc.selectFirst(".comic-alt-title")?.text()
			?.split(',')
			?.map { it.trim() }
			?.filter { it.isNotEmpty() }
			?.toSet()
			?: emptySet()

		val cover = doc.selectFirst(".comic-cover img")?.let { img ->
			img.attr("data-src").ifBlank { img.attr("src") }
		}?.ifBlank { null }
			?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
			?: manga.coverUrl

		val tags = doc.select(".comic-genres a.genre-pill, .comic-genres a").mapNotNullToSet { a ->
			val tagTitle = a.text().trim()
			if (tagTitle.isEmpty()) null else MangaTag(tagTitle, tagTitle.lowercase(), source)
		}

		val description = doc.selectFirst(".comic-synopsis .synopsis-content")
			?.text()?.trim()?.ifBlank { null }
			?: doc.selectFirst(".comic-synopsis")?.text()?.trim()?.ifBlank { null }
			?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.ifBlank { null }
			?: ""

		val stateText = doc.selectFirst(".badge-status")?.text()?.trim()?.lowercase()
		val state = when {
			stateText == null -> null
			stateText.contains("ongoing") -> MangaState.ONGOING
			stateText.contains("tamat") || stateText.contains("completed") || stateText.contains("end") -> MangaState.FINISHED
			stateText.contains("hiatus") -> MangaState.PAUSED
			else -> null
		}

		var author: String? = null
		doc.select(".meta-row").forEach { row ->
			val label = row.selectFirst(".meta-label")?.text()?.trim()?.lowercase() ?: return@forEach
			val value = row.selectFirst(".meta-value")?.text()?.trim() ?: return@forEach
			if (label.contains("author") && value != "-") {
				author = value
			}
		}

		val isAdult = doc.selectFirst("article.comic-detail .badge-pornhwa, article.comic-detail .adult-sensitive-cover") != null

		val chapters = doc.select(".chapter-grid a.ch-grid-item").mapChapters(reversed = true) { index, el ->
			val chUrl = el.attrAsRelativeUrlOrNull("href") ?: return@mapChapters null
			val chTitle = el.text().trim().ifBlank { "Chapter ${index + 1}" }
			val number = el.attr("data-chapter-sort").toFloatOrNull()
				?: el.attr("data-chapter").toFloatOrNull()
				?: chTitle.substringAfterLast(' ').toFloatOrNull()
				?: (index + 1).toFloat()
			MangaChapter(
				id = generateUid(chUrl),
				title = chTitle,
				url = chUrl,
				number = number,
				volume = 0,
				scanlator = null,
				uploadDate = 0L,
				branch = null,
				source = source,
			)
		}

		return manga.copy(
			title = title,
			altTitles = altTitles,
			coverUrl = cover,
			largeCoverUrl = cover,
			description = description,
			tags = tags,
			state = state,
			authors = setOfNotNull(author),
			contentRating = if (isAdult) ContentRating.ADULT else manga.contentRating,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(chapterUrl, getRequestHeaders()).parseHtml()
		val pages = runCatching { fetchAccessPages(doc, chapterUrl) }.getOrNull()
		if (!pages.isNullOrEmpty()) return pages
		return parseLegacyPages(doc)
	}

	private suspend fun fetchAccessPages(doc: Document, chapterUrl: String): List<MangaPage> {
		val script = doc.selectFirst("script:containsData(__READER_ACCESS)")?.data() ?: return emptyList()
		val configJson = READER_ACCESS_REGEX.find(script)?.groupValues?.get(1) ?: return emptyList()
		val config = JSONObject(configJson)
		val endpoint = config.optString("endpoint").takeIf { it.isNotBlank() } ?: return emptyList()
		val pageToken = config.optString("page").takeIf { it.isNotBlank() } ?: return emptyList()
		val accessUrl = when {
			endpoint.startsWith("http") -> endpoint.toHttpUrl()
			endpoint.startsWith('/') -> "https://$domain$endpoint".toHttpUrl()
			else -> "https://$domain/$endpoint".toHttpUrl()
		}
		val headers = getRequestHeaders().newBuilder()
			.set("Referer", chapterUrl)
			.set("Origin", "https://$domain")
			.add("X-Reader-Access", "1")
			.build()
		val sid = randomHex(24)
		var data = postAccess(accessUrl, sid, pageToken, null, null, headers)
		var attempts = 0
		while (data.optJSONObject("challenge") != null && attempts < 2) {
			val challenge = data.getJSONObject("challenge")
			val ticket = challenge.optString("ticket")
			val seed = challenge.optString("seed")
			val difficulty = challenge.optInt("difficulty", 12)
			val nonce = solveAccessChallenge(seed, difficulty) ?: return emptyList()
			data = postAccess(accessUrl, sid, pageToken, ticket, nonce, headers)
			attempts++
		}
		val resources = data.optString("resources")
		val urls = if (resources.startsWith("2.")) {
			decryptResources(resources, pageToken)
		} else {
			val images = data.optJSONArray("images") ?: return emptyList()
			val list = ArrayList<String>(images.length())
			for (i in 0 until images.length()) {
				images.optString(i).takeIf { it.startsWith("http") }?.let { list += it }
			}
			val layout = data.optString("layout")
			if (layout.startsWith("1.")) deobfuscateImages(list, layout) else list
		}
		return urls.filter { it.startsWith("http") }.map { url ->
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun postAccess(
		url: HttpUrl,
		sid: String,
		page: String,
		ticket: String?,
		nonce: String?,
		headers: Headers,
	): JSONObject {
		val body = JSONObject()
			.put("sid", sid)
			.put("page", page)
			.put("token", "")
		if (!ticket.isNullOrBlank()) body.put("ticket", ticket)
		if (!nonce.isNullOrBlank()) body.put("nonce", nonce)
		return webClient.httpPost(url, body, headers).parseJson()
	}

	private fun randomHex(size: Int): String {
		val data = ByteArray(size)
		random.nextBytes(data)
		val chars = CharArray(size * 2)
		for (i in data.indices) {
			val value = data[i].toInt() and 0xFF
			chars[i * 2] = HEX_ALPHABET[value ushr 4]
			chars[i * 2 + 1] = HEX_ALPHABET[value and 0x0F]
		}
		return String(chars)
	}

	private fun solveAccessChallenge(seed: String, difficulty: Int): String? {
		if (seed.length != 32) return null
		if (difficulty <= 0) return "0"
		val digest = MessageDigest.getInstance("SHA-256")
		for (n in 0 until MAX_POW_ATTEMPTS) {
			val nonce = n.toString()
			val hash = digest.digest("$seed$nonce".toByteArray(Charsets.UTF_8))
			if (meetsDifficulty(hash, difficulty)) return nonce
		}
		return null
	}

	private fun meetsDifficulty(hash: ByteArray, difficulty: Int): Boolean {
		val fullBytes = difficulty / 8
		val remainingBits = difficulty % 8
		for (i in 0 until fullBytes) {
			if (hash[i].toInt() != 0) return false
		}
		if (remainingBits > 0) {
			val mask = (0xFF shl (8 - remainingBits)) and 0xFF
			if ((hash[fullBytes].toInt() and mask) != 0) return false
		}
		return true
	}

	private fun decryptResources(resources: String, pageToken: String): List<String> {
		val signature = pageToken.takeLast(64)
		if (signature.length != 64) return emptyList()
		val key = ByteArray(32) { index ->
			signature.substring(index * 2, index * 2 + 2).toInt(16).toByte()
		}
		val encoded = resources.removePrefix("2.")
			.replace('-', '+')
			.replace('_', '/')
		val padded = encoded + "=".repeat((4 - encoded.length % 4) % 4)
		val binary = runCatching { Base64.getDecoder().decode(padded) }.getOrNull() ?: return emptyList()
		val decoded = ByteArray(binary.size) { index ->
			(binary[index].toInt() xor key[index % 32].toInt()).toByte()
		}
		val packed = runCatching {
			JSONArray(String(decoded, Charsets.UTF_8))
		}.getOrNull() ?: return emptyList()
		if (packed.length() < 3 || packed.optString(2) != signature) return emptyList()
		val bases = packed.optJSONArray(0) ?: return emptyList()
		val entries = packed.optJSONArray(1) ?: return emptyList()
		val result = ArrayList<String>(entries.length())
		for (i in 0 until entries.length()) {
			val entry = entries.optJSONArray(i) ?: continue
			if (entry.length() < 2) continue
			val baseIndex = entry.optInt(0, -1)
			if (baseIndex < 0 || baseIndex >= bases.length()) continue
			val base = bases.optString(baseIndex)
			val suffix = entry.optString(1)
			if (base.startsWith("http")) result += base + suffix
		}
		return result
	}

	private fun deobfuscateImages(urls: List<String>, layout: String): List<String> {
		if (urls.isEmpty()) return emptyList()
		val seed = layout.substring(2).toLongOrNull(16)?.toInt() ?: return emptyList()
		var n = 0
		var k = 0
		fun amount(size: Int): Int = when {
			size < 1 -> 0
			size <= 5 -> 1
			size <= 20 -> 2
			else -> 3 + (seed and 1)
		}
		for (d in 1..4) {
			val size = urls.size - d
			if (size > 0 && amount(size) == d) {
				n = size
				k = d
				break
			}
		}
		if (n <= 0) return emptyList()
		var state = if (seed != 0) seed else 0x9e3779b9.toInt()
		val span = n - 1
		fun step(): Long {
			state = state xor (state shl 13)
			state = state xor (state ushr 17)
			state = state xor (state shl 5)
			return state.toLong() and 0xFFFFFFFFL
		}
		val gaps = HashSet<Int>(k)
		for (i in 0 until k) {
			val low = 1 + (i * span) / k
			val high = ((i + 1) * span) / k
			val offset = if (n == 1) 1L else low + step() % (high - low + 1)
			gaps += (offset + i).toInt()
		}
		return urls.filterIndexed { index, _ -> index !in gaps }
	}

	private fun parseLegacyPages(doc: Document): List<MangaPage> {
		val urls = LinkedHashSet<String>()
		doc.select("#readerImages .page-container").forEach { el ->
			val url = el.attr("data-url").trim().ifBlank { el.attr("data-path").trim() }
			if (url.startsWith("http")) {
				urls += url
			}
		}
		if (urls.isEmpty()) {
			doc.select("#readerImages img").forEach { img ->
				val url = img.attr("data-src").ifBlank { img.attr("src") }.trim()
				if (url.startsWith("http")) {
					urls += url
				}
			}
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

	private companion object {
		private const val MAX_POW_ATTEMPTS = 5_000_000
		private val HEX_ALPHABET = "0123456789abcdef".toCharArray()
		private val READER_ACCESS_REGEX = Regex(
			"""__READER_ACCESS\s*=\s*(\{.*?})\s*;""",
			RegexOption.DOT_MATCHES_ALL,
		)
	}
}
