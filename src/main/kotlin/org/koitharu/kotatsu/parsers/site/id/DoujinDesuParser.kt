package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("DOUJINDESU", "DoujinDesu.tv", "id", ContentType.HENTAI)
internal class DoujinDesuParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.DOUJINDESU, pageSize = 24) {

	override val configKeyDomain: ConfigKey.Domain
		get() = ConfigKey.Domain("doujin.desu.xxx")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder>
		get() = EnumSet.of(SortOrder.UPDATED, SortOrder.NEWEST, SortOrder.ALPHABETICAL, SortOrder.POPULARITY)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isAuthorSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.DOUJINSHI,
		),
	)

	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("X-App-Secret", APP_SECRET)
		.add("x-app-secret", APP_SECRET)
		.build()

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = urlBuilder().apply {
			addPathSegments("api/manga")
			addQueryParameter("limit", pageSize.toString())
			addQueryParameter("offset", ((page - 1) * pageSize).toString())
			addQueryParameter(
				"sort",
				when (order) {
					SortOrder.UPDATED -> "update"
					SortOrder.POPULARITY -> "popular"
					SortOrder.ALPHABETICAL -> "title"
					SortOrder.NEWEST -> "newest"
					else -> "newest"
				},
			)
			filter.query?.let { addQueryParameter("search", it) }
			filter.author?.let { addQueryParameter("author", it) }
			if (filter.tags.isNotEmpty()) {
				addQueryParameter("genre", filter.tags.joinToString(",") { it.key })
			}
			filter.states.oneOrThrowIfMany()?.let {
				addQueryParameter(
					"status",
					when (it) {
						MangaState.ONGOING -> "ongoing"
						MangaState.FINISHED -> "finished"
						else -> ""
					},
				)
			}
			filter.types.oneOrThrowIfMany()?.let {
				addQueryParameter(
					"type",
					when (it) {
						ContentType.MANGA -> "manga"
						ContentType.MANHWA -> "manhwa"
						ContentType.DOUJINSHI -> "doujinshi"
						else -> ""
					},
				)
			}
		}.build()

		val array = apiGetArray(url)
		return List(array.length()) { i ->
			val obj = array.getJSONObject(i)
			val slug = obj.getString("slug")
			Manga(
				id = generateUid(slug),
				title = obj.getString("title"),
				altTitles = parseAltTitles(obj.optString("alt_titles", "")),
				url = slug,
				publicUrl = "https://$domain/manga/$slug/",
				rating = parseRating(obj.optDouble("rating", -1.0)),
				contentRating = ContentRating.ADULT,
				coverUrl = obj.optString("cover_url").takeIf { it.isNotEmpty() },
				tags = emptySet(),
				state = parseState(obj.optString("status", "")),
				authors = setOfNotNull(obj.optString("author", null)?.takeIf { it.isNotEmpty() }),
				largeCoverUrl = null,
				description = null,
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val url = urlBuilder().addPathSegments("api/manga/${manga.url}").build()
		val obj = apiGet(url)

		val mangaGenres = obj.optJSONArray("manga_genres") ?: JSONArray()
		val chaptersArray = obj.optJSONArray("chapters") ?: JSONArray()

		val tags = buildSet<MangaTag> {
			for (i in 0 until mangaGenres.length()) {
				val mg = mangaGenres.getJSONObject(i)
				val genre = mg.optJSONObject("genres") ?: continue
				add(
					MangaTag(
						key = genre.getString("slug"),
						title = genre.getString("name"),
						source = source,
					),
				)
			}
		}

		val chapters = ArrayList<MangaChapter>(chaptersArray.length())
		for (i in 0 until chaptersArray.length()) {
			val ch = chaptersArray.getJSONObject(i)
			val chId = ch.getString("id")
			chapters.add(
				MangaChapter(
					id = generateUid(chId),
					title = ch.optString("title").takeIf { it.isNotEmpty() }
						?: "Chapter ${ch.getDouble("chapter_number")}",
					number = ch.getDouble("chapter_number").toFloat(),
					volume = 0,
					url = chId,
					scanlator = null,
					uploadDate = parseIsoDate(ch.optString("created_at", "")),
					branch = null,
					source = source,
				),
			)
		}
		chapters.sortBy { it.number }

		return manga.copy(
			altTitles = parseAltTitles(obj.optString("alt_titles", "")),
			description = obj.optString("description").takeIf { it.isNotEmpty() },
			state = parseState(obj.optString("status", "")),
			authors = setOfNotNull(
				obj.optString("author", null)?.takeIf { it.isNotEmpty() },
				obj.optString("artist", null)?.takeIf { it.isNotEmpty() },
			),
			rating = parseRating(obj.optDouble("rating", -1.0)),
			tags = tags,
			chapters = chapters,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val url = urlBuilder().addPathSegments("api/chapters/${chapter.url}").build()
		val obj = apiGet(url)
		val contentUrls = obj.optJSONArray("content_urls") ?: JSONArray()
		return List(contentUrls.length()) { i ->
			val imgUrl = contentUrls.getString(i)
			MangaPage(
				id = generateUid(imgUrl),
				url = imgUrl,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val url = urlBuilder().addPathSegments("api/genres").build()
		val array = apiGetArray(url)
		return buildSet {
			for (i in 0 until array.length()) {
				val obj = array.getJSONObject(i)
				add(
					MangaTag(
						key = obj.getString("slug"),
						title = obj.getString("name"),
						source = source,
					),
				)
			}
		}
	}

	// --- Decryption helpers ---

	private fun generateKey(epochSlot: Long): String {
		val s = "${ENC_SALT}_$epochSlot"
		var n = 0
		for (c in s) {
			n = (n shl 5) - n + c.code
		}
		var l = if (n == 0) 123456789L else Math.abs(n.toLong())
		val sb = StringBuilder(32)
		repeat(32) {
			l = (l * 1664525L + 1013904223L) % 4294967296L
			sb.append((33 + (l % 93).toInt()).toChar())
		}
		return sb.toString()
	}

	private fun decryptXor(hexStr: String, key: String): String {
		val bytes = ArrayList<Int>(hexStr.length / 2)
		var u = 0
		while (u < hexStr.length) {
			val part = hexStr.substring(u, minOf(u + 2, hexStr.length))
			if (part.isEmpty()) break
			bytes.add(part.toInt(16))
			u += 2
		}
		val keyLen = key.length
		var c = 42
		val sb = StringBuilder(bytes.size)
		for (i in bytes.indices) {
			val p = bytes[i]
			val f = key[i % keyLen].code
			val k = p xor f xor (i * 13) xor c
			sb.append((k and 255).toChar())
			c = (c + p) % 256
		}
		return sb.toString()
	}

	private fun decryptResponse(encStr: String): String {
		val t = System.currentTimeMillis() / ENC_INTERVAL
		val keys = listOf(generateKey(t), generateKey(t - 1), generateKey(t + 1))
		for (key in keys) {
			try {
				val raw = decryptXor(encStr, key)
				return URLDecoder.decode(raw, "UTF-8")
			} catch (_: Exception) {
			}
		}
		throw Exception("Failed to decrypt DoujinDesu API response")
	}

	private suspend fun apiGet(url: okhttp3.HttpUrl): JSONObject {
		val wrapper = webClient.httpGet(url).parseJson()
		val enc = wrapper.optString("_enc_resp_", null) ?: return wrapper
		return JSONObject(decryptResponse(enc))
	}

	private suspend fun apiGetArray(url: okhttp3.HttpUrl): JSONArray {
		val wrapper = webClient.httpGet(url).parseJson()
		val enc = wrapper.optString("_enc_resp_", null) ?: return JSONArray()
		return JSONArray(decryptResponse(enc))
	}

	// --- Misc helpers ---

	private fun parseAltTitles(raw: String): Set<String> {
		if (raw.isBlank()) return emptySet()
		return raw.split("|").mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }.toSet()
	}

	private fun parseState(status: String): MangaState? = when (status) {
		"ongoing" -> MangaState.ONGOING
		"finished" -> MangaState.FINISHED
		else -> null
	}

	private fun parseRating(raw: Double): Float {
		if (raw <= 0.0) return RATING_UNKNOWN
		return (raw / 10f).toFloat().coerceIn(0f, 1f)
	}

	private fun parseIsoDate(iso: String): Long {
		if (iso.isEmpty()) return 0L
		return try {
			val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
			sdf.timeZone = TimeZone.getTimeZone("UTC")
			sdf.parse(iso)?.time ?: 0L
		} catch (_: Exception) {
			0L
		}
	}

	companion object {
		private const val APP_SECRET = "dfdf72051dbfdc7d76889ebd31324e74"
		private const val ENC_SALT = "doujindesu-scrapers-cannot-read-this-super-secret-salt-2026-v2"
		private const val ENC_INTERVAL = 3600000L
	}
}