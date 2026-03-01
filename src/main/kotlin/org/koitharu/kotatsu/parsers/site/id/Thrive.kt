package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.util.*

@MangaSourceParser("THRIVE", "Thrive", "id")
internal class Thrive(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.THRIVE, 20) {

    override val configKeyDomain = ConfigKey.Domain("thrive.moe")

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .add("User-Agent", DEFAULT_USER_AGENT)
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.NEWEST)

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://$domain/")
            if (!filter.query.isNullOrEmpty()) {
                append("search?q=${filter.query.urlEncoded()}")
            }
        }
        val doc = webClient.httpGet(url).parseHtml()
        val json = doc.selectFirst("script#__NEXT_DATA__")?.data()?.let { JSONObject(it) }
            ?: return emptyList()

        val props = json.optJSONObject("props")?.optJSONObject("pageProps") ?: return emptyList()

        val mangaArray = when {
            props.has("terbaru") -> props.getJSONArray("terbaru")
            props.has("res") -> props.getJSONArray("res")        
            props.has("data") -> props.getJSONArray("data")       
            else -> return emptyList()
        }

        return mangaArray.mapJSON { jo ->
            val id = jo.getString("id")
            val cover = jo.optString("cover")?.let { 
                if (it.startsWith("http")) it else "https://uploads.mangadex.org/covers/$id/$it"
            } ?: ""

            Manga(
                id = generateUid(id),
                title = jo.getStringOrNull("title")?.trim() ?: "Untitled",
                url = "/title/$id",
                publicUrl = "https://$domain/title/$id",
                coverUrl = cover,
                source = source,
                rating = RATING_UNKNOWN,
                contentRating = ContentRating.SAFE,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val fullUrl = "https://$domain${manga.url}"
        val doc = webClient.httpGet(fullUrl).parseHtml()
        val json = doc.selectFirst("script#__NEXT_DATA__")?.data()?.let { JSONObject(it) }
            ?: return manga

        val props = json.optJSONObject("props")?.optJSONObject("pageProps") ?: return manga

        val desc = props.optString("desc_ID")?.takeIf { it.isNotBlank() }
            ?: props.optJSONObject("desc")?.optString("en")
            ?: ""

        val tags = props.optJSONArray("tags")?.map { it.toString() } ?: emptyList()

        val chaptersArray = props.optJSONArray("chapterlist") ?: return manga.copy(description = desc)

        val chapters = chaptersArray.mapJSON { ch ->
            val chId = ch.getString("chapter_id")
            val numberStr = ch.optString("chapter_number")
            val number = numberStr.toFloatOrNull() ?: 0f
            val titleExtra = ch.optString("chapter_title")?.let { " - $it" } ?: ""

            MangaChapter(
                id = generateUid(chId),
                title = "Chapter $number$titleExtra",
                url = "/read/$chId",
                number = number,
                volume = 0,
                scanlator = ch.optString("scanlator"),
                uploadDate = dateFormat.tryParse(ch.optString("created_at")) ?: 0L,
                branch = null,
                source = source
            )
        }.sortedByDescending { it.number }

        return manga.copy(
            description = desc.trim().takeIf { it.isNotBlank() },
            tags = tags.map { MangaTag(it.lowercase().replace(" ", "-"), it, source) }.toSet(),
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet("https://$domain${chapter.url}").parseHtml()
        val json = doc.selectFirst("script#__NEXT_DATA__")?.data()?.let { JSONObject(it) }
            ?: return emptyList()

        val props = json.optJSONObject("props")?.optJSONObject("pageProps") ?: return emptyList()

        val prefix = props.optString("prefix")
        val images = props.optJSONArray("image") ?: return emptyList()

        if (prefix.isEmpty()) return emptyList()

        return images.map { filename ->
            val url = "https://cdn.thrive.moe/data/$prefix/$filename"
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source
            )
        }
    }

    override val defaultIsNsfw: Boolean = false
}
