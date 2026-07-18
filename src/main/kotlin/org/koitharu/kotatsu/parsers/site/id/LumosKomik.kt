package org.koitharu.kotatsu.parsers.site.id

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("LUMOSKOMIK", "LumosKomik", "id")
internal class LumosKomik(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.LUMOSKOMIK, pageSize = 50) {

	override val configKeyDomain = ConfigKey.Domain("03.lumosgg.com")

	override val userAgentKey = ConfigKey.UserAgent(UserAgents.CHROME_DESKTOP)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.RATING,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchTags(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.PAUSED),
		availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			append("/browse?page=")
			append(page)

			filter.query?.let {
				append("&q=")
				append(it.urlEncoded())
			}

			for (tag in filter.tags) {
				append("&genre=")
				append(tag.key)
			}

			filter.states.oneOrThrowIfMany()?.let {
				append("&status=")
				append(
					when (it) {
						MangaState.ONGOING -> "ongoing"
						MangaState.FINISHED -> "completed"
						MangaState.PAUSED -> "hiatus"
						else -> ""
					},
				)
			}

			filter.types.oneOrThrowIfMany()?.let {
				append("&type=")
				append(
					when (it) {
						ContentType.MANGA -> "manga"
						ContentType.MANHWA -> "manhwa"
						ContentType.MANHUA -> "manhua"
						else -> ""
					},
				)
			}

			append("&sort=")
			append(
				when (order) {
					SortOrder.UPDATED -> "latest"
					SortOrder.POPULARITY -> "popular"
					SortOrder.RATING -> "rating"
					SortOrder.ALPHABETICAL -> "az"
					else -> "latest"
				},
			)
		}
		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	private fun parseMangaList(doc: Document): List<Manga> {
		return doc.select("div.htg-card").mapNotNull { card ->
			val a = card.selectFirst("a[href*=/comic/]") ?: return@mapNotNull null
			val href = a.attrAsRelativeUrl("href")
			val title = card.selectFirst("h3")?.text()
				?: a.selectFirst("img")?.attr("alt").orEmpty()
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				coverUrl = card.selectFirst("img")?.src(),
				title = title,
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				authors = emptySet(),
				state = when (card.selectFirst("span.capitalize")?.text()?.trim()?.lowercase()) {
					"ongoing" -> MangaState.ONGOING
					"completed" -> MangaState.FINISHED
					"hiatus" -> MangaState.PAUSED
					else -> null
				},
				source = source,
				contentRating = null,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

		val infoMap = doc.select("div.grid div.rounded, div.grid > div").associate { block ->
			val label = block.selectFirst("div.mb-1")?.text()?.trim()?.lowercase().orEmpty()
			val value = block.select("span").lastOrNull()?.text()?.trim().orEmpty()
			label to value
		}

		return manga.copy(
			title = doc.selectFirst("h1")?.text() ?: manga.title,
			description = doc.selectFirst("meta[property=og:description]")?.attr("content")
				?: manga.description,
			coverUrl = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: manga.coverUrl,
			tags = doc.select("a[href*=browse?genre=]").mapNotNullToSet { a ->
				val key = a.attr("href").substringAfter("genre=").substringBefore('&')
				val name = a.text().trim()
				if (key.isEmpty() || name.isEmpty()) null else MangaTag(name, key, source)
			},
			authors = infoMap["author"]
				?.takeUnless { it.isEmpty() || it.equals("updating", ignoreCase = true) }
				?.let { setOf(it) }
				?: manga.authors,
			state = when (infoMap["status"]?.lowercase()) {
				"ongoing" -> MangaState.ONGOING
				"completed" -> MangaState.FINISHED
				"hiatus" -> MangaState.PAUSED
				else -> manga.state
			},
			chapters = doc.select("a[href*=/read/][data-chapter]").mapChapters(reversed = true) { _, a ->
				val href = a.attrAsRelativeUrl("href")
				val number = a.attr("data-chapter").trim().toFloatOrNull() ?: 0f
				MangaChapter(
					id = generateUid(href),
					title = a.selectFirst("span")?.text()?.trim(),
					number = number,
					volume = 0,
					url = href,
					scanlator = null,
					uploadDate = parseRelativeDate(a.selectFirst("span.tabular-nums")?.text()),
					branch = null,
					source = source,
				)
			},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.select("#reader-pages img").mapNotNull { img ->
			val url = img.src() ?: return@mapNotNull null
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/browse").parseHtml()
		return doc.select("label.bf-genre-item").mapNotNullToSet { label ->
			val key = label.attr("data-bf-genre-name").trim()
			val name = label.text().trim()
			if (key.isEmpty() || name.isEmpty()) null else MangaTag(name, key, source)
		}
	}

	private fun parseRelativeDate(date: String?): Long {
		val d = date?.lowercase()?.trim() ?: return 0L
		val number = Regex("""(\d+)""").find(d)?.value?.toIntOrNull() ?: return 0L
		val cal = Calendar.getInstance()
		return when {
			WordSet("detik", "second").anyWordIn(d) -> cal.apply { add(Calendar.SECOND, -number) }.timeInMillis
			WordSet("menit", "minute").anyWordIn(d) -> cal.apply { add(Calendar.MINUTE, -number) }.timeInMillis
			WordSet("jam", "hour").anyWordIn(d) -> cal.apply { add(Calendar.HOUR, -number) }.timeInMillis
			WordSet("hari", "day").anyWordIn(d) -> cal.apply { add(Calendar.DAY_OF_MONTH, -number) }.timeInMillis
			WordSet("minggu", "week").anyWordIn(d) -> cal.apply { add(Calendar.WEEK_OF_YEAR, -number) }.timeInMillis
			WordSet("bulan", "month").anyWordIn(d) -> cal.apply { add(Calendar.MONTH, -number) }.timeInMillis
			WordSet("tahun", "year").anyWordIn(d) -> cal.apply { add(Calendar.YEAR, -number) }.timeInMillis
			else -> 0L
		}
	}
}
