package org.koitharu.kotatsu.parsers.site.madara.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.util.Locale

@MangaSourceParser("YURILAB", "YuriLab", "id", ContentType.HENTAI)
internal class YuriLab(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.YURILAB, "yurilabs.my.id", pageSize = 20) {

    override val sourceLocale: Locale = Locale("id")

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        val doc = webClient.httpGet("https://$domain").parseHtml()
        
        val tags = doc.select(".genres__collapse ul.list-unstyled a").mapNotNull {
            val name = it.text().replace(Regex("""\(\d+\)"""), "").trim()
            val value = it.attrAsRelativeUrl("href").removeSuffix("/").substringAfterLast("/")
            
            if (name.isNotBlank() && value.isNotBlank()) {
                MangaTag(title = name, key = value, source = source)
            } else {
                null
            }
        }.toSet()

        return MangaListFilterOptions(
            availableTags = tags,
            availableStates = emptySet(),
            availableContentTypes = emptySet(),
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val docs = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
        
        val chapters = docs.select("li.wp-manga-chapter").mapChapters(reversed = false) { i, element ->
            val a = element.selectFirst("a") ?: return@mapChapters null
            MangaChapter(
                id = generateUid(a.attrAsRelativeUrl("href")),
                title = a.text().trim(),
                url = a.attrAsRelativeUrl("href"),
                number = Regex("""(?i)chapter\s*(\d+(?:\.\d+)?)""").find(a.text())?.groupValues?.get(1)?.toFloatOrNull() ?: (i + 1f),
                volume = 0,
                scanlator = null,
                uploadDate = 0,
                branch = null,
                source = source,
            )
        }

        val parsedAuthor = docs.selectFirst("div.author-content a")?.text()
        val parsedDescription = docs.select("div.summary__content p").joinToString("\n") { it.text() }
        val parsedTags = docs.select("div.genres-content a").mapNotNull {
            val tagText = it.text().trim()
            if (tagText.isNotBlank()) MangaTag(title = tagText, key = tagText, source = source) else null
        }.toSet()

        val parsedStatus = docs.selectFirst("div.post-content_item:has(h5:contains(Status)) div.summary-content")?.text()?.trim()
        val state = if (parsedStatus?.contains("OnGoing", ignoreCase = true) == true) {
            MangaState.ONGOING
        } else if (parsedStatus?.contains("Completed", ignoreCase = true) == true || parsedStatus?.contains("End", ignoreCase = true) == true) {
            MangaState.FINISHED
        } else {
            null
        }

        return manga.copy(
            description = parsedDescription,
            authors = setOfNotNull(parsedAuthor),
            tags = parsedTags,                 
            state = state ?: manga.state,
            coverUrl = docs.selectFirst("div.summary_image img")?.src() ?: manga.coverUrl,
            chapters = chapters,
            contentRating = ContentRating.ADULT,
        )
    }
}
