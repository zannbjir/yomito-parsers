package org.koitharu.kotatsu.parsers.site.mangareader.id

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale
import java.util.TimeZone

@MangaSourceParser("KUMOPOI", "KumoPoi", "id")
internal class KumoPoi(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.KUMOPOI, pageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain("beta.kumopoi.com")

	override val userAgentKey = ConfigKey.UserAgent(DESKTOP_USER_AGENT)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	private val apiHost = "api.kumopoi.com"
	private val cdnHost = "https://kumo.gorae.my.id"

	@Volatile
	private var readerReferer: String? = null

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = false,
		)

	override fun getRequestHeaders(): Headers {
		val referer = readerReferer
		return if (referer == null) {
			super.getRequestHeaders().newBuilder()
				.set("Accept", API_ACCEPT)
				.set("Origin", "https://$domain")
				.set("Referer", "https://$domain/")
				.build()
		} else {
			super.getRequestHeaders().newBuilder()
				.set("Accept", IMAGE_ACCEPT)
				.set("Referer", referer)
				.set("Sec-Fetch-Dest", "image")
				.set("Sec-Fetch-Mode", "no-cors")
				.set("Sec-Fetch-Site", "same-origin")
				.set("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
				.removeAll("Origin")
				.build()
		}
	}

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		if (request.url.encodedPath != "/api/reader/media") {
			return chain.proceed(request)
		}
		val referer = request.url.fragment
			?.takeIf { it.startsWith("ref=") }
			?.substring(4)
			?: readerReferer
			?: "https://$domain/"
		val newRequest = request.newBuilder()
			.removeHeader("Origin")
			.header("User-Agent", DESKTOP_USER_AGENT)
			.header("Accept", IMAGE_ACCEPT)
			.header("Referer", referer)
			.header("Sec-Fetch-Dest", "image")
			.header("Sec-Fetch-Mode", "no-cors")
			.header("Sec-Fetch-Site", "same-origin")
			.header("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
			.build()
		return chain.proceed(newRequest)
	}

	override suspend fun getFavicons(): Favicons {
		val icon = runCatching {
			webClient.httpGet("https://$apiHost/api/v1/settings", getRequestHeaders())
				.parseJson().getJSONObject("data").getString("navicon")
		}.getOrNull()?.takeIf { it.isNotBlank() }
		return Favicons.single("$cdnHost/${icon ?: "settings/navicon_1784968110748.png"}")
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
		availableTags = fetchGenres(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA),
	)

	private suspend fun fetchGenres(): Set<MangaTag> {
		val array = webClient.httpGet("https://$apiHost/api/v1/genres", getRequestHeaders())
			.parseJson().getJSONArray("data")
		val result = LinkedHashSet<MangaTag>()
		for (i in 0 until array.length()) {
			val item = array.optJSONObject(i) ?: continue
			val slug = item.getStringOrNull("slug") ?: continue
			val name = item.getStringOrNull("name") ?: continue
			if (item.optJSONObject("_count")?.optInt("comics", 1) == 0) continue
			result += MangaTag(title = name.toTitleCase(), key = slug, source = source)
		}
		return result
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://$apiHost/api/v1/comics?page=").append(page)
			append("&limit=").append(pageSize)
			append("&excludeAdult=false")
			append("&sort=").append(
				when (order) {
					SortOrder.POPULARITY -> "popular"
					SortOrder.ALPHABETICAL -> "az"
					else -> "latest"
				},
			)
			filter.query?.takeIf { it.isNotBlank() }?.let { append("&search=").append(it.urlEncoded()) }
			filter.tags.firstOrNull()?.let { append("&genre=").append(it.key) }
			filter.states.oneOrThrowIfMany()?.let { state ->
				when (state) {
					MangaState.ONGOING -> append("&status=ONGOING")
					MangaState.FINISHED -> append("&status=END")
					else -> Unit
				}
			}
			filter.types.oneOrThrowIfMany()?.let { type ->
				when (type) {
					ContentType.MANGA -> append("&type=MANGA")
					ContentType.MANHWA -> append("&type=MANHWA")
					ContentType.MANHUA -> append("&type=MANHUA")
					else -> Unit
				}
			}
		}
		val response = webClient.httpGet(url, getRequestHeaders()).parseJson()
		val data = response.optJSONObject("data") ?: return emptyList()
		return parseList(data.optJSONArray("data"))
	}

	private fun parseList(array: JSONArray?): List<Manga> {
		if (array == null) return emptyList()
		val result = ArrayList<Manga>(array.length())
		for (i in 0 until array.length()) {
			val item = array.optJSONObject(i) ?: continue
			val slug = item.getStringOrNull("slug") ?: continue
			val title = item.getStringOrNull("title") ?: continue
			result += Manga(
				id = generateUid(slug),
				title = title,
				altTitles = emptySet(),
				url = "/comic/$slug",
				publicUrl = "https://$domain/comic/$slug",
				coverUrl = coverUrl(item.getStringOrNull("cover")),
				largeCoverUrl = null,
				rating = RATING_UNKNOWN,
				contentRating = if (item.optBoolean("isAdult", false)) ContentRating.ADULT else null,
				tags = parseTags(item.optJSONArray("genres")),
				state = parseState(item.getStringOrNull("status")),
				authors = emptySet(),
				source = source,
			)
		}
		return result
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.substringAfterLast("/comic/").trim('/').substringBefore('/')
		val data = webClient.httpGet("https://$apiHost/api/v1/comics/$slug", getRequestHeaders())
			.parseJson().getJSONObject("data")
		val chapters = ArrayList<MangaChapter>()
		data.optJSONArray("chapters")?.let { array ->
			for (i in 0 until array.length()) {
				val item = array.optJSONObject(i) ?: continue
				if (item.optBoolean("isLocked", false)) continue
				val chapterId = item.getStringOrNull("id") ?: continue
				val number = item.getStringOrNull("number")?.toFloatOrNull() ?: continue
				val chapterTitle = item.getStringOrNull("title") ?: "Chapter ${number.formatSimple()}"
				chapters += MangaChapter(
					id = generateUid(chapterId),
					title = chapterTitle,
					number = number,
					volume = 0,
					url = "/comic/$slug/chapter/$chapterId",
					scanlator = null,
					uploadDate = parseDate(item.getStringOrNull("publishedAt")),
					branch = null,
					source = source,
				)
			}
		}
		chapters.sortBy { it.number }
		return manga.copy(
			title = data.getStringOrNull("title") ?: manga.title,
			description = data.getStringOrNull("description") ?: "",
			coverUrl = coverUrl(data.getStringOrNull("cover")) ?: manga.coverUrl,
			largeCoverUrl = null,
			altTitles = data.optJSONArray("altTitles")?.toStringList() ?: manga.altTitles,
			tags = parseTags(data.optJSONArray("genres")).ifEmpty { manga.tags },
			authors = setOfNotNull(data.getStringOrNull("author")),
			state = parseState(data.getStringOrNull("status")) ?: manga.state,
			contentRating = if (data.optBoolean("isAdult", false)) ContentRating.ADULT else manga.contentRating,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		readerReferer = null
		val chapterId = chapter.url.substringBefore('?').trimEnd('/').substringAfterLast('/')
		val headers = getRequestHeaders()
		val challengeData = webClient.httpGet("https://$domain/api/reader/challenge", headers)
			.parseJson().optJSONObject("data") ?: return emptyList()
		val challenge = challengeData.getStringOrNull("challenge") ?: return emptyList()
		val salt = challengeData.getStringOrNull("salt") ?: return emptyList()
		val difficulty = challengeData.optInt("difficulty", 3).coerceIn(0, 6)
		val payload = JSONObject()
			.put("chapterId", chapterId)
			.put("challenge", challenge)
			.put("powNonce", solvePow(salt, difficulty))
		val data = webClient.httpPost(
			"https://$domain/api/reader/manifest".toHttpUrl(),
			payload,
			headers,
		).parseJson().optJSONObject("data") ?: return emptyList()
		if (data.optBoolean("locked", false)) return emptyList()
		val pages = data.optJSONArray("pages") ?: return emptyList()
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		readerReferer = chapterUrl
		val items = (0 until pages.length())
			.mapNotNull { pages.optJSONObject(it) }
			.sortedBy { it.optInt("order", 0) }
		val result = ArrayList<MangaPage>(items.size)
		for (item in items) {
			val token = item.getStringOrNull("token") ?: continue
			val url = "https://$domain/api/reader/media?token=${token.urlEncoded()}#ref=${chapterUrl.urlEncoded()}"
			result += MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
		return result
	}

	private fun solvePow(salt: String, difficulty: Int): String {
		if (difficulty <= 0) return "0"
		val digest = MessageDigest.getInstance("SHA-256")
		val bytes = (difficulty + 1) / 2 + 1
		val hex = CharArray(bytes * 2)
		for (n in 0 until MAX_POW_ATTEMPTS) {
			val nonce = n.toString(36)
			digest.update("$salt:$nonce".toByteArray(Charsets.UTF_8))
			val hash = digest.digest()
			var index = 0
			for (i in 0 until bytes) {
				val value = hash[i].toInt() and 0xFF
				hex[index++] = HEX_ALPHABET[value ushr 4]
				hex[index++] = HEX_ALPHABET[value and 0x0F]
			}
			var matched = true
			for (i in 0 until difficulty) {
				if (hex[i] != '0') {
					matched = false
					break
				}
			}
			if (matched) return nonce
		}
		return "0"
	}

	private fun parseTags(array: JSONArray?): Set<MangaTag> {
		if (array == null) return emptySet()
		val result = LinkedHashSet<MangaTag>()
		for (i in 0 until array.length()) {
			val item = array.optJSONObject(i) ?: continue
			val slug = item.getStringOrNull("slug") ?: continue
			val name = item.getStringOrNull("name") ?: slug
			result += MangaTag(title = name.toTitleCase(), key = slug, source = source)
		}
		return result
	}

	private fun parseState(status: String?): MangaState? = when (status?.uppercase(Locale.ROOT)) {
		"ONGOING" -> MangaState.ONGOING
		"END", "COMPLETED", "END_SEASON" -> MangaState.FINISHED
		"HIATUS" -> MangaState.PAUSED
		"DROP", "DROPPED" -> MangaState.ABANDONED
		else -> null
	}

	private fun coverUrl(cover: String?): String? {
		if (cover.isNullOrBlank()) return null
		if (cover.startsWith("http")) return cover
		return "$cdnHost/${cover.removePrefix("/")}".replace(" ", "%20")
	}

	private fun parseDate(iso: String?): Long {
		if (iso.isNullOrBlank()) return 0L
		return runCatching {
			SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
				.apply { timeZone = TimeZone.getTimeZone("UTC") }
				.parse(iso)?.time
		}.getOrNull() ?: 0L
	}

	private fun JSONArray.toStringList(): Set<String> {
		val result = LinkedHashSet<String>(length())
		for (i in 0 until length()) {
			optString(i).takeIf { it.isNotBlank() }?.let { result += it }
		}
		return result
	}

	private fun JSONObject.getStringOrNull(name: String): String? =
		if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

	private companion object {
		private const val MAX_POW_ATTEMPTS = 200_000
		private const val DESKTOP_USER_AGENT =
			"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/158.0.0.0 Safari/537.36"
		private const val API_ACCEPT = "application/json, text/plain, */*"
		private const val IMAGE_ACCEPT = "image/jxl,image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
		private val HEX_ALPHABET = "0123456789abcdef".toCharArray()
	}
}
