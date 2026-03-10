package org.koitharu.kotatsu.parsers.site.madara.id

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("ROSEVEIL", "Roseveil", "id")
internal class Roseveil(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.ROSEVEIL, "roseveil.org") {

    override val sourceLocale: Locale = Locale("id")
    override val datePattern = "MMMM dd, yyyy"   
    override val withoutAjax = true 
    override val listUrl = "comic/"
    override val selectMangaTitle = ".post-title h3 a, h3 a"
    override val selectChapter = "#lone-ch-list li.wp-manga-chapter"
    private val requestHeaders: Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "https://$domain/"
    )

    override fun parseMangaList(doc: Document): List<Manga> {
        val items = doc.select(".manga-item, article, .page-item-detail")
        return items.mapNotNull { item ->
            val link = item.selectFirst("h3 a, .post-title a") ?: return@mapNotNull null
            val href = link.attrAsRelativeUrl("href")
        
            val title = link.text().trim()
            
            val cover = item.selectFirst("img")?.src() ?: ""

            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                coverUrl = cover,
                title = title,
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                authors = emptySet(),
                state = null,
                source = source,
                contentRating = ContentRating.SAFE
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val response = webClient.httpGet(manga.publicUrl, requestHeaders)
        val doc = response.parseHtml()
        val chapters = mutableListOf<MangaChapter>()
        doc.select(selectChapter).forEachIndexed { i, element ->
            val link = element.selectFirst("a")
            if (link != null) {
                val href = link.attrAsRelativeUrl("href")
                val name = link.text().trim()
                chapters.add(MangaChapter(
                    id = generateUid(href),
                    title = name,
                    url = href,
                    number = name.filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: (i + 1f),
                    uploadDate = 0L,
                    source = source,
                    scanlator = null,
                    branch = null,
                    volume = 0
                ))
            }
        }

        return manga.copy(
            title = doc.selectFirst("h1.text-4xl, .post-title h1")?.text() ?: manga.title,
            description = doc.select(".tab-panel#panel-synopsis .prose, .description-summary").text().trim(),
            chapters = chapters.reversed(), // Biasanya chapter terbaru di atas, kita balik
            state = if (doc.text().contains("Ongoing", true)) MangaState.ONGOING else MangaState.FINISHED
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val response = webClient.httpGet(chapter.url.toAbsoluteUrl(domain), requestHeaders)
        val doc = response.parseHtml()
        
        return doc.select(".reading-content img, .page-break img").mapNotNull { img ->
            val url = img.src() ?: return@mapNotNull null
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source
            )
        }
    }
}
			filter.author?.takeIf { it.isNotBlank() }?.let {
				addQueryParameter("manga_author", it)
			}

			filter.tags.firstOrNull()?.let { tag ->
				when {
					tag.key.startsWith(TAG_PREFIX) -> addQueryParameter("manga_tag", tag.key.removePrefix(TAG_PREFIX))
					tag.key.startsWith(GENRE_PREFIX) -> addQueryParameter(
						"manga_genre",
						tag.key.removePrefix(GENRE_PREFIX),
					)

					else -> addQueryParameter("manga_genre", tag.key)
				}
			}
		}.build()

		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	override fun parseMangaList(doc: Document): List<Manga> {
		val items = doc.select("article")
		if (items.isEmpty()) {
			return super.parseMangaList(doc)
		}
		return items.mapNotNull { item ->
			val link = item.selectFirst("h3 a") ?: item.selectFirst("a") ?: return@mapNotNull null
			val href = link.attrAsRelativeUrl("href")
			val title = link.text().trim().ifEmpty {
				item.selectFirst(".post-title, .manga-name, h3")?.text().orEmpty()
			}
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				coverUrl = item.selectFirst("img")?.src(),
				title = title,
				altTitles = emptySet(),
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				authors = emptySet(),
				state = null,
				source = source,
				contentRating = if (isNsfwSource) ContentRating.ADULT else ContentRating.SAFE,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val href = doc.selectFirst("head meta[property='og:url']")
			?.attr("content")
			?.toRelativeUrl(domain)
			?: manga.url

		val chapterElements = if (doc.select(selectTestAsync).isNotEmpty()) {
			doc.select(selectChapter)
		} else {
			loadChapterElements(href, doc)
		}

		val altTitles = doc.select(".mb-2 .text-gray-400")
			.eachText()
			.flatMap { text ->
				text.substringAfter(':', text)
					.split(',', ';', '\n')
					.map(String::trim)
			}
			.filter { it.isNotEmpty() }
			.toSet()

		val tags = LinkedHashSet<MangaTag>()
		doc.select(
			"a[href*='manga_genre='], a[href*='manga_tag='], a[href*='/genre/'], .genres-content a, .tags-content a",
		).forEach { a ->
			parseTag(a)?.let(tags::add)
		}

		doc.selectFirst(".flex:has(.fa-text-width) .inline-block")
			?.textOrNull()
			?.trim()
			?.takeIf { it.isNotEmpty() }
			?.let {
				tags.add(
					MangaTag(
						key = "type:$it",
						title = it,
						source = source,
					),
				)
			}

		return manga.copy(
			title = doc.selectFirst("h1.text-4xl, h1")?.textOrNull() ?: manga.title,
			url = href,
			publicUrl = href.toAbsoluteUrl(domain),
			coverUrl = doc.selectFirst("div.lg\\:col-span-3 img.wp-post-image, .summary_image img")?.src() ?: manga.coverUrl,
			description = doc.selectFirst(".tab-panel#panel-synopsis .prose")?.html()
				?: doc.select(selectDesc).html(),
			authors = doc.select("a[href*='/author/']")
				.eachText()
				.map(String::trim)
				.filter { it.isNotEmpty() }
				.toSet(),
			altTitles = altTitles,
			tags = tags,
			state = parseState(doc.selectFirst(".tw-status-badge .tw-label")?.text()),
			chapters = parseChapters(chapterElements),
			contentRating = if (doc.selectFirst(".adult-confirm") != null || isNsfwSource) {
				ContentRating.ADULT
			} else {
				ContentRating.SAFE
			},
		)
	}

	override suspend fun getChapters(manga: Manga, doc: Document): List<MangaChapter> {
		return parseChapters(doc.select(selectChapter))
	}

	override suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> {
		return parseChapters(loadChapterElements(mangaUrl, document))
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		val images = doc.select(".reading-content .page-break img")
		if (images.isEmpty()) {
			return super.getPages(chapter)
		}
		return images.mapNotNull { img ->
			val pageUrl = img.src()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
			MangaPage(
				id = generateUid(pageUrl),
				url = pageUrl.toRelativeUrl(domain),
				preview = null,
				source = source,
			)
		}
	}

	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/$listUrl").parseHtml()
		val genres = doc.select("select[name='manga_genre'] option")
		val tags = doc.select("select[name='manga_tag'] option")

		val result = LinkedHashSet<MangaTag>(genres.size + tags.size)

		genres.mapNotNullToSet { option ->
			val key = option.attr("value").trim()
			val title = option.text().trim()
			if (key.isEmpty() || title.isEmpty()) {
				null
			} else {
				MangaTag(
					key = GENRE_PREFIX + key,
					title = title,
					source = source,
				)
			}
		}.forEach(result::add)

		tags.mapNotNullToSet { option ->
			val key = option.attr("value").trim()
			val title = option.text().trim()
			if (key.isEmpty() || title.isEmpty()) {
				null
			} else {
				MangaTag(
					key = TAG_PREFIX + key,
					title = title,
					source = source,
				)
			}
		}.forEach(result::add)

		return if (result.isNotEmpty()) {
			result
		} else {
			super.fetchAvailableTags()
		}
	}

	private fun parseChapters(elements: List<Element>): List<MangaChapter> {
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
		return elements.mapChapters(reversed = true) { i, element ->
			val link = element.selectFirst("a") ?: return@mapChapters null
			val href = link.attrAsRelativeUrl("href")
			val name = link.selectFirst("h3")?.textOrNull()
				?: link.textOrNull()
				?: return@mapChapters null

			val chapterNumber = CHAPTER_NUMBER_REGEX.find(name)?.value?.toFloatOrNull()

			MangaChapter(
				id = generateUid(href),
				title = name,
				number = chapterNumber ?: (i + 1f),
				volume = 0,
				url = href + stylePage,
				uploadDate = parseChapterDate(dateFormat, element.selectFirst("p")?.text()),
				source = source,
				scanlator = null,
				branch = null,
			)
		}
	}

	private suspend fun loadChapterElements(mangaUrl: String, document: Document): List<Element> {
		val doc = if (postReq) {
			val mangaId = document.select("div#manga-chapters-holder").attr("data-id")
			webClient.httpPost(
				"https://$domain/wp-admin/admin-ajax.php",
				"$postDataReq$mangaId",
			).parseHtml()
		} else {
			webClient.httpPost(
				mangaUrl.toAbsoluteUrl(domain).removeSuffix("/") + "/ajax/chapters/",
				emptyMap<String, String>(),
			).parseHtml()
		}
		return doc.select(selectChapter)
	}

	private fun parseTag(a: Element): MangaTag? {
		val title = a.text().trim()
		if (title.isEmpty()) {
			return null
		}
		val href = a.attr("href")
		val key = when {
			"manga_tag=" in href -> TAG_PREFIX + href.substringAfter("manga_tag=").substringBefore("&").substringBefore("#")
			"manga_genre=" in href -> GENRE_PREFIX + href.substringAfter("manga_genre=").substringBefore("&")
				.substringBefore("#")

			"/genre/" in href -> GENRE_PREFIX + href.removeSuffix("/").substringAfterLast("/")
			else -> href.removeSuffix("/").substringAfterLast("/").ifEmpty { title.lowercase(sourceLocale) }
		}
		return MangaTag(
			key = key,
			title = title,
			source = source,
		)
	}

	private fun parseState(text: String?): MangaState? = when (text?.trim()?.lowercase(sourceLocale).orEmpty()) {
		in ongoing -> MangaState.ONGOING
		in finished -> MangaState.FINISHED
		in paused -> MangaState.PAUSED
		in abandoned -> MangaState.ABANDONED
		in upcoming -> MangaState.UPCOMING
		else -> null
	}

	private fun SortOrder.toRoseveilOrder(): String = when (this) {
		SortOrder.POPULARITY -> "views"
		SortOrder.RATING -> "rating"
		SortOrder.ALPHABETICAL -> "title"
		else -> "latest"
	}

	private fun MangaState.toRoseveilStatus(): String? = when (this) {
		MangaState.ONGOING -> "on-going"
		MangaState.FINISHED -> "end"
		MangaState.PAUSED -> "on-hold"
		MangaState.ABANDONED -> "canceled"
		MangaState.UPCOMING -> "upcoming"
		else -> null
	}

	private fun ContentType.toRoseveilType(): String? = when (this) {
		ContentType.MANGA -> "manga"
		ContentType.MANHWA -> "manhwa"
		ContentType.MANHUA -> "manhua"
		ContentType.COMICS -> "comic"
		else -> null
	}

	private companion object {
		private const val GENRE_PREFIX = "genre:"
		private const val TAG_PREFIX = "tag:"
		private val CHAPTER_NUMBER_REGEX = Regex("""\d+(?:\.\d+)?""")
	}
    }
