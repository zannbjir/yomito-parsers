package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("KOMIKAPK", "KomikApk", "id", ContentType.HENTAI)
internal class Komikapk(context: MangaLoaderContext) :
    PagedMangaParser(context, MangaParserSource.KOMIKAPK, 20) {

    override val configKeyDomain = ConfigKey.Domain("komikapk.app")

    private val cdnUrl = "https://s1.cdn-guard.com/komikapk2-chapter/"
    private val storageUrl = "https://storage.com/"

    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Referer", "https://$domain/")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
        .add("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
        .build()

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchTags(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
        ),
    )

    private fun fetchTags(): Set<MangaTag> {
        return setOf(
            "3d", "abusing", "actio", "action", "action-adventure", "adult", "adventure", "affair",
            "aheago", "ahegao", "ahego", "all-the-way-through", "amputee", "anal", "anal-intercourse",
            "anime", "anoria", "apron", "aunt", "bald", "bathroom", "bbew", "bbm", "bbw", "bdsm",
            "beach", "beauty-mark", "beautymark", "bebih", "bestiality", "big", "big-aas", "big-areolae",
            "big-ass", "big-big-breast", "big-blowjob", "big-breast", "bigbreast", "big-breasts",
            "big-breast-sole-male", "big-cock", "big-dick", "big-nipples", "big-penis", "bigpenis",
            "bike-shorts", "bikini", "bisexual", "biting", "blackmail", "blacmail", "blindfold",
            "blinfold", "bloomers", "blow-job", "blowjob", "blowjob-face", "bodysuit", "body-swap",
            "bog-ass", "bondage", "booty", "breast", "breast-feeding", "breasts", "bride", "brutal-sex",
            "bsdm", "bss", "bukake", "bukkake", "bunny-girl", "business-suit", "busty", "cat-girl",
            "catgirl", "cheating", "chubby", "collar", "color", "comedy", "condom", "confinement",
            "corruption", "cosplay", "cousin", "cow-girl", "cowgirl", "co-workers", "creampie", "crime",
            "crossdressing", "crypto", "cunnilingus", "curly-hair", "dark", "darkin", "dark-skin",
            "darkskin", "darkskj", "daughter", "deepthroat", "defloration", "deflorationg", "demon",
            "demon-girl", "demons", "dick-growth", "dilf", "domination", "double-penetration", "doujin",
            "doujinshi", "drama", "drama-romance", "drugs", "drunk", "ecchi", "elf", "emotionless-sex",
            "enakadashi", "exhibitionism", "exhibtionism", "eyebrows", "eyepatch", "eyepath", "face-mask",
            "facesitting", "facial", "family", "fangs", "fantasi", "fantasy", "fantasy-shounen", "female",
            "females", "females-only", "femdom", "fetish", "fffm-foursome", "ffm-threesome", "film",
            "filming", "fingering", "fivesome-plus", "fnakadashi", "footjob", "forced", "foreigner",
            "fox-girl", "friends", "full-color", "full-colour", "funny", "furry", "futanari", "futari",
            "futunari", "game", "gangbang", "garter-belt", "gater-belt", "gende-bender", "gender",
            "gender-bender", "gender-nakadashi", "genderswap", "ghost", "girlfriend", "girls-love",
            "glases", "glasses", "grop", "group", "group-humiliation", "grup", "guro", "gyaru", "hairy",
            "handjob", "hardcore", "hardsex", "harem", "heart-pupils", "hentai", "hidden-sex", "highschool",
            "hijab-3dx", "hijabitch", "hijabizah", "hijabolic", "hipnotis", "historical", "history",
            "horns", "horor", "horror", "hotpants", "hot-spring", "hotspring", "house-wife", "housewife",
            "huge-boobs", "huge-breast", "huge-breasts", "humiliation", "impregnant", "impregnate",
            "impregnatif", "impregnation", "inces", "incest", "indie", "inflation", "inseki",
            "inverted-nipple", "inverted-nipples", "invisible", "isekai", "jambu-madu", "josei",
            "kemomimi", "kemonomimi", "kimono", "kissing", "kogal", "komik", "komik-naruto",
            "komikus-fasik", "kuudere", "lactation", "leotard", "lesbi", "licking", "life", "light-hair",
            "lingeri", "lingerie", "loli", "lolicon", "lolipai", "lotion", "love-hotel", "m", "madloki",
            "magic", "magical-girl", "magical-girls", "maid", "malay", "males-only", "mama", "manga",
            "manhua", "manhwa", "mantap", "manwha", "martial-arts", "masked-face", "massage",
            "masturbasi", "masturbation", "mating-press", "mature", "mecha", "medical", "miko", "milf",
            "milftoon", "military", "mind", "mind-break", "mind-control", "mistery", "mmf-threesome",
            "mnakadashi", "mom-and-son", "money", "monster", "monster-girl", "monstergirl",
            "monster-girls", "monsters", "mother", "mouse-girl", "multi", "multiseries", "multi-work",
            "multiwork", "multi-work-series", "muscle", "muscles", "musde", "music", "mystery",
            "nakadashi", "nakdashi", "naruto", "natorare", "netoare", "netorare", "netorase", "netori",
            "netorre", "niece", "ntr", "nun", "nurse", "office-lady", "ojousama", "okkycreed", "old-man",
            "oldman", "onee-san", "one-piece", "orgy", "osananajimi", "other", "outdoors", "oyakodon",
            "paizuri", "pantyhose", "pantyhouse", "parody", "petite", "philosophical", "piercing",
            "pony-tail", "ponytail", "ponytails", "possession", "pregnan", "pregnant", "princess",
            "prostitution", "psychological", "pubic-hair", "rape", "reincarnation", "rimjob", "robot",
            "romance", "romantic", "rough-translation", "s", "scat", "school", "school-girl",
            "schoolgirl-outfit", "school-girl-uniform", "schoolife", "school-life", "school-sole-female",
            "school-uniform", "schooluniform", "sci-fi", "sein", "seinen", "sensual", "sex-toy",
            "sextoy", "sex-toys", "shemale", "short-hair", "shota", "shotacon", "shoujo", "shoujo-ai",
            "shounen", "shounen-ai", "shouse", "sister", "sixty-nine", "sjg", "slave", "sleeping",
            "slice-of-life", "slime", "small-beast", "small-breast", "smallbreast", "small-breasts",
            "smut", "snuff", "sole-female", "sole-male", "sole-uncensored", "spanking", "sports",
            "sportswear", "squirting", "ssole-female", "stepmom", "stocking", "stockings", "story-arc",
            "sub-indo", "succubus", "sucubus", "sumata", "superhero", "supernatural", "sweating",
            "swimsuit", "swimswit", "tail", "tall-girl", "tankoubon", "tanlines", "teacher", "tentacles",
            "threesome", "thriller", "tomboi", "tomboy", "tomgirl", "toys", "tragedi", "tragedy",
            "tsundere", "twin", "twins", "twintail", "twintails", "ugly-bastard", "uncen", "uncencored",
            "uncensore", "uncensored", "uncle", "unsensored", "unsual-pupils", "unusual",
            "unusual-pupils", "vani", "vanilla", "vir", "virg", "virgin", "virginity", "voyeurism",
            "vtuber", "webtoon", "widow", "wife", "wuxia", "x-ray", "yandere", "yaoi", "yuri"
        ).map { slug ->
            MangaTag(
                key = slug,
                title = slug.replace("-", " ")
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() },
                source = source
            )
        }.toSet()
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildListUrl(page, filter, order)
        val doc = webClient.httpGet(url).parseHtml()
        return parseMangaList(doc)
    }

    private fun buildListUrl(page: Int, filter: MangaListFilter, order: SortOrder): String {
        if (!filter.query.isNullOrEmpty()) {
            return "https://$domain/pencarian?q=${filter.query.urlEncoded()}&page=$page&is-adult=on"
        }

        val type = when (filter.types.firstOrNull()) {
            ContentType.MANGA -> "manga"
            ContentType.MANHWA -> "manhwa"
            ContentType.MANHUA -> "manhua"
            else -> "semua"
        }
        val tag = filter.tags.firstOrNull()?.key ?: "semua"
        val sort = when (order) {
            SortOrder.POPULARITY -> "populer"
            else -> "terbaru"
        }

        return "https://$domain/pustaka/$type/$tag/$sort/$page?include_adult=true"
    }

    private fun parseMangaList(doc: Document): List<Manga> {
        return doc.select("a[href^='/komik/']").mapNotNull { element ->
            val href = element.attr("href")
            val slug = href.removePrefix("/komik/").removeSuffix("/")

            val coverImg = element.selectFirst("img[src*='cdn-guard']")
            val coverUrl = coverImg?.src() ?: return@mapNotNull null

            val titleEl = element.selectFirst("div.font-display")
            val title = titleEl?.text()?.trim() ?: return@mapNotNull null

            Manga(
                id = generateUid(slug),
                title = title,
                altTitles = emptySet(),
                url = href,
                publicUrl = "https://$domain$href",
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = coverUrl,
                largeCoverUrl = coverUrl,
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }.distinctBy { it.id }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseHtml()
        val title = doc.selectFirst("h1.font-label")?.text()?.trim() ?: manga.title
        val cover = doc.selectFirst("img.h-\\[200px\\]")?.src() ?: manga.coverUrl
        val description = doc.selectFirst("div.font-display.mt-5.text-center")?.text()?.trim() ?: ""
        val tags = doc.select("a[href^='/pustaka/semua/'][href*='/terbaru/']").mapNotNull { a ->
            val tagSlug = a.attr("href").split("/").getOrNull(3) ?: return@mapNotNull null
            MangaTag(title = a.text().trim(), key = tagSlug, source = source)
        }.toSet()
        val state = if (doc.html().contains("completed", ignoreCase = true) ||
            doc.html().contains("tamat", ignoreCase = true))
            MangaState.FINISHED else MangaState.ONGOING
        val adultKeywords = listOf("adult", "mature", "smut", "ecchi", "hentai", "18+", "nakadashi", "rape", "incest", "milf", "loli", "shota", "futanari", "gangbang", "creampie", "ntr")
        val contentRating = if (tags.any { tag -> adultKeywords.any { it in tag.title.lowercase() } })
            ContentRating.ADULT else ContentRating.SAFE

        val chapters = doc.select("a[href^='/komik/']").mapNotNull { a ->
            val href = a.attr("href").trim()
            val segments = href.split("/").filter { it.isNotBlank() }
            if (segments.size < 4) return@mapNotNull null

            val uploaderSlug = segments[2]
            val titleText = a.text().trim()
            if (titleText.isBlank()) return@mapNotNull null

            val number = parseChapterNumber(titleText)
                ?: segments.lastOrNull()?.toFloatOrNull()
                ?: 0f

            MangaChapter(
                id = generateUid(href),
                title = if (titleText.isNotBlank()) titleText else "Chapter $number",
                url = href,
                number = number,
                volume = 0,
                scanlator = uploaderSlug,
                uploadDate = 0L,
                branch = null,
                source = source,
            )
        }.distinctBy { it.url }
            .sortedBy { it.number }

        return manga.copy(
            title = title,
            description = description,
            coverUrl = cover,
            largeCoverUrl = cover,
            tags = tags,
            state = state,
            contentRating = contentRating,
            chapters = chapters,
        )
    }

    private fun parseChapterNumber(name: String): Float? {
        val regex = Regex("""(?:chapter|ch\.?|bab)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        return regex.find(name)?.groupValues?.get(1)?.toFloatOrNull()
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterUrl = chapter.url.toAbsoluteUrl(domain)

        // Primary: use SvelteKit __data.json API for reliable structured data
        try {
            val dataUrl = "${chapterUrl}/__data.json"
            val json = webClient.httpGet(dataUrl).parseJson()
            val pages = parsePagesFromJson(json)
            if (pages.isNotEmpty()) return pages
        } catch (_: Exception) {
            // Fall through to HTML parsing
        }

        // Fallback: parse HTML
        val doc = webClient.httpGet(chapterUrl).parseHtml()
        return parsePagesFromHtml(doc, chapter)
    }

    private fun parsePagesFromJson(json: JSONObject): List<MangaPage> {
        val nodes = json.getJSONArray("nodes")
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            if (node.optString("type") != "data") continue
            val data = node.getJSONArray("data")
            val root = data.optJSONObject(0) ?: continue
            val chapterIdx = root.optInt("chapter", -1)
            if (chapterIdx < 0) continue
            val chapterObj = data.optJSONObject(chapterIdx) ?: continue
            val imagesIdx = chapterObj.optInt("images", -1)
            if (imagesIdx < 0) continue
            val imagesArray = data.optJSONArray(imagesIdx) ?: continue

            val pages = ArrayList<MangaPage>(imagesArray.length())
            for (j in 0 until imagesArray.length()) {
                val imgRef = imagesArray.optInt(j, -1)
                if (imgRef < 0) continue
                val rawUrl = data.optString(imgRef, null) ?: continue
                val url = rawUrl.replace(storageUrl, cdnUrl)
                pages.add(MangaPage(id = generateUid(url), url = url, preview = null, source = source))
            }
            if (pages.isNotEmpty()) return pages
        }
        return emptyList()
    }

    private fun parsePagesFromHtml(doc: Document, chapter: MangaChapter): List<MangaPage> {
        val imgElements = doc.select("section img, img[alt*='image-komik']")
        val realPages = imgElements.mapNotNull { img ->
            val src = img.src() ?: img.attr("data-src").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (src.contains("loading.gif") || src.isBlank()) return@mapNotNull null
            MangaPage(id = generateUid(src), url = src, preview = null, source = source)
        }
        if (realPages.isNotEmpty()) return realPages

        // Fallback: construct CDN URLs from image count
        val totalFromCount = imgElements.size
        if (totalFromCount > 0) {
            val segments = chapter.url.split("/").filter { it.isNotBlank() }
            val comicSlug = segments.getOrNull(1) ?: return emptyList()
            val chapterName = segments.getOrNull(3) ?: return emptyList()
            return (0 until totalFromCount).map { i ->
                val url = "${cdnUrl}$comicSlug/chapter-$chapterName/image-${i.toString().padStart(4, '0')}.webp"
                MangaPage(id = generateUid(url), url = url, preview = null, source = source)
            }
        }

        // Last resort: extract total from alt text (e.g. "...-32/33")
        val altLast = imgElements.lastOrNull()?.attr("alt")
        val totalFromAlt = altLast?.substringAfterLast("/")?.toIntOrNull()
        if (totalFromAlt != null && totalFromAlt > 0) {
            val segments = chapter.url.split("/").filter { it.isNotBlank() }
            val comicSlug = segments.getOrNull(1) ?: return emptyList()
            val chapterName = segments.getOrNull(3) ?: return emptyList()
            return (0 until totalFromAlt).map { i ->
                val url = "${cdnUrl}$comicSlug/chapter-$chapterName/image-${i.toString().padStart(4, '0')}.webp"
                MangaPage(id = generateUid(url), url = url, preview = null, source = source)
            }
        }

        return emptyList()
    }
}
