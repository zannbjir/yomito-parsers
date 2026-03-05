package org.koitharu.kotatsu.parsers.site.id

import org.json.JSONObject
import org.jsoup.Jsoup
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("THRIVE", "Thrive", "id")
internal class Thrive(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.THRIVE, 20) {

    override val configKeyDomain = ConfigKey.Domain("thrive.moe")

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST)

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
            isSearchSupported = true,
            isSearchWithFiltersSupported = false
        )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions()
    }

    private fun getNextData(html: String): JSONObject {
        val document = Jsoup.parse(html)
        val scriptData = document.selectFirst("script#__NEXT_DATA__")?.data()
            ?: throw Exception("Data JSON tidak ditemukan")
        return JSONObject(scriptData).getJSONObject("props").getJSONObject("pageProps")
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = if (filter.query.isNullOrEmpty()) {
            if (page > 1) "https://$domain/?page=$page" else "https://$domain/"
        } else {
            "https://$domain/search?q=${filter.query.urlEncoded()}"
        }

        val response = webClient.httpGet(url)
        val html = response.body.use { it.string() }
        val props = getNextData(html)
        
        val array = props.optJSONArray("terbaru") 
            ?: props.optJSONArray("res") 
            ?: props.optJSONArray("data") 
            ?: return emptyList()

        return array.mapJSON { jo ->
            val id = jo.getString("id")
            Manga(
                id = generateUid(id),
                url = "/title/$id",
                publicUrl = "https://$domain/title/$id",
                title = jo.getString("title").trim(),
                altTitles = emptySet(),
                coverUrl = jo.optString("cover"),
                largeCoverUrl = jo.optString("cover"),
                authors = emptySet(),
                tags = emptySet(),
                state = MangaState.ONGOING,
                description = null,
                contentRating = null,
                source = source,
                rating = RATING_UNKNOWN
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val response = webClient.httpGet(manga.publicUrl)
        val html = response.body.use { it.string() }
        val props = getNextData(html)

        val chaptersArray = props.optJSONArray("chapterlist")
        val chapters = mutableListOf<MangaChapter>()
        
        if (chaptersArray != null) {
            for (i in 0 until chaptersArray.length()) {
                val ch = chaptersArray.getJSONObject(i)
                val chId = ch.getString("chapter_id")
                
                chapters.add(MangaChapter(
                    id = generateUid(chId),
                    title = "Chapter ${ch.optString("chapter_number")}",
                    url = "read/$chId",
                    number = ch.optString("chapter_number").replace(",", ".").toFloatOrNull() ?: 0f,
                    uploadDate = ch.optString("created_at").parseDate(),
                    scanlator = ch.optString("scanlator"),
                    branch = null,
                    source = source,
                    volume = 0
                ))
            }
        }

        return manga.copy(
            description = props.optString("desc_ID", props.optString("desc_EN")),
            state = if (props.optString("status").contains("ongoing", true)) MangaState.ONGOING else MangaState.FINISHED,
            tags = props.optJSONArray("tags")?.mapJSONToSet { tag ->
                MangaTag(tag.getString("name"), tag.getString("name"), source)
            }.orEmpty(),
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val response = webClient.httpGet("https://$domain/${chapter.url}")
        val html = response.body.use { it.string() }
        val props = getNextData(html)
        
        val prefix = props.optString("prefix")
        val images = props.getJSONArray("image")
        
        return List(images.length()) { i ->
            val imageUrl = "https://cdn.thrive.moe/data/$prefix/${images.getString(i)}"
            MangaPage(
                id = generateUid(imageUrl),
                url = imageUrl,
                preview = null,
                source = source
            )
        }
    }

    private fun String.parseDate(): Long {
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(this.substringBefore("."))?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
