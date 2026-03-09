package org.koitharu.kotatsu.parsers.site.mangareader.id

import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
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

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "https://$domain/")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Accept-Encoding", "gzip, deflate, br")
        .build()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        return try {
            val url = when {
                !filter.query.isNullOrEmpty() -> {
                    "https://$domain/s/all".toHttpUrl().newBuilder()
                        .addQueryParameter("q", filter.query)
                        .addQueryParameter("post_type", "post")
                        .addQueryParameter("s", filter.query)
                        .build()
                }
                else -> {
                    "https://$domain/page/$page".toHttpUrl()
                }
            }

            val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
            val mangaList = mutableListOf<Manga>()

            // MangaThemesia template selectors
            doc.select(".post-title a, .series-title a, .entry-title a").forEach { element ->
                try {
                    val href = element.absUrl("href").takeIf { it.isNotBlank() } ?: return@forEach
                    val title = element.text().trim().takeIf { it.isNotBlank() } ?: return@forEach
                    val slug = href.substringAfterLast("/").substringBefore("?")

                    // Get cover image
                    val coverUrl = element.closest(".post-item, .series-item, .entry-item")
                        ?.selectFirst("img")
                        ?.let { imgExtractUrl(it) }
                        ?: ""

                    mangaList.add(Manga(
                        id = generateUid(slug),
                        title = title,
                        altTitles = emptySet(),
                        url = slug,
                        publicUrl = href,
                        rating = RATING_UNKNOWN,
                        contentRating = null,
                        coverUrl = coverUrl,
                        tags = emptySet(),
                        state = null,
                        authors = emptySet(),
                        source = source
                    ))
                } catch (e: Exception) {
                    // Skip this entry and continue
                }
            }

            mangaList
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        return try {
            val doc = webClient.httpGet("https://$domain/${manga.url}", getRequestHeaders()).parseHtml()

            val title = doc.selectFirst("h1.judul-komik, h1.entry-title")?.text()?.trim() ?: manga.title
            
            val description = doc.selectFirst(".sinopsis, .series-desc, .entry-content")
                ?.text()?.trim()
            
            val authors = mutableSetOf<String>()
            doc.select(".keterangan-komik").forEach { el ->
                if (el.text().contains("author", ignoreCase = true)) {
                    el.selectFirst("span")?.text()?.trim()?.let { authors.add(it) }
                }
            }

            val tags = doc.select(".genre-komik a, .series-genre a").mapNotNull { el ->
                val tagText = el.text().trim()
                if (tagText.isNotBlank()) {
                    MangaTag(
                        title = tagText,
                        key = tagText.lowercase().replace(" ", "-"),
                        source = source
                    )
                } else null
            }.toSet()

            val chapters = doc.select(".list-chapter a").mapNotNull { el ->
                try {
                    val chapterUrl = el.absUrl("href")
                    val chapterTitle = el.selectFirst(".nomer-chapter, .chapter-title")?.text()?.trim()
                        ?: el.text().trim()
                    val chapterNum = extractChapterNumber(chapterTitle)

                    MangaChapter(
                        id = generateUid(chapterUrl),
                        title = chapterTitle,
                        url = chapterUrl.substringAfter("$domain"),
                        number = chapterNum,
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
            val doc = webClient.httpGet("https://$domain${chapter.url}", getRequestHeaders()).parseHtml()
            
            // First try: Regular image parsing
            val pages = mutableListOf<MangaPage>()
            doc.select(".reading-content img, .page-break img, .entry-content img").forEachIndexed { idx, img ->
                val imageUrl = imgExtractUrl(img)
                if (imageUrl.isNotBlank()) {
                    pages.add(MangaPage(
                        id = generateUid(imageUrl),
                        url = imageUrl,
                        preview = null,
                        source = source
                    ))
                }
            }

            if (pages.isNotEmpty()) {
                return pages
            }

            // Fallback: Try AJAX method (Siren Komik specific)
            val postIdRegex = Regex("""chapter_id\s*=\s*(\d+)""")
            val postId = doc.select("script").mapNotNull { script ->
                postIdRegex.find(script.data())?.groupValues?.get(1)
            }.firstOrNull()

            if (postId != null) {
                return fetchPagesViaAjax(postId)
            }

            pages
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchPagesViaAjax(postId: String): List<MangaPage> {
        return try {
            val formBody = FormBody.Builder()
                .add("action", "get_image_json")
                .add("post_id", postId)
                .build()

            val response = webClient.httpPost("https://$domain/wp-admin/admin-ajax.php", formBody, getRequestHeaders())
            val bodyStr = response.body?.string() ?: return emptyList()

            val json = JSONObject(bodyStr)
            val dataObj = json.optJSONObject("data")
            val sourcesArray = dataObj?.optJSONArray("sources") ?: return emptyList()

            val pages = mutableListOf<MangaPage>()
            for (i in 0 until sourcesArray.length()) {
                val source = sourcesArray.getJSONObject(i)
                val imagesArray = source.optJSONArray("images") ?: continue
                
                for (j in 0 until imagesArray.length()) {
                    val imageUrl = imagesArray.optString(j)
                    if (imageUrl.isNotBlank()) {
                        pages.add(MangaPage(
                            id = generateUid(imageUrl),
                            url = imageUrl,
                            preview = null,
                            source = source as org.koitharu.kotatsu.parsers.model.MangaSource
                        ))
                    }
                }
            }

            pages
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun imgExtractUrl(img: org.jsoup.nodes.Element): String {
        return when {
            img.hasAttr("data-lazy-src") -> img.absUrl("data-lazy-src")
            img.hasAttr("data-src") -> img.absUrl("data-src")
            img.attributes().any { it.key.endsWith("original-src") } -> {
                img.attributes()
                    .find { it.key.endsWith("original-src") }
                    ?.let { img.absUrl(it.key) } ?: img.absUrl("src")
            }
            else -> img.absUrl("src")
        }
    }

    private fun extractChapterNumber(text: String): Float {
        val regex = Regex("""(\d+(?:\.\d+)?)""")
        return regex.find(text)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
    }
}
