package org.koitharu.kotatsu.parsers.site.id

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("THRIVE", "Thrive", "id")
internal class Thrive(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.THRIVE, 20) {

    override val configKeyDomain = ConfigKey.Domain("thrive.moe")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST)

    override val filterCapabilities = MangaListFilterCapabilities(
        isMultipleTagsSupported = false,
        isTagsExclusionSupported = false,
        isSearchSupported = true,
        isSearchWithFiltersSupported = true
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val genres = listOf(
            "Action", "Adventure", "Boys' Love", "Comedy", "Crime", "Drama", 
            "Fantasy", "Girls' Love", "Historical", "Horror", "Isekai", "Mecha", 
            "Medical", "Mystery", "Psychological", "Romance", "Sci-Fi", 
            "Slice of Life", "Sports", "Superhero", "Thriller", "Tragedy"
        )
        
        return MangaListFilterOptions(
            availableTags = genres.map { name -> 
                val key = name.lowercase().replace(" ", "-").replace("'", "")
                MangaTag(name, key, source) 
            }
        )
    }

    private fun getNextData(html: String): JSONObject {
        val document = Jsoup.parse(html)
        val scriptData = document.selectFirst("script#__NEXT_DATA__")?.data()
            ?: throw Exception("Data JSON tidak ditemukan")
        val json = JSONObject(scriptData).getJSONObject("props").getJSONObject("pageProps")
        return if (json.has("data")) json.getJSONObject("data") else json
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = when {
            !filter.query.isNullOrEmpty() -> {
                "https://$domain/search?q=${filter.query.urlEncoded()}"
            }
            filter.tags.isNotEmpty() -> {
                "https://$domain/genre/${filter.tags.first().key}?page=$page"
            }
            else -> {
                if (page > 1) "https://$domain/?page=$page" else "https://$domain/"
            }
        }

        val response = webClient.httpGet(url)
        val body = response.body?.string() ?: return emptyList()
        val props = getNextData(body)
        
        val array = props.optJSONArray("terbaru") 
            ?: props.optJSONArray("res") 
            ?: props.optJSONArray("data") 
            ?: return emptyList()

        val mangaList = mutableListOf<Manga>()
        for (i in 0 until array.length()) {
            val jo = array.getJSONObject(i)
            val id = jo.getString("id")
            val cover = jo.optString("cover")
            
            mangaList.add(Manga(
                id = generateUid(id),
                url = "/title/$id",
                publicUrl = "https://$domain/title/$id",
                title = jo.getString("title").trim(),
                altTitles = emptySet(),
                coverUrl = cover,
                largeCoverUrl = cover,
                authors = emptySet(),
                tags = emptySet(),
                state = MangaState.ONGOING,
                description = null,
                contentRating = null,
                source = source,
                rating = RATING_UNKNOWN
            ))
        }
        return mangaList
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val response = webClient.httpGet(manga.publicUrl)
        val body = response.body?.string() ?: throw Exception("Gagal memuat detail")
        val props = getNextData(body)

        val chaptersArray = props.optJSONArray("chapters") ?: props.optJSONArray("chapterlist") ?: JSONArray()
        val chapters = mutableListOf<MangaChapter>()
        
        for (i in 0 until chaptersArray.length()) {
            val ch = chaptersArray.getJSONObject(i)
            val chId = ch.getString("id")
            val chNum = ch.optString("chapter_number", "0")
            
            chapters.add(MangaChapter(
                id = generateUid(chId),
                title = "Chapter $chNum",
                url = "/read/$chId",
                number = chNum.replace(",", ".").toFloatOrNull() ?: 0f,
                uploadDate = ch.optString("created_at").parseDate(),
                scanlator = ch.optString("scanlator").takeIf { it.isNotBlank() },
                branch = null,
                source = source,
                volume = 0
            ))
        }

        val tags = mutableSetOf<MangaTag>()
        props.optJSONArray("tags")?.let { tagArray ->
            for (i in 0 until tagArray.length()) {
                val name = tagArray.getJSONObject(i).getString("name")
                val key = name.lowercase().replace(" ", "-").replace("'", "")
                tags.add(MangaTag(name, key, source))
            }
        }

        return manga.copy(
            description = props.optString("description_id", props.optString("description_en")),
            state = if (props.optString("status").contains("ongoing", true)) MangaState.ONGOING else MangaState.FINISHED,
            tags = tags,
            chapters = chapters.sortedByDescending { it.number }
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = if (chapter.url.startsWith("http")) chapter.url else "https://$domain${chapter.url}"
        val response = webClient.httpGet(fullUrl)
        val body = response.body?.string() ?: return emptyList()
        val props = getNextData(body)
        
        val prefix = props.optString("prefix")
        val images = props.optJSONArray("images") ?: JSONArray()
        
        val pages = mutableListOf<MangaPage>()
        for (i in 0 until images.length()) {
            val imageName = images.getString(i)
            val imageUrl = "https://cdn.thrive.moe/data/$prefix/$imageName"
            pages.add(MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source
            ))
        }
        return pages
    }

    private fun String.parseDate(): Long {
        if (this.isBlank()) return 0L
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(this.substringBefore("."))?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
