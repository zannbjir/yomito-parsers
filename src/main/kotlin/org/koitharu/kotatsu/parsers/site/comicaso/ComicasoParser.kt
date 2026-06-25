package org.koitharu.kotatsu.parsers.site.comicaso

import org.json.JSONArray
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
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
		val data = json.getJSONArray("data")

		// Status filter is not supported server-side; apply client-side
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
		val url = "https://$domain/api/manga.php?source=$apiSource&slug=${slug.urlEncoded()}&platform=web"
		val json = webClient.httpGet(url).parseJson()
		val data = json.getJSONObject("data")

		val title = data.getString("title")
		val synopsis = data.optString("synopsis").parseHtml().text().takeIf { it.isNotBlank() }
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
		val tags = if (genresArray != null) {
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
			tagSet as Set<MangaTag>
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
		// Parse manga slug and chapter slug from URL: /komik/{manga_slug}/{chapter_slug}/
		val parts = chapter.url.trim('/').split("/")
		// parts[0]="komik", parts[1]=manga_slug, parts[2]=chapter_slug
		if (parts.size < 3) return emptyList()
		val mangaSlug = parts[1]
		val chapterSlug = parts[2]

		val url = "https://$domain/api/chapter.php" +
			"?source=${apiSource.urlEncoded()}" +
			"&manga=${mangaSlug.urlEncoded()}" +
			"&chapter=${chapterSlug.urlEncoded()}"

		val json = webClient.httpGet(url).parseJson()
		val data = json.getJSONObject("data")
		val images = data.optJSONArray("images") ?: return emptyList()

		return images.mapJSON { imgUrl ->
			MangaPage(
				id = generateUid(imgUrl),
				url = imgUrl,
				preview = null,
				source = source,
			)
		}
	}

	private fun extractChapterNumber(title: String): Float {
		return Regex("""[\d]+(?:[.,]\d+)?""").find(title)
			?.value?.replace(',', '.')?.toFloatOrNull() ?: 0f
	}
}
