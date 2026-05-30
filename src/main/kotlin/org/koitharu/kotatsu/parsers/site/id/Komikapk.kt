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

    private val cdnChapterUrl = "https://s1.cdn-guard.com/komikapk2-chapter/"
    private val cdnCoverUrl = "https://s1.cdn-guard.com/komikapk2-cover/"
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
            isMultipleTagsSupported = false,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = staticTags(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
            ContentType.HENTAI,
        ),
    )

    private fun staticTags(): Set<MangaTag> {
        val slugs = listOf(
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
        ).distinct()
        return slugs.map { slug ->
            MangaTag(
                key = slug,
                title = slug.replace("-", " ")
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() },
                source = source,
            )
        }.toSet()
    }

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildListUrl(page, filter, order)
        val raw = webClient.httpGet(url, getRequestHeaders()).parseRaw()
        val list = parseMangaListFromRaw(raw)
        if (list.isNotEmpty()) return list
        val doc = webClient.httpGet(url, getRequestHeaders()).parseHtml()
        return parseMangaListFromHtml(doc)
    }

    private fun buildListUrl(page: Int, filter: MangaListFilter, order: SortOrder): String {
        if (!filter.query.isNullOrEmpty()) {
            return "https://$domain/pencarian?q=${filter.query.urlEncoded()}&page=$page&include_adult=true"
        }
        val type = when (filter.types.firstOrNull()) {
            ContentType.MANGA -> "manga"
            ContentType.MANHWA -> "manhwa"
            ContentType.MANHUA -> "manhua"
            else -> "semua"
        }
        val tag = filter.tags.firstOrNull()?.key
            ?: if (filter.types.contains(ContentType.HENTAI)) "adult" else "semua"
        val sort = when (order) {
            SortOrder.POPULARITY -> "populer"
            else -> "terbaru"
        }
        return "https://$domain/pustaka/$type/$tag/$sort/$page?include_adult=true"
    }

    private fun parseMangaListFromRaw(raw: String): List<Manga> {
        val results = ArrayList<Manga>()
        val cardRegex = Regex(
            """\{[^{}]*?id:\d+[^{}]*?title:"([^"]+)"[^{}]*?slug:"([^"]+)"[^{}]*?coverUrl:"([^"]*)"[^{}]*?origin:"([^"]*)"[^{}]*?(?:sinopsis:"([^"]*)"[^{}]*?)?isAdult:(true|false)[^{}]*?\}""",
        )
        for (m in cardRegex.findAll(raw)) {
            val title = m.groupValues[1].unescapeJs()
            val slug = m.groupValues[2]
            val rawCover = m.groupValues[3]
            val origin = m.groupValues[4]
            val sinopsis = m.groupValues[5].unescapeJs()
            val isAdult = m.groupValues[6] == "true"
            val coverUrl = normalizeCover(rawCover, slug)
            val href = "/komik/$slug"
            results.add(
                Manga(
                    id = generateUid(slug),
                    title = title,
                    altTitles = emptySet(),
                    url = href,
                    publicUrl = "https://$domain$href",
                    rating = RATING_UNKNOWN,
                    contentRating = if (isAdult) ContentRating.ADULT else ContentRating.SAFE,
                    coverUrl = coverUrl,
                    largeCoverUrl = coverUrl,
                    tags = setOfNotNull(originTag(origin)),
                    state = null,
                    authors = emptySet(),
                    description = sinopsis.takeIf { it.isNotBlank() },
                    source = source,
                ),
            )
        }
        return results.distinctBy { it.id }
    }

    private fun originTag(origin: String): MangaTag? {
        val slug = origin.lowercase().takeIf { it.isNotBlank() } ?: return null
        return MangaTag(
            title = slug.replaceFirstChar { it.titlecase(Locale.getDefault()) },
            key = slug,
            source = source,
        )
    }

    private fun normalizeCover(raw: String, slug: String): String {
        if (raw.isBlank()) return "$cdnCoverUrl$slug.webp"
        return when {
            raw.startsWith("http") -> raw.replace(storageUrl, cdnChapterUrl)
            raw.startsWith("/") -> "https://$domain$raw"
            else -> "$cdnCoverUrl$raw"
        }
    }

    private fun parseMangaListFromHtml(doc: Document): List<Manga> {
        return doc.select("a[href^='/komik/']").mapNotNull { element ->
            val href = element.attr("href")
            val segments = href.split("/").filter { it.isNotBlank() }
            if (segments.size != 2) return@mapNotNull null
            val slug = segments[1]
            val coverImg = element.selectFirst("img[src*='cdn-guard'], img[src*='komikapk2-cover']")
            val coverUrl = coverImg?.src() ?: return@mapNotNull null
            val title = element.selectFirst("div.font-display")?.text()?.trim() ?: return@mapNotNull null
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
        val raw = webClient.httpGet(manga.publicUrl, getRequestHeaders()).parseRaw()
        val doc = org.jsoup.Jsoup.parse(raw, manga.publicUrl)

        val title = Regex("""comicDetail[^{]*\{[^}]*?title:"([^"]+)"""").find(raw)
            ?.groupValues?.get(1)?.unescapeJs()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: manga.title

        val sinopsis = Regex("""sinopsis:"((?:\\.|[^"\\])*)"""").find(raw)
            ?.groupValues?.get(1)?.unescapeJs()
            ?: ""

        val slug = manga.url.removePrefix("/komik/").trim('/')

        val coverRaw = Regex("""image:"((?:\\.|[^"\\])*)"""").find(raw)
            ?.groupValues?.get(1)?.unescapeJs()
        val cover = coverRaw?.let { normalizeCover(it, slug) } ?: manga.coverUrl

        val uploaderSlug = Regex("""uploaders:\[[^\]]*?slug:"([^"]+)"""").find(raw)
            ?.groupValues?.get(1)
            ?: "kmapk"

        val tags = LinkedHashSet<MangaTag>()
        val genreBlock = Regex("""genre:\[(.*?)\][,}]""").find(raw)?.groupValues?.get(1).orEmpty()
        Regex("""name:"([^"]+)"[^}]*?slug:"([^"]+)"""").findAll(genreBlock).forEach { gm ->
            tags.add(
                MangaTag(
                    title = gm.groupValues[1].unescapeJs(),
                    key = gm.groupValues[2],
                    source = source,
                ),
            )
        }

        val originMatch = Regex("""origin:"([^"]+)"""").find(raw)?.groupValues?.get(1)
        originMatch?.let { originTag(it) }?.let { tags.add(it) }

        val isAdult = Regex("""isAdult:(true|false)""").find(raw)?.groupValues?.get(1) == "true"
        val adultTagSlugs = setOf(
            "adult", "mature", "smut", "ecchi", "hentai", "nakadashi", "rape", "incest",
            "milf", "loli", "shota", "futanari", "gangbang", "creampie", "ntr", "netorare",
        )
        val contentRating = when {
            isAdult -> ContentRating.ADULT
            tags.any { it.key.lowercase() in adultTagSlugs } -> ContentRating.ADULT
            else -> ContentRating.SAFE
        }

        val statusRaw = Regex("""status:"([^"]+)"""").find(raw)?.groupValues?.get(1)?.lowercase()
        val state = when (statusRaw) {
            "completed", "tamat", "finished", "end" -> MangaState.FINISHED
            "ongoing", "berjalan", "publishing" -> MangaState.ONGOING
            "hiatus" -> MangaState.PAUSED
            else -> null
        }

        val chapters = parseChaptersFromRaw(raw, slug, uploaderSlug)

        return manga.copy(
            title = title,
            description = sinopsis.ifBlank { manga.description.orEmpty() },
            coverUrl = cover,
            largeCoverUrl = cover,
            tags = tags,
            state = state,
            contentRating = contentRating,
            chapters = chapters,
        )
    }

    private fun parseChaptersFromRaw(raw: String, slug: String, uploaderSlug: String): List<MangaChapter> {
        val seen = LinkedHashMap<String, MangaChapter>()

        val latestRegex = Regex("""latestChapter:\{[^{}]*?name:"([^"]+)"[^{}]*?chapterOrder:(\d+(?:\.\d+)?)[^{}]*?(?:createdAt:"([^"]+)")?""")
        latestRegex.find(raw)?.let { lm ->
            val name = lm.groupValues[1]
            val order = lm.groupValues[2].toFloatOrNull() ?: 0f
            val createdAt = lm.groupValues.getOrNull(3).orEmpty()
            addChapter(seen, slug, uploaderSlug, name, order, createdAt)
        }

        val nonImageBlock = Regex("""chaptersNonImage:\[(.*?)\][,}]""", RegexOption.DOT_MATCHES_ALL)
            .find(raw)?.groupValues?.get(1).orEmpty()
        val itemRegex = Regex("""\{[^{}]*?name:"([^"]+)"[^{}]*?chapterOrder:(\d+(?:\.\d+)?)[^{}]*?(?:createdAt:"([^"]+)")?[^{}]*?\}""")
        for (m in itemRegex.findAll(nonImageBlock)) {
            val name = m.groupValues[1]
            val order = m.groupValues[2].toFloatOrNull() ?: 0f
            val createdAt = m.groupValues.getOrNull(3).orEmpty()
            addChapter(seen, slug, uploaderSlug, name, order, createdAt)
        }

        return seen.values.sortedBy { it.number }
    }

    private fun addChapter(
        store: LinkedHashMap<String, MangaChapter>,
        slug: String,
        uploaderSlug: String,
        name: String,
        order: Float,
        createdAt: String,
    ) {
        val href = "/komik/$slug/$uploaderSlug/$name"
        if (store.containsKey(href)) return
        store[href] = MangaChapter(
            id = generateUid(href),
            title = "Chapter $name",
            url = href,
            number = order,
            volume = 0,
            scanlator = uploaderSlug,
            uploadDate = parseDate(createdAt),
            branch = null,
            source = source,
        )
    }

    private fun parseDate(raw: String): Long {
        if (raw.isBlank()) return 0L
        return try {
            java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli()
        } catch (_: Exception) {
            try {
                java.time.Instant.parse(raw).toEpochMilli()
            } catch (_: Exception) {
                0L
            }
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val chapterUrl = chapter.url.toAbsoluteUrl(domain)

        runCatching {
            val json = webClient.httpGet("$chapterUrl/__data.json", getRequestHeaders()).parseJson()
            val pages = parsePagesFromJson(json)
            if (pages.isNotEmpty()) return pages
        }

        val raw = webClient.httpGet(chapterUrl, getRequestHeaders()).parseRaw()
        val regexPages = Regex(""""(https://storage\.com/[^"\s]+?\.webp)"""")
            .findAll(raw)
            .map { it.groupValues[1].replace(storageUrl, cdnChapterUrl) }
            .toList()
            .distinct()
        if (regexPages.isNotEmpty()) {
            return regexPages.map { MangaPage(id = generateUid(it), url = it, preview = null, source = source) }
        }

        val cdnPages = Regex("""(https://s\d+\.cdn-guard\.com/komikapk2-chapter/[^"\s]+?\.webp)""")
            .findAll(raw)
            .map { it.groupValues[1] }
            .toList()
            .distinct()
        if (cdnPages.isNotEmpty()) {
            return cdnPages.map { MangaPage(id = generateUid(it), url = it, preview = null, source = source) }
        }

        val segments = chapter.url.split("/").filter { it.isNotBlank() }
        val comicSlug = segments.getOrNull(1) ?: return emptyList()
        val chapterName = segments.getOrNull(3) ?: return emptyList()
        val doc = org.jsoup.Jsoup.parse(raw, chapterUrl)
        val altImgs = doc.select("img[alt*='image-komik']")
        val total = altImgs.lastOrNull()?.attr("alt")?.substringAfterLast("/")?.toIntOrNull()
            ?: altImgs.size
        if (total <= 0) return emptyList()
        return (0 until total).map { i ->
            val url = "$cdnChapterUrl$comicSlug/chapter-$chapterName/image-${i.toString().padStart(4, '0')}.webp"
            MangaPage(id = generateUid(url), url = url, preview = null, source = source)
        }
    }

    private fun parsePagesFromJson(json: JSONObject): List<MangaPage> {
        val nodes = json.optJSONArray("nodes") ?: return emptyList()
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            if (node.optString("type") != "data") continue
            val data = node.optJSONArray("data") ?: continue
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
                val url = rawUrl.replace(storageUrl, cdnChapterUrl)
                pages.add(MangaPage(id = generateUid(url), url = url, preview = null, source = source))
            }
            if (pages.isNotEmpty()) return pages
        }
        return emptyList()
    }

    private fun String.unescapeJs(): String = this
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace("\\/", "/")
}
