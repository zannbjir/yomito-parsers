package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("THRIVE", "Thrive", "id")
class Thrive(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.THRIVE, 20) {

    override val configKeyDomain = ConfigKey.Domain("thrive.moe")

    override val filterCapabilities = MangaListFilterCapabilities(
        isSearchSupported = true,
        isSearchWithFiltersSupported = false
    )

    override suspend fun getFilterOptions() = MangaListFilterOptions()

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .build()

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = when {
            !filter.query.isNullOrEmpty() -> "https://$domain/search?q=${filter.query.urlEncoded()}"
            else -> "https://$domain/"
        }

        val doc = webClient.httpGet(url).parseHtml()
        val scriptData = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return emptyList()
        val json = JSONObject(scriptData)

        val props = json.optJSONObject("props")?.optJSONObject("pageProps") ?: return emptyList()

        val array = props.optJSONArray("terbaru")
            ?: props.optJSONArray("res")   
            ?: props.optJSONArray("data")  
            ?: return emptyList()

        return array.mapJSON { jo ->
            val id = jo.getString("id")
            val coverRaw = jo.optString("cover", "")
            val cover = if (coverRaw.startsWith("http")) coverRaw else "https://uploads.mangadex.org/covers/$id/$coverRaw"

            Manga(
                id = generateUid(id),
                title = jo.getString("title").trim(),
                altTitle = null,
                url = "/title/$id",
                publicUrl = "https://$domain/title/$id",
                rating = 0f,
                isNsfw = false,
                coverUrl = cover,
                tags = emptySet(),
                state = null,
                author = null,
                source = source
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet("https://$domain${manga.url}").parseHtml()
        val scriptData = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return manga
        val json = JSONObject(scriptData)

        val props = json.optJSONObject("props")?.optJSONObject("pageProps") ?: return manga

        val description = props.optString("desc_ID")?.trim()
            ?: props.optJSONObject("desc")?.optString("en")?.trim()
            ?: ""

        val tagsList = props.optJSONArray("tags")?.map { it.toString() } ?: emptyList()
        val tags = tagsList.map { MangaTag(it.lowercase().replace(" ", "-"), it, source) }.toSet()

        val chaptersArray = props.optJSONArray("chapterlist") ?: return manga.copy(description = description, tags = tags)

        val dateParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

        val chapters = chaptersArray.mapJSON { ch ->
            val chId = ch.getString("chapter_id")
            val number = ch.optString("chapter_number").toFloatOrNull() ?: 0f
            val titleExtra = ch.optString("chapter_title")?.let { " - $it" } ?: ""

            MangaChapter(
                id = generateUid(chId),
                title = "Chapter $number$titleExtra",
                url = "/read/$chId",
                number = number,
                volume = 0,
                scanlator = ch.optString("scanlator"),
                uploadDate = dateParser.tryParse(ch.optString("created_at")) ?: 0L,
                branch = null,
                source = source
            )
        }.sortedByDescending { it.number }

        return manga.copy(
            description = description,
            tags = tags,
            chapters = chapters
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = webClient.httpGet("https://$domain${chapter.url}").parseHtml()
        val scriptData = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return emptyList()
        val json = JSONObject(scriptData)

        val props = json.optJSONObject("props")?.optJSONObject("pageProps") ?: return emptyList()

        val prefix = props.optString("prefix", "")
        val images = props.optJSONArray("image") ?: return emptyList()

        if (prefix.isEmpty()) return emptyList()

        return images.mapIndexed { index, filename ->
            val url = "https://cdn.thrive.moe/data/$prefix/${index + 1}-$filename"
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source
            )
        }
    }
}
