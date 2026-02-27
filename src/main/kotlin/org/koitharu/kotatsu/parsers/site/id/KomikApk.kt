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

@MangaSourceParser("KOMIKAPK", "KomikAPK", "id")
internal class KomikAPK(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.KOMIKAPK, 20) {

    override val configKeyDomain = ConfigKey.Domain("komikapk.app")

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
        SortOrder.RATING
    )

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return MangaListFilterOptions(
            availableTags = fetchTags(),
            availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
            availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.MANHWA, ContentType.MANHUA)
        )
    }

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true,
            isContentTypeFilterSupported = true
        )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        return try {
            val url = when {
                !filter.query.isNullOrEmpty() -> {
                    "https://$domain/pencarian?q=${filter.query.urlEncoded()}&is-adult=${if (isAdultContent()) "on" else "off"}"
                }
                else -> {
                    val contentType = when {
                        filter.contentTypes.size == 1 -> {
                            when (filter.contentTypes.first()) {
                                ContentType.MANGA -> "manga"
                                ContentType.MANHWA -> "manhwa"
                                ContentType.MANHUA -> "manhua"
                                else -> "semua"
                            }
                        }
                        else -> "semua"
                    }

                    val genre = when {
                        filter.tags.size == 1 -> filter.tags.first().key
                        else -> "semua"
                    }

                    val sort = when (order) {
                        SortOrder.NEWEST -> "terbaru"
                        SortOrder.POPULARITY -> "populer"
                        SortOrder.RATING -> "rating"
                        else -> "terbaru"
                    }

                    buildString {
                        append("https://")
                        append(domain)
                        append("/pustaka/")
                        append(contentType)
                        append("/")
                        append(genre)
                        append("/")
                        append(sort)
                        append("/")
                        append(page)
                        if (isAdultContent()) {
                            append("?include_adult=true")
                        }
                    }
                }
            }

            val doc = webClient.httpGet(url).parseHtml()
            doc.select("a[href^=\"/komik/\"]").mapNotNull { link ->
                val href = link.attr("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val slug = href.removePrefix("/komik/").split("/").first()
                
                if (slug.isBlank()) return@mapNotNull null
                val img = link.selectFirst("img")?.src() ?: return@mapNotNull null
                val type = link.ownText()
                    .split("\n")
                    .firstOrNull { it.lowercase().contains(Regex("manhwa|manga|manhua")) }
                    ?.lowercase()?.trim()
                    ?: "manga"

                val title = link.selectFirst("img")?.attr("alt")
                    ?: link.text().split("\n").lastOrNull()
                    ?: return@mapNotNull null

                val titleClean = title.replace(Regex("(manhwa|manga|manhua|ch\\..*)", RegexOption.IGNORE_CASE), "").trim()
                
                Manga(
                    id = generateUid(slug),
                    title = titleClean,
                    altTitles = emptySet(),
                    url = "/komik/$slug",
                    publicUrl = "https://$domain/komik/$slug",
                    rating = RATING_UNKNOWN,
                    contentRating = null,
                    coverUrl = img.takeIf { it.isNotEmpty() },
                    tags = emptySet(),
                    state = null,
                    authors = emptySet(),
                    source = source
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        return try {
            val slug = manga.url.removePrefix("/komik/").split("/").first()
            val doc = webClient.httpGet(manga.publicUrl).parseHtml()
            val title = doc.selectFirst("h1")?.text()?.trim() ?: manga.title
            val description = doc.selectFirst(".description, [class*='deskripsi'], [class*='synopsis']")?.text()
                ?: doc.select("p").filter { it.text().length > 50 }.firstOrNull()?.text()
            val tags = doc.select("a[href*='/pustaka/'][href*='/terbaru/']").mapNotNull { link ->
                val genre = link.text().trim()
                if (genre.isNotEmpty()) {
                    MangaTag(
                        key = genre.lowercase().replace(" ", "-"),
                        title = genre,
                        source = source
                    )
                } else {
                    null
                }
            }.toSet()

            val defaultUploader = doc.selectFirst("a[href*='?uploader=']")
                ?.attr("href")
                ?.substringAfterLast("uploader=")
                ?.takeIf { it.isNotEmpty() }
                ?: "kmapk"

            val chapters = doc.select("a[href^=\"/komik/$slug/\"]").mapNotNull { link ->
                val href = link.attr("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                if (!href.contains("/") || href.split("/").size < 4) return@mapNotNull null

                val parts = href.split("/")
                val uploader = parts.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: defaultUploader
                val chapterNum = parts.lastOrNull()?.toFloatOrNull() ?: return@mapNotNull null

                val chapterTitle = link.text().trim()
                if (chapterTitle.isBlank()) return@mapNotNull null

                MangaChapter(
                    id = generateUid(href),
                    title = chapterTitle,
                    url = href,
                    number = chapterNum,
                    volume = 0,
                    scanlator = uploader,
                    uploadDate = 0L,
                    branch = null,
                    source = source
                )
            }.reversed()

            manga.copy(
                title = title,
                description = description,
                tags = tags,
                chapters = chapters
            )
        } catch (e: Exception) {
            manga.copy(chapters = emptyList())
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        return try {
            val url = "https://$domain${chapter.url}"
            val doc = webClient.httpGet(url).parseHtml()

            doc.select("img[src*='cdn-guard.com/komikapk-chapter/']").mapNotNull { img ->
                val imageUrl = img.attr("src").takeIf { it.isNotEmpty() }
                    ?: img.attr("data-src").takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null

                if (!imageUrl.contains("komikapk-chapter") || imageUrl.isBlank()) {
                    return@mapNotNull null
                }

                MangaPage(
                    id = generateUid(imageUrl),
                    url = imageUrl,
                    preview = null,
                    source = source
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchTags(): Set<MangaTag> {
        return try {
            val doc = webClient.httpGet("https://$domain/pustaka/semua/semua/terbaru/1").parseHtml()
            
            doc.select("a[href*='/pustaka/semua/'][href*='/terbaru/']").mapNotNull { link ->
                val genre = link.text().trim()
                val href = link.attr("href")
                val key = href.substringAfter("/pustaka/semua/")
                    .substringBefore("/terbaru")
                    .takeIf { it.isNotEmpty() }
                
                if (genre.isNotEmpty() && key != null) {
                    MangaTag(
                        key = key,
                        title = genre.capitalize(),
                        source = source
                    )
                } else null
            }.toSet()
        } catch (e: Exception) {
            getAllGenres()
        }
    }

    private fun getAllGenres(): Set<MangaTag> {
        val genreList = setOf(
            "3d", "action", "adventure", "anime", "comedy", "crime", "drama", "ecchi", "fantasy", 
            "film", "game", "horror", "historical", "isekai", "magic", "martial-arts", "mecha", 
            "medical", "military", "music", "mystery", "philosophical", "psychology", "psychological", 
            "reincarnation", "romance", "romantic", "sci-fi", "shoujo", "shounen", "seinen", "josei", 
            "slice-of-life", "sports", "supernatural", "thriller", "tragedy", "wuxia",
            "doujin", "doujinshi", "manga", "manhua", "manhwa", "webtoon", "parody", "indie",
            "action-adventure", "body-swap", "drama-romance", "game", "school-life", "school-uniform",
            "story-arc", "tankoubon",
            "shoujo-ai", "shounen-ai", "boys-love", "girls-love", "yaoi", "yuri",
            "elf", "demon", "demon-girl", "monster", "monster-girl", "monster-girls", "ghost", 
            "robot", "furry", "kemonomimi", "kemomimi", "cat-girl", "catgirl", "fox-girl", 
            "mouse-girl", "bunny-girl", "angel", "vampire", "succubus", "slime", "doll",
            "aunt", "cousin", "daughter", "family", "girlfriend", "friends", "mother", "niece", 
            "sister", "uncle", "wife", "housewife", "house-wife", "office-lady", "teacher", 
            "maid", "nurse", "nun", "princess", "bride", "prostitution",
            "bald", "short-hair", "light-hair", "curly-hair", "long-hair", "ponytail", "twintail", 
            "twintails", "pony-tail", "glasses", "eyepatch", "fangs", "horns", "tail", "hairy", 
            "dark-skin", "darkskin", "tanlines", "beauty-mark", "beautymark", "piercing",
            "big-breast", "big-breasts", "big-ass", "big-dick", "big-cock", "big-penis", 
            "small-breast", "small-breasts", "busty", "chubby", "muscle", "muscles", "petite", 
            "huge-breast", "huge-breasts", "huge-boobs", "bbw", "amputee",
            "apron", "bikini", "kimono", "lingerie", "lingeri", "pantyhose", "swimsuit", 
            "stocking", "stockings", "school-girl-uniform", "schoolgirl-outfit", "business-suit", 
            "bodysuit", "leotard", "garter-belt", "hotpants", "gym-outfit", "cosplay",
            "adult", "hentai", "smut", "uncensored", "uncensore", "uncencored", "creampie", 
            "anal", "anal-intercourse", "blowjob", "blow-job", "bondage", "bdsm", "domination", 
            "femdom", "handjob", "footjob", "paizuri", "rimjob", "facesitting", "cowgirl", 
            "mating-press", "nakadashi", "deepthroat", "gang-bang", "gangbang", "group-sex", 
            "threesome", "foursome", "orgy", "masturbation", "masturbasi", "fingering", 
            "cunnilingus", "breast-feeding", "lactation", "squirting", "kissing", "licking", 
            "biting", "sweating", "tentacles", "impregnation", "impregnate", "pregnant", "pregnant",
            "feet", "foot-fetish", "guro", "scat", "inflation", "semen", "bukkake", "ejaculation", 
            "bukkake", "creampie", "defloration", "defloration", "double-penetration", "fisting", 
            "rough-sex", "harsh", "mindbreak", "corruption", "enslavement", "slavery", "slave",
            "bisexual", "homosexual", "lesbian", "lesbi", "gay",
            "tsundere", "kuudere", "yandere", "deredere", "osananajimi", "ojousama", "gyaru",
            "bondage", "chains", "collar", "leash", "whip", "spanking", "slapping", "choking", 
            "gagging", "blindfold", "blinfold",
            "beach", "bathroom", "hot-spring", "hotspring", "love-hotel", "hotel", "school", 
            "highschool", "school-life", "outdoors", "public", "hidden-sex",
            "loli", "lolicon", "lolipai", "shota", "shotacon",
            "affair", "cheating", "netorare", "netorare", "netorase", "netori", "ntr", "voyeurism", 
"exhibitionism", "blackmail", "rape", "forced", "non-consent", "drugs", "drunk", "sleep", 
            "sleeping", "hypnosis", "hipnotis", "mind-control", "mind-break", "magic", "invisible",
            "full-color", "full-colour", "uncensored", "rough-translation", "sub-indo"
        )
        
        return genreList.map { key ->
            MangaTag(
                key = key,
                title = key.replace("-", " ").capitalize(),
                source = source
            )
        }.toSet()
    }

    private fun isAdultContent(): Boolean {
        return true
    }
}
