package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("SOFTKOMIK", "Softkomik", "id")
internal class Softkomik(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.SOFTKOMIK, 20) {

    override val configKeyDomain = ConfigKey.Domain("softkomik.co")

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST, SortOrder.POPULARITY)

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchTags(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
        availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA)
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = true
        )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
            append(domain)
            if (!filter.query.isNullOrEmpty()) {
                append("/komik/list?name=")
                append(filter.query.urlEncoded())
            } else {
                append("/komik/library?page=")
                append(page)
                
                val genre = filter.tags.firstOrNull()?.key ?: ""
                if (genre.isNotEmpty()) {
                    append("&genre=")
                    append(genre)
                }

                val state = filter.states.oneOrThrowIfMany()
                if (state != null) {
                    append("&status=")
                    append(if (state == MangaState.ONGOING) "ongoing" else "completed")
                }

                append("&sortBy=")
                append(if (order == SortOrder.POPULARITY) "view" else "newKomik")
            }
        }

        val doc = webClient.httpGet(url).parseHtml()

        return doc.select(".listupd .bs, .grid-container .bs, .grid-item").mapNotNull { el ->
            val link = el.selectFirst("a") ?: return@mapNotNull null
            val mangaUrl = link.attr("href").removePrefix("https://$domain").removePrefix("http://$domain")
            if (mangaUrl.contains("/chapter/")) return@mapNotNull null

            Manga(
                id = generateUid(mangaUrl),
                title = el.select(".tt, .title, h3").text().trim(),
                altTitles = emptySet(),
                url = mangaUrl,
                publicUrl = "https://$domain$mangaUrl",
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = el.select("img").src() ?: "",
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source
            )
        }.distinctBy { it.id }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl).parseHtml()
        
        val chapters = doc.select("#chapterlist li, .clist li").mapIndexed { i, el ->
            val link = el.selectFirst("a") ?: return@mapIndexed null
            val href = link.attr("href").removePrefix("https://$domain").removePrefix("/")
            val title = link.select(".chapt, .cap-text").text().trim().ifEmpty { link.text().trim() }
            
            MangaChapter(
                id = generateUid(href),
                title = title,
                url = href,
                number = title.replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: (i + 1f),
                volume = 0,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = source
            )
        }.filterNotNull().reversed()

        return manga.copy(
            description = doc.select(".entry-content p, .desc, .synopsis").text().trim(),
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet("https://$domain/${chapter.url}").parseHtml()
        return doc.select("#readerarea img, .reader-area img, .entry-content img").mapNotNull { img ->
            val imageUrl = img.src() ?: img.attr("data-src") ?: return@mapNotNull null
            if (imageUrl.contains(Regex("logo|ads|banner|iklan", RegexOption.IGNORE_CASE))) return@mapNotNull null
            
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source
            )
        }
    }

    private fun fetchTags(): Set<MangaTag> {
        return setOf(
            "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror", 
            "Martial Arts", "Mystery", "Romance", "Sci-Fi", "Slice of Life", 
            "Sports", "Supernatural", "Tragedy", "Yaoi", "Yuri", "School", 
            "Isekai", "Military", "Magic", "Parody", "Super Power", "Demon", 
            "Police", "Cooking", "Gore", "Webtoons", "Game", "Music", "Zombies", 
            "Reincarnation", "Medical", "Thriller", "Crime", "Harem", "Historical", 
            "Josei", "Mature", "Mecha", "Psychological", "School Life", "Seinen", 
            "Shoujo", "Gender Bender", "Shoujo Ai", "Shounen", "Shounen Ai", "Ecchi"
        ).map { title ->
            MangaTag(
                key = title.lowercase().replace(" ", "-"), 
                title = title, 
                source = source
            )
        }.toSet()
    }
}
