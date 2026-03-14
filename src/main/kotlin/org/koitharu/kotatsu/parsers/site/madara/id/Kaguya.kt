package org.koitharu.kotatsu.parsers.site.madara.id

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("KAGUYA", "Kaguya", "id")
internal class Kaguya(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.KAGUYA, "kaguya.my.id") {

    override val sourceLocale: Locale = Locale("id")
    override val datePattern = "dd MMMM yyyy"

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
        .add("Referer", "https://$domain/")
        .add("X-Requested-With", "XMLHttpRequest")
        .build()

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()
        
        val ajaxUrl = "https://$domain/wp-admin/admin-ajax.php".toHttpUrl()
        val mangaId = doc.select("div#manga-chapters-holder").attr("data-id").ifEmpty {
            doc.select("body").attr("class").split(" ").find { it.startsWith("postid-") }?.removePrefix("postid-") ?: ""
        }

        val chapterDoc = webClient.httpPost(
            ajaxUrl,
            mapOf("action" to "manga_get_chapters", "manga" to mangaId),
            getRequestHeaders()
        ).parseHtml()

        val chapters = chapterDoc.select("li.wp-manga-chapter").map { element ->
            val link = element.selectFirst("a")!!
            val href = link.attrAsRelativeUrl("href")
            MangaChapter(
                id = generateUid(href),
                title = link.text().trim(),
                url = href,
                number = 0f, 
                uploadDate = 0L,
                source = source,
                scanlator = null, branch = null, volume = 0
            )
        }

        return manga.copy(
            description = doc.select(".summary__content, .post-content_item").text().trim(),
            chapters = chapters,
            state = if (doc.text().contains("Ongoing", true)) MangaState.ONGOING else MangaState.FINISHED
        )
    }
}
