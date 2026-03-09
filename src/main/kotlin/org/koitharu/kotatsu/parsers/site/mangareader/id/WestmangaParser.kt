package org.koitharu.kotatsu.parsers.site.mangareader.id

import androidx.collection.ArrayMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@MangaSourceParser("WESTMANGA", "WestManga", "id")
internal class WestmangaParser(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.WESTMANGA, pageSize = 20) {

	override val configKeyDomain = ConfigKey.Domain("westmanga.tv")

	private val domain = "westmanga.tv"
	private val apiDomain = "westmanga.tv"
	private val accessKey = "WM_WEB_FRONT_END"
	private val secretKey = "xxxoidj"

	private var genresCache: Set<MangaTag>? = null
	private val mutex = Mutex()

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.ALPHABETICAL,
		SortOrder.NEWEST,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchGenres(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		return try {
			val url = "https://$apiDomain/api/contents".toHttpUrl().newBuilder().apply {
				if (!filter.query.isNullOrEmpty()) {
					addQueryParameter("q", filter.query)
				}
				addQueryParameter("page", page.toString())
				addQueryParameter("per_page", "20")
				addQueryParameter("type", "Comic")

				when (order) {
					SortOrder.UPDATED -> addQueryParameter("sort", "updated_at")
					SortOrder.POPULARITY -> addQueryParameter("sort", "views")
					SortOrder.ALPHABETICAL -> addQueryParameter("sort", "title")
					SortOrder.NEWEST -> addQueryParameter("sort", "created_at")
					else -> {}
				}

				filter.tags.forEach { tag ->
					addQueryParameter("genre[]", tag.key)
				}

				filter.states.oneOrThrowIfMany()?.let {
					addQueryParameter("status", when (it) {
						MangaState.ONGOING -> "ongoing"
						MangaState.FINISHED -> "completed"
						MangaState.PAUSED -> "hiatus"
						else -> return@let
					})
				}

				filter.types.oneOrThrowIfMany()?.let {
					addQueryParameter("country", when (it) {
						ContentType.MANGA -> "JP"
						ContentType.MANHWA -> "KR"
						ContentType.MANHUA -> "CN"
						else -> return@let
					})
				}
			}.build()

			val response = webClient.httpGet(url, createApiHeaders(url)).parseJson()
			val mangaArray = response.optJSONArray("data") ?: return emptyList()

			mangaArray.mapJSON { jo ->
				val slug = jo.getString("slug")
				Manga(
					id = generateUid(slug),
					title = jo.getString("title"),
					altTitles = emptySet(),
					url = "/comic/$slug/",
					publicUrl = "https://$domain/comic/$slug",
					rating = RATING_UNKNOWN,
					contentRating = null,
					coverUrl = jo.optString("cover", ""),
					tags = emptySet(),
					state = null,
					authors = emptySet(),
					source = source,
				)
			}
		} catch (e: Exception) {
			logcat(e) { "Failed to fetch list page" }
			emptyList()
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		return try {
			val slug = manga.url.removeSurrounding("/comic/", "/")
			val url = "https://$apiDomain/api/comic/$slug".toHttpUrl()

			val response = webClient.httpGet(url, createApiHeaders(url)).parseJson()
			val data = response.getJSONObject("data")

			val tags = buildSet {
				when (data.getStringOrNull("country_id") ?: data.getStringOrNull("country")) {
					"JP" -> add(MangaTag("Manga", "manga", source))
					"CN" -> add(MangaTag("Manhua", "manhua", source))
					"KR" -> add(MangaTag("Manhwa", "manhwa", source))
				}
				if (data.optBoolean("color", false)) {
					add(MangaTag("Colored", "colored", source))
				}
				data.optJSONArray("genres")?.let { genres ->
					for (i in 0 until genres.length()) {
						val genre = genres.getJSONObject(i)
						add(MangaTag(
							title = genre.getString("name"),
							key = genre.getInt("id").toString(),
							source = source,
						))
					}
				}
			}

			val description = buildString {
				data.getStringOrNull("sinopsis")?.let { synopsis ->
					append(org.jsoup.Jsoup.parseBodyFragment(synopsis).wholeText().trim())
				}
				data.getStringOrNull("alternative_name")?.let { alt ->
					append("\n\nAlternative Name: ")
					append(alt.trim())
				}
			}

			val state = when (data.getStringOrNull("status")) {
				"ongoing" -> MangaState.ONGOING
				"completed" -> MangaState.FINISHED
				"hiatus" -> MangaState.PAUSED
				else -> null
			}

			val chapters = data.optJSONArray("chapters")?.mapJSON { chapterObj ->
				val chapterSlug = chapterObj.getString("slug")
				val updatedAt = chapterObj.optJSONObject("updated_at")?.getLongOrDefault("time", 0L)
					?: chapterObj.getLongOrDefault("updated_at", 0L)
				MangaChapter(
					id = generateUid(chapterSlug),
					url = "/view/$chapterSlug/",
					title = "Chapter ${chapterObj.getString("number")}",
					number = chapterObj.getString("number").toFloatOrNull() ?: 0f,
					volume = 0,
					branch = null,
					uploadDate = updatedAt * 1000,
					scanlator = null,
					source = source,
				)
			} ?: emptyList()

			manga.copy(
				title = data.getString("title"),
				coverUrl = data.optString("cover", ""),
				tags = tags,
				authors = setOfNotNull(data.getStringOrNull("author")),
				description = description.takeIf { it.isNotEmpty() },
				state = state,
				chapters = chapters.reversed(),
			)
		} catch (e: Exception) {
			logcat(e) { "Failed to fetch details for ${manga.url}" }
			manga
		}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		return try {
			val slug = chapter.url.removeSurrounding("/view/", "/")
			val url = "https://$apiDomain/api/v/$slug".toHttpUrl()

			val response = webClient.httpGet(url, createApiHeaders(url)).parseJson()
			val images = response.optJSONObject("data")?.getJSONArray("images")
				?: response.optJSONArray("images")
				?: return emptyList()

			val pages = ArrayList<MangaPage>(images.length())
			for (i in 0 until images.length()) {
				val imageUrl = images.getString(i)
				
				val fullImageUrl = when {
					imageUrl.startsWith("http") -> imageUrl
					imageUrl.startsWith("/") -> "https://$domain$imageUrl"
					else -> "https://$apiDomain/$imageUrl"
				}
				
				pages.add(
					MangaPage(
						id = generateUid(fullImageUrl),
						url = fullImageUrl,
						preview = null,
						source = source,
					)
				)
			}
			return pages
		} catch (e: Exception) {
			logcat(e) { "Failed to fetch pages for ${chapter.url}" }
			emptyList()
		}
	}

	private suspend fun fetchGenres(): Set<MangaTag> = mutex.withLock {
		genresCache?.let { return@withLock it }

		return try {
			val url = "https://$apiDomain/api/contents/genres".toHttpUrl()
			val response = webClient.httpGet(url, createApiHeaders(url)).parseJson()
			val genresArray = response.optJSONArray("data") ?: return@withLock emptySet()

			val genres = genresArray.mapJSONToSet { genreObj ->
				MangaTag(
					title = genreObj.getString("name"),
					key = genreObj.getInt("id").toString(),
					source = source,
				)
			}

			genresCache = genres
			genres
		} catch (e: Exception) {
			logcat(e) { "Failed to fetch genres" }
			emptySet()
		}
	}

	private fun createApiHeaders(url: okhttp3.HttpUrl): Headers {
		val timestamp = (System.currentTimeMillis() / 1000).toString()
		val message = "wm-api-request"
		val key = timestamp + "GET" + url.encodedPath + accessKey + secretKey

		val mac = Mac.getInstance("HmacSHA256")
		val secretKeySpec = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")
		mac.init(secretKeySpec)
		val hash = mac.doFinal(message.toByteArray(Charsets.UTF_8))
		val signature = hash.joinToString("") { "%02x".format(it) }

		return Headers.Builder()
			.add("User-Agent", config[userAgentKey])
			.add("Referer", "https://$domain/")
			.add("Accept-Language", "en-US,en;q=0.9")
			.add("Accept-Encoding", "gzip, deflate, br")
			.add("x-wm-request-time", timestamp)
			.add("x-wm-accses-key", accessKey)
			.add("x-wm-request-signature", signature)
			.build()
	}
}
