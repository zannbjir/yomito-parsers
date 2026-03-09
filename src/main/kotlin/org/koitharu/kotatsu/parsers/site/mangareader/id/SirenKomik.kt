package org.koitharu.kotatsu.parsers.site.mangareader.id

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("SIRENKOMIK", "SirenKomik", "id")
internal class SirenKomik(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.SIRENKOMIK, pageSize = 20) {

    override val configKeyDomain = ConfigKey.Domain("sirenkomik.xyz")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY
    )

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isTagsExclusionSupported = false
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions()

    private fun getHeaders(): Headers = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        .add("Referer", "https://$domain/")
        .build()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        return try {
            val url = when {
                !filter.query.isNullOrEmpty() -> "https://$domain/s/all?q=${filter.query}&s=${filter.query}"
                else -> "https://$domain/page/$page"
            }

            val doc = webClient.httpGet(url, getHeaders()).parseHtml()
            val mangaList = mutableListOf<Manga>()

            doc.select(".post-title a, .series-title a").forEach { element ->
                try {
                    val href = element.absUrl("href")
                    val title = element.text().trim()
                    if (href.isBlank() || title.isBlank()) return@forEach

                    val slug = href.substringAfterLast("/").substringBefore("?")
                    val cover = element.closest(".post-item, .series-item")?.selectFirst("img")?.absUrl("src") ?: ""

                    mangaList.add(Manga(
                        id = generateUid(slug),
                        title = title,
                        altTitles = emptySet(),
                        url = slug,
                        publicUrl = href,
                        rating = RATING_UNKNOWN,
                        contentRating = null,
                        coverUrl = cover,
                        tags = emptySet(),
                        state = null,
                        authors = emptySet(),
                        source = source
                    ))
                } catch (e: Exception) {
                    // Skip
                }
            }
            mangaList
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        return try {
            val doc = webClient.httpGet("https://$domain/${manga.url}", getHeaders()).parseHtml()

            val title = doc.selectFirst("h1")?.text()?.trim() ?: manga.title
            val description = doc.selectFirst(".sinopsis, .series-desc")?.text()?.trim()
            
            val authors = mutableSetOf<String>()
            doc.select(".keterangan-komik").forEach { el ->
                if (el.text().contains("author", ignoreCase = true)) {
                    el.selectFirst("span")?.text()?.trim()?.let { authors.add(it) }
                }
            }

            val tags = doc.select(".genre-komik a").mapNotNull { el ->
                val text = el.text().trim()
                if (text.isNotBlank()) MangaTag(text, text.lowercase(), source) else null
            }.toSet()

            val chapters = doc.select(".list-chapter a").mapNotNull { el ->
                try {
                    val url = el.absUrl("href")
                    val title = el.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val num = title.substringAfterLast(" ").toFloatOrNull() ?: 0f

                    MangaChapter(
                        id = generateUid(url),
                        title = title,
                        url = url.substringAfter("$domain"),
                        number = num,
                        volume = 0,
                        scanlator = null,
                        uploadDate = 0L,
                        branch = null,
                        source = source
                    )
                } catch (e: Exception) {
                    null
                }
            }.sortedByDescending { it.number }

            manga.copy(
                title = title,
                description = description,
                authors = authors,
                tags = tags,
                chapters = chapters
            )
        } catch (e: Exception) {
            manga
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        return try {
            val doc = webClient.httpGet("https://$domain${chapter.url}", getHeaders()).parseHtml()
            
            val pages = mutableListOf<MangaPage>()
            doc.select(".reading-content img, .page-break img").forEach { img ->
                val url = when {
                    img.hasAttr("data-lazy-src") -> img.absUrl("data-lazy-src")
                    img.hasAttr("data-src") -> img.absUrl("data-src")
                    else -> img.absUrl("src")
                }
                if (url.isNotBlank()) {
                    pages.add(MangaPage(generateUid(url), url, null, source))
                }
            }
            pages
        } catch (e: Exception) {
            emptyList()
        }
    }
}
