package org.koitharu.kotatsu.parsers.site.comicaso

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.util.*

internal abstract class ComicasoParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	domain: String,
	pageSize: Int = 20,
) : PagedMangaParser(context, source, pageSize, pageSize) {

	override val configKeyDomain = ConfigKey.Domain(domain)

	override val sourceLocale: Locale = Locale("id")

	protected abstract val apiSource: String

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = false,
			isTagsExclusionSupported = false,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val url = "https://$domain/api/genres.php?source=$apiSource"
		val json = webClient.httpGet(url).parseJson()
		val arr = json.optJSONObject("data")?.optJSONArray(apiSource) ?: return emptySet()
		val result = LinkedHashSet<MangaTag>(arr.length())
		for (i in 0 until arr.length()) {
			val item = arr.getJSONObject(i)
			val genre = item.optString("genre").takeIf { it.isNotBlank() } ?: continue
			val slug = item.optString("genre_slug").ifBlank { genre.lowercase(sourceLocale) }
			result.add(
				MangaTag(
					key = slug,
					title = genre.toTitleCase(sourceLocale),
					source = source,
				),
			)
		}
		return result
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val offset = (page - 1) * pageSize

		val mode = when (order) {
			SortOrder.NEWEST -> "new"
			else -> "update"
		}

		val typeParam = filter.types.oneOrThrowIfMany()?.let { type ->
			when (type) {
				ContentType.MANGA -> "manga"
				ContentType.MANHWA -> "manhwa"
				ContentType.MANHUA -> "manhua"
				else -> "all"
			}
		} ?: "all"

		val genreParam = filter.tags.oneOrThrowIfMany()?.key ?: ""
		val query = filter.query.orEmpty()

		val url = buildString {
			append("https://$domain/api/home.php")
			append("?source=").append(apiSource)
			append("&q=").append(query.urlEncoded())
			append("&mode=").append(mode)
			append("&type=").append(typeParam)
			if (genreParam.isNotEmpty()) append("&genre=").append(genreParam.urlEncoded())
			append("&limit=").append(pageSize)
			append("&offset=").append(offset)
		}

		val json = webClient.httpGet(url).parseJson()
		val data = json.optJSONArray("data") ?: return emptyList()

		val stateFilter = filter.states.oneOrThrowIfMany()

		val result = ArrayList<Manga>(data.length())
		for (i in 0 until data.length()) {
			val item = data.getJSONObject(i)

			if (stateFilter != null) {
				val itemStatus = item.optString("status")
				val expectedStatus = when (stateFilter) {
					MangaState.ONGOING -> "on-going"
					MangaState.FINISHED -> "end"
					else -> null
				}
				if (expectedStatus != null && itemStatus != expectedStatus) continue
			}

			val slug = item.getString("slug")
			result.add(
				Manga(
					id = generateUid("/komik/$slug/"),
					url = "/komik/$slug/",
					title = item.getString("title"),
					altTitles = emptySet(),
					publicUrl = "https://$domain/komik/$slug/",
					rating = RATING_UNKNOWN,
					contentRating = if (isNsfwSource) ContentRating.ADULT else null,
					coverUrl = item.optString("thumbnail").ifBlank { "" },
					tags = emptySet(),
					state = when (item.optString("status")) {
						"on-going" -> MangaState.ONGOING
						"end" -> MangaState.FINISHED
						else -> null
					},
					authors = emptySet(),
					source = source,
				),
			)
		}
		return result
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val slug = manga.url.removePrefix("/komik/").removeSuffix("/")
		val json = fetchMangaJson(slug)
		val data = json.getJSONObject("data")

		val title = data.getString("title")
		val synopsis = Jsoup.parse(data.optString("synopsis")).text().takeIf { it.isNotBlank() }
		val alternative = data.optString("alternative").takeIf { it.isNotBlank() }
		val thumbnail = data.optString("thumbnail").takeIf { it.isNotBlank() }
		val author = data.optString("author").takeIf { it.isNotBlank() }
		val artist = data.optString("artist").takeIf { it.isNotBlank() }

		val description = buildString {
			if (synopsis != null) append(synopsis)
			if (alternative != null) {
				if (isNotEmpty()) append("\n\n")
				append("Alternative: $alternative")
			}
		}.trim().takeIf { it.isNotEmpty() }

		val state = when (data.optString("status")) {
			"on-going" -> MangaState.ONGOING
			"end" -> MangaState.FINISHED
			else -> null
		}

		val genresArray = data.optJSONArray("genres")
		val tags: Set<MangaTag> = if (genresArray != null) {
			val tagSet = LinkedHashSet<MangaTag>(genresArray.length())
			for (i in 0 until genresArray.length()) {
				val genre = genresArray.getString(i)
				tagSet.add(
					MangaTag(
						key = genre.lowercase(sourceLocale),
						title = genre.toTitleCase(sourceLocale),
						source = source,
					),
				)
			}
			tagSet
		} else {
			emptySet()
		}

		val authors = setOfNotNull(
			author,
			artist?.takeIf { it != author },
		)

		val chaptersArray = data.optJSONArray("chapters") ?: JSONArray()
		val chapters = ArrayList<MangaChapter>(chaptersArray.length())
		for (i in 0 until chaptersArray.length()) {
			val ch = chaptersArray.getJSONObject(i)
			val chSlug = ch.getString("slug")
			val chTitle = ch.getString("title")
			val chDate = ch.optLong("date").takeIf { it != 0L }?.times(1000L) ?: 0L
			chapters.add(
				MangaChapter(
					id = generateUid("/komik/$slug/$chSlug/"),
					title = chTitle,
					number = extractChapterNumber(chTitle),
					volume = 0,
					url = "/komik/$slug/$chSlug/",
					scanlator = null,
					uploadDate = chDate,
					branch = null,
					source = source,
				),
			)
		}

		chapters.sortBy { it.number }

		return manga.copy(
			title = title,
			description = description,
			coverUrl = thumbnail ?: manga.coverUrl,
			tags = tags,
			state = state,
			authors = authors,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val parts = chapter.url.trim('/').split("/")
		if (parts.size < 3) return emptyList()
		val mangaSlug = parts[1]
		val chapterSlug = parts[2]

		// Re-fetch manga detail to get a fresh, IP-bound chapter token.
		val detailJson = fetchMangaJson(mangaSlug)

		var chapterToken = ""
		val chaptersArray = detailJson.optJSONObject("data")?.optJSONArray("chapters")
		if (chaptersArray != null) {
			for (i in 0 until chaptersArray.length()) {
				val ch = chaptersArray.getJSONObject(i)
				if (ch.optString("slug") == chapterSlug) {
					chapterToken = ch.optString("chapter_token")
					break
				}
			}
		}

		val json = fetchChapterJson(mangaSlug, chapterSlug, chapterToken)
		val data = json.getJSONObject("data")
		val images = data.optJSONArray("images") ?: return emptyList()

		return (0 until images.length()).map { i ->
			val imgUrl = images.getString(i)
			MangaPage(
				id = generateUid(imgUrl),
				url = imgUrl,
				preview = null,
				source = source,
			)
		}
	}

	// --- API helpers -----------------------------------------------------
	//
	// The Comicaso API returns non-2xx status codes for its "expected" error
	// responses (428 for `need_challenge`, 403 for `locked`, 400 for a failed
	// challenge submission), each with a JSON body describing what happened.
	// The default WebClient throws HttpStatusException on any 4xx, so we
	// bypass it here and use OkHttp directly, then parse the body ourselves.

	/**
	 * Fetches /api/manga.php, transparently solving the human-challenge if the
	 * server requests one and mapping "locked" responses to AuthRequiredException.
	 */
	private suspend fun fetchMangaJson(slug: String): JSONObject {
		val url = "https://$domain/api/manga.php" +
			"?source=${apiSource.urlEncoded()}" +
			"&slug=${slug.urlEncoded()}" +
			"&platform=web"

		val json = fetchJsonAllowingApiErrors(url).let { first ->
			ensureUnlocked(first, url) { fetchJsonAllowingApiErrors(url) }
		}

		if (json.optJSONObject("data") == null) {
			throw ParseException("Comicaso: data manga tidak ditemukan", url)
		}
		return json
	}

	private suspend fun fetchChapterJson(
		mangaSlug: String,
		chapterSlug: String,
		token: String,
	): JSONObject {
		val url = buildString {
			append("https://$domain/api/chapter.php")
			append("?source=").append(apiSource.urlEncoded())
			append("&manga=").append(mangaSlug.urlEncoded())
			append("&chapter=").append(chapterSlug.urlEncoded())
			append("&platform=web")
			if (token.isNotBlank()) {
				append("&token=").append(token.urlEncoded())
			}
		}

		return fetchJsonAllowingApiErrors(url).let { first ->
			ensureUnlocked(first, url) { fetchJsonAllowingApiErrors(url) }
		}
	}

	/**
	 * Inspects a Comicaso API response for `need_challenge` / `locked` flags.
	 *  - If the account is locked (Medusa requires login), throws
	 *    [AuthRequiredException] so the app shows a proper "login required"
	 *    prompt instead of a generic "page not found" error.
	 *  - If a human-challenge is required, solves it once and retries via
	 *    the [retry] block, returning that response.
	 */
	private suspend fun ensureUnlocked(
		json: JSONObject,
		url: String,
		retry: suspend () -> JSONObject,
	): JSONObject {
		if (json.optBoolean("ok", true)) return json

		if (json.optBoolean("locked", false)) {
			throw AuthRequiredException(source)
		}

		if (json.optBoolean("need_challenge", false)) {
			solveChallenge()
			val retried = retry()
			if (!retried.optBoolean("ok", true)) {
				if (retried.optBoolean("locked", false)) {
					throw AuthRequiredException(source)
				}
				val message = retried.optString("message")
					.ifBlank { "Comicaso: gagal memuat konten setelah verifikasi." }
				throw ParseException(message, url)
			}
			return retried
		}

		val message = json.optString("message")
			.ifBlank { "Comicaso: gagal memuat konten." }
		throw ParseException(message, url)
	}

	/**
	 * Solves the Comicaso human-challenge:
	 *   1. GET /api/challenge.php -> receives { challenge: { id, ticket } }.
	 *   2. POST /api/challenge.php with a synthetic slider trace that satisfies
	 *      the server's minimum requirements (elapsed >= 650ms, progress >= 0.97,
	 *      samples throttled to >= 45ms apart, at most 80 samples).
	 * The verification cookie is persisted by the shared OkHttp CookieJar,
	 * so subsequent /api/manga.php and /api/chapter.php calls succeed.
	 */
	private suspend fun solveChallenge() {
		val challengeUrl = "https://$domain/api/challenge.php"
		val getJson = fetchJsonAllowingApiErrors(challengeUrl)
		if (!getJson.optBoolean("ok", false)) {
			throw ParseException(
				getJson.optString("message")
					.ifBlank { "Comicaso: verifikasi tidak dapat dimulai." },
				challengeUrl,
			)
		}
		if (getJson.optBoolean("verified", false)) return

		val challenge = getJson.optJSONObject("challenge") ?: throw ParseException(
			"Comicaso: verifikasi tidak dapat dimulai (challenge kosong).",
			challengeUrl,
		)
		val challengeId = challenge.optString("id")
		val ticket = challenge.optString("ticket")
		if (challengeId.isBlank() || ticket.isBlank()) {
			throw ParseException(
				"Comicaso: verifikasi tidak dapat dimulai (ticket kosong).",
				challengeUrl,
			)
		}

		val samples = JSONArray()
		val total = 24
		val totalMs = 1500L
		for (i in 0 until total) {
			val fraction = i.toDouble() / (total - 1)
			// Match the JS reference: Number(x.toFixed(4)) and Math.round(elapsed).
			val x = Math.round(fraction * 10_000.0) / 10_000.0
			val t = Math.round(fraction * totalMs)
			samples.put(JSONObject().apply {
				put("x", x)
				put("t", t)
			})
		}

		val body = JSONObject().apply {
			put("challenge_id", challengeId)
			put("ticket", ticket)
			put("elapsed_ms", totalMs)
			put("progress", 1)
			put("samples", samples)
		}

		val postJson = postJsonAllowingApiErrors(challengeUrl, body)
		if (!postJson.optBoolean("ok", false) || !postJson.optBoolean("verified", false)) {
			throw ParseException(
				postJson.optString("message").ifBlank { "Comicaso: verifikasi gagal." },
				challengeUrl,
			)
		}
	}

	/**
	 * Performs a GET and parses the JSON body without rejecting 4xx responses,
	 * because Comicaso encodes challenge / lock signals as HTTP errors with a
	 * JSON payload. Only actual network / non-JSON failures propagate.
	 */
	private suspend fun fetchJsonAllowingApiErrors(url: String): JSONObject {
		val request = Request.Builder()
			.get()
			.url(url)
			.tag(MangaSource::class.java, source)
			.build()
		return context.httpClient.newCall(request).await().parseJsonAndClose()
	}

	private suspend fun postJsonAllowingApiErrors(url: String, body: JSONObject): JSONObject {
		val requestBody = body.toString()
			.toRequestBody("application/json; charset=utf-8".toMediaType())
		val request = Request.Builder()
			.post(requestBody)
			.url(url)
			.tag(MangaSource::class.java, source)
			.build()
		return context.httpClient.newCall(request).await().parseJsonAndClose()
	}

	private fun Response.parseJsonAndClose(): JSONObject = use { r ->
		val text = r.body.string()
		try {
			JSONObject(text)
		} catch (e: Exception) {
			throw ParseException(
				"Comicaso: respons tidak dapat diparsing (status ${r.code}).",
				r.request.url.toString(),
				e,
			)
		}
	}

	private fun extractChapterNumber(title: String): Float {
		return Regex("""[\d]+(?:[.,]\d+)?""").find(title)
			?.value?.replace(',', '.')?.toFloatOrNull() ?: 0f
	}
}
