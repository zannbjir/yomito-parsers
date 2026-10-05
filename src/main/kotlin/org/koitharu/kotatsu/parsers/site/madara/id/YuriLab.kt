package org.koitharu.kotatsu.parsers.site.madara.id

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@MangaSourceParser("YURILAB", "YuriLab", "id", ContentType.HENTAI)
internal class YuriLab(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.YURILAB, "yurilab.top", pageSize = 30) {

    override val sourceLocale: Locale = Locale.ENGLISH
    override val withoutAjax = true

    override val filterCapabilities: MangaListFilterCapabilities
        get() = super.filterCapabilities.copy(isMultipleTagsSupported = false)

    override fun getRequestHeaders(): Headers = super.getRequestHeaders().newBuilder()
        .set("Referer", "https://$domain/")
        .build()

    override fun parseMangaList(doc: Document): List<Manga> {
        val elements = doc.select("div.row.c-tabs-item__content").ifEmpty {
            doc.select("div.page-item-detail, div.manga__item")
        }
        if (elements.isEmpty()) {
            return emptyList()
        }
        return elements.mapNotNull { div ->
            val a = div.selectFirst("a") ?: return@mapNotNull null
            val href = a.attrAsRelativeUrl("href")
            val summary = div.selectFirst(".tab-summary") ?: div.selectFirst(".item-summary")
            val author = summary?.selectFirst(".mg_author, .mg_artists")?.selectFirst("a")?.ownText()
            val coverUrl = div.selectFirst("img")?.src()?.replace(Regex("""-\d+x\d+(?=\.\w+$)"""), "")
            val title = (div.selectFirst(".post-title a, h2 a, h3 a, h4 a, .manga__content a, .manga-name a")
                ?: div.selectFirst("a[href*='/series/']:not(:has(img))")
                ?: summary?.selectFirst("h3 a, h4 a")
                ?: summary?.selectFirst("h3, h4")
                ?: div.selectFirst(".manga-name, .post-title"))?.text()?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null

            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(div.host ?: domain),
                coverUrl = coverUrl,
                title = title,
                altTitles = emptySet(),
                rating = div.selectFirst("span.total_votes")?.ownText()?.toFloatOrNull()?.div(5f) ?: RATING_UNKNOWN,
                tags = summary?.selectFirst(".mg_genres")?.select("a")?.mapNotNullToSet { tagEl ->
                    val tagHref = tagEl.attr("href").removeSuffix('/').substringAfterLast('/')
                    val tagTitle = tagEl.text().ifEmpty { return@mapNotNullToSet null }.toTitleCase(sourceLocale)
                    MangaTag(
                        key = tagHref,
                        title = tagTitle,
                        source = source,
                    )
                }.orEmpty(),
                authors = setOfNotNull(author),
                state = when (
                    summary?.selectFirst(".mg_status")
                        ?.selectFirst(".summary-content")
                        ?.ownText()?.lowercase()
                        .orEmpty()
                ) {
                    in ongoing -> MangaState.ONGOING
                    in finished -> MangaState.FINISHED
                    in abandoned -> MangaState.ABANDONED
                    in paused -> MangaState.PAUSED
                    in upcoming -> MangaState.UPCOMING
                    else -> null
                },
                source = source,
                contentRating = if (isNsfwSource) ContentRating.ADULT else null,
            )
        }
    }

    override suspend fun fetchAvailableTags(): Set<MangaTag> {
        val url = urlBuilder().apply {
            addQueryParameter("s", "")
            addQueryParameter("post_type", "wp-manga")
        }.build()
        val docs = webClient.httpGet(url).parseHtml()
        val genreLinks = docs.select(".genres-filter .dropdown-menu a[href*='genre=']")
        return genreLinks.mapNotNullToSet { el ->
            val href = el.attrOrNull("href") ?: return@mapNotNullToSet null
            val match = Regex("""genre=([^&]+)""").find(href)
            val key = match?.groupValues?.get(1) ?: return@mapNotNullToSet null
            val title = el.textOrNull()?.trim()?.toTitleCase(sourceLocale) ?: return@mapNotNullToSet null
            MangaTag(
                title = title,
                key = key,
                source = source,
            )
        }
    }

    override val selectGenre = ".genres-content a[href*='genre'], .tags-content a[href*='tag']"

    override suspend fun createMangaTag(a: Element): MangaTag? {
        val href = a.attrOrNull("href") ?: return null
        val tagKey = extractTagKey(href) ?: return null
        val title = a.textOrNull()?.trim() ?: return null
        return MangaTag(
            title = title,
            key = tagKey,
            source = source,
        )
    }

    private fun extractTagKey(href: String): String? {
        val genreMatch = Regex("""genre=([^&/?]+)""").find(href)
        if (genreMatch != null) return genreMatch.groupValues[1]
        val pattern = Regex("""series-genre/([^/?]+)|series-tag/([^/?]+)""", RegexOption.IGNORE_CASE)
        return pattern.find(href)?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() }
            ?: pattern.find(href)?.groupValues?.getOrNull(2)?.takeIf { it.isNotEmpty() }
    }

    override val selectChapter = "ul.version-chap li.wp-manga-chapter"

    private fun transformChapterName(element: Element, name: String): String {
        return if (element.hasClass("premium") || element.hasClass("premium-block")) {
            "🔒 ${name.trim()}"
        } else {
            name.trim()
        }
    }

    private fun String?.applyChapterNumber(index: Int): String {
        val base = this ?: "Chapter"
        return if (base == "Chapter" || base == "🔒 Chapter") {
            base.replace("Chapter", "Chapter ${index + 1}")
        } else {
            base
        }
    }

    override suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> = coroutineScope {
        val allChapters = mutableListOf<MangaChapter>()
        var page = 1
        val batchSize = 5
        val dateFormat = SimpleDateFormat("d MMMM yyyy", sourceLocale)

        while (true) {
            val deferreds = (page until page + batchSize).map { currentPage ->
                async {
                    runCatching {
                        val ajaxUrl = mangaUrl.toAbsoluteUrl(domain).removeSuffix("/") + "/ajax/chapters/?t=$currentPage"
                        val ajaxDocs = webClient.httpPost(
                            ajaxUrl.toHttpUrl(),
                            emptyMap(),
                            Headers.Builder().add("X-Requested-With", "XMLHttpRequest").build(),
                        ).parseHtml()

                        val lis = ajaxDocs.select(selectChapter)
                        lis.mapNotNull { li ->
                            val a = li.selectFirst("a") ?: return@mapNotNull null
                            val rawHref = a.attrAsRelativeUrl("href")

                            val baseName = a.ownText().ifEmpty { null } ?: a.selectFirst("p")?.textOrNull()
                            ?: "Chapter"

                            val finalName = transformChapterName(li, baseName)

                            var dateText = li.selectFirst("a.c-new-tag")?.attr("title") ?: li.selectFirst(selectDate)?.text()
                            if (dateText != null && !dateText.contains("ago", true) && !dateText.contains(Regex("""\d{4}"""))) {
                                val year = Calendar.getInstance().get(Calendar.YEAR)
                                dateText = "$dateText $year"
                            }

                            MangaChapter(
                                id = generateUid(rawHref),
                                url = rawHref,
                                title = finalName,
                                number = 0f,
                                volume = 0,
                                branch = null,
                                uploadDate = parseChapterDate(dateFormat, dateText),
                                scanlator = null,
                                source = source,
                            )
                        }
                    }.getOrDefault(emptyList())
                }
            }

            val batches = deferreds.awaitAll()
            var emptyBatch = false
            for (pageChapters in batches) {
                if (pageChapters.isEmpty()) {
                    emptyBatch = true
                    break
                }
                allChapters.addAll(pageChapters)
            }

            if (emptyBatch) {
                break
            }
            page += batchSize
        }
        allChapters.reversed().mapIndexed { index, chapter ->
            chapter.copy(
                title = chapter.title.applyChapterNumber(index),
                number = (index + 1).toFloat()
            )
        }
    }
}