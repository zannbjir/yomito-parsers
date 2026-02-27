package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.util.*

@MangaSourceParser("RYZUKOMIK", "Ryzukomik", "id")
internal class Ryzukomik(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.RYZUKOMIK, 24) {

    override val configKeyDomain = ConfigKey.Domain("ryzukomik.my.id")

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("referer", "https://$domain/")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.NEWEST
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = fetchTags(),
            availableStates = EnumSet.of(
                MangaState.ONGOING,
                MangaState.FINISHED,
            ),
            availableContentTypes = EnumSet.of(
                ContentType.MANGA,
                ContentType.MANHWA,
                ContentType.MANHUA,
            ),
        )
    }

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = true,
            isSearchSupported = true,
            isSearchWithFiltersSupported = true,
        )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)
            append("/browse-data")
            append("?page=")
            append(page.toString())
            
            if (!filter.query.isNullOrEmpty()) {
                append("&q=")
                append(filter.query.urlEncoded())
            }

            filter.states.oneOrThrowIfMany()?.let {
                append("&status=")
                append(if (it == MangaState.FINISHED) "Completed" else "ongoing")
            }

            if (filter.types.isNotEmpty()) {
                filter.types.forEach {
                    append("&type=")
                    append(it.name.lowercase())
                }
            }

            if (filter.tags.isNotEmpty()) {
                append("&genre=")
                append(filter.tags.joinToString(",") { it.key })
            }
        }

        val json = webClient.httpGet(url).parseJson()
        val data = json.getJSONArray("data")

        return data.mapJSON { jo ->
            val slug = jo.getString("slug")
            Manga(
                id = generateUid(slug),
                url = "/komik/$slug",
                publicUrl = "https://$domain/komik/$slug",
                title = jo.getString("title"),
                coverUrl = jo.getString("cover"),
                state = when (jo.getString("status").lowercase()) {
                    "ongoing" -> MangaState.ONGOING
                    "completed" -> MangaState.FINISHED
                    else -> null
                },
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet("https://$domain${manga.url}").parseHtml()
        
        return manga.copy(
            description = doc.select("#desk-content").text(),
            authors = setOf(doc.select("div.text-neutral-200").first()?.text()?.removeSuffix(" (Author)") ?: ""),
            tags = doc.select("a[class*='bg-neutral-800']").map { el ->
                MangaTag(
                    key = el.attr("href").substringAfter("genre=").ifEmpty { el.text().lowercase() },
                    title = el.text(),
                    source = source
                )
            }.toSet(),
            chapters = doc.select("div.chapter-list a").map { el ->
                val chapterUrl = el.attr("href").removePrefix("/")
                val title = el.select("span.visited-chapter").text()
                MangaChapter(
                    id = generateUid(chapterUrl),
                    title = title,
                    number = title.filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: 0f,
                    url = chapterUrl,
                    source = source,
                )
            }.reversed()
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet("https://$domain/${chapter.url}").parseHtml()
        
        return doc.select("#chap-img img").map { img ->
            val imageUrl = img.attr("src")
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                source = source
            )
        }
    }

    private fun fetchTags(): Set<MangaTag> {
        return setOf(
            "4-koma", "action", "adult", "adventure", "anthology", "comedy", "cooking",
            "crime", "crossdressing", "demon", "drama", "ecchi", "fantasy", "game",
            "gender-bender", "gore", "harem", "historical", "horror", "isekai",
            "josei", "magic", "martial-arts", "mature", "mecha", "mystery", "psychological",
            "romance", "school-life", "sci-fi", "seinen", "shoujo", "shounen", "slice-of-life",
            "supernatural", "thriller", "tragedy", "vampire", "webtoons"
        ).map { slug ->
            MangaTag(key = slug, title = slug.replace("-", " ").capitalize(), source = source)
        }.toSet()
    }
}
