package org.koitharu.kotatsu.parsers.site.id

import okhttp3.Headers
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.toJSONObjectOrNull
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.util.*

@MangaSourceParser("CGBUM", "CGBUM", "id", ContentType.HENTAI)
internal class CgBum(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.CGBUM, pageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain("cgbum.com")

	private companion object {
		// gambar reader disajikan dari domain registrasi yang BERBEDA: *.cgb.my.id
		val IMAGE_EXT = arrayOf(".webp", ".jpg", ".jpeg", ".png", ".gif", ".avif", ".bmp")
		const val MAX_BATCHES = 200
	}

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	// Fakta terukur (lihat lampiran B):
	//   - berkas reader: https://cdn1.cgb.my.id/s1/auto/<slug>/<ch>/<hash>/<file>.webp
	//   - CDN menjawab 200 bahkan TANPA Referer; cookie yang di-set untuk .cgbum.com
	//     tidak akan pernah dikirim ke *.cgb.my.id, jadi cgbum_reader_ts tidak relevan di sini.
	//   - https://cgbum.com/img.php tidak ada (404) -> jangan pernah menyusun URL dengannya.
	override fun getRequestHeaders(): Headers = Headers.Builder()
		.add("User-Agent", config[userAgentKey])
		.add("Referer", "https://$domain/")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.NEWEST_ASC,
	)

	override val filterCapabilities = MangaListFilterCapabilities(
		isMultipleTagsSupported = true,
		isSearchSupported = true,
		isSearchWithFiltersSupported = false,
	)

	private val availableTags = suspendLazy(initializer = ::fetchTags)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = availableTags.get(),
		availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED),
		availableContentTypes = EnumSet.of(
			ContentType.MANGA,
			ContentType.MANHWA,
			ContentType.MANHUA,
			ContentType.HENTAI,
		),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val query = filter.query
		val url = buildString {
			append("https://").append(domain)
			if (!query.isNullOrEmpty()) {
				// halaman search tidak menerima filter maupun sort
				append("/cari?q=").append(query.urlEncoded())
				append("&page=").append(page)
				return@buildString
			}
			append("/daftar-komik?page=").append(page)
			append("&sort=").append(
				when (order) {
					SortOrder.POPULARITY -> "views"
					SortOrder.NEWEST -> "newest"
					SortOrder.NEWEST_ASC -> "oldest"
					else -> "latest"
				},
			)
			filter.types.firstOrNull()?.let { type ->
				append("&type=").append(
					when (type) {
						ContentType.MANHWA -> "manhwa"
						ContentType.MANHUA -> "manhua"
						ContentType.HENTAI -> "pornhwa"
						else -> "manga"
					},
				)
			}
			filter.states.firstOrNull()?.let { state ->
				append("&status=").append(if (state == MangaState.FINISHED) "tamat" else "ongoing")
			}
			// situs meng-AND semua genre yang dipilih dan hanya memakai tiga pertama
			filter.tags.take(3).forEach { tag ->
				append("&genres%5B%5D=").append(tag.key.urlEncoded())
			}
		}
		val doc = webClient.httpGet(url).parseHtml()
		val cards = doc.select("article.comic-card, article")
			.mapNotNull { parseCard(it) }
			.distinctBy { it.id }
		if (cards.isNotEmpty()) return cards
		// cadangan bila kartu tidak memakai elemen <article>
		return doc.select("a[href*=\"/komik/\"]").mapNotNull { a ->
			val href = a.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val title = a.text().trim()
			if (title.length < 2) return@mapNotNull null
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				title = title,
				altTitles = emptySet(),
				coverUrl = null,
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				contentRating = null,
				source = source,
			)
		}.distinctBy { it.id }
	}

	private fun parseCard(card: Element): Manga? {
		val a = card.selectFirst(".comic-card-title a")
			?: card.selectFirst("h3 a")
			?: card.selectFirst("a.comic-card-cover")
			?: card.selectFirst("a[href*=\"/komik/\"]")
			?: return null
		val href = a.attrAsRelativeUrlOrNull("href") ?: return null
		val img = card.selectFirst("img")
		return Manga(
			id = generateUid(href),
			url = href,
			publicUrl = href.toAbsoluteUrl(domain),
			title = a.text().trim().ifEmpty { img?.attr("alt").orEmpty() },
			altTitles = emptySet(),
			coverUrl = img?.let { it.rawAttr("data-src") ?: it.rawAttr("src") }?.let { resolveHttp(it) },
			rating = RATING_UNKNOWN,
			tags = emptySet(),
			state = parseState(card.selectFirst(".badge-status")) ?: parseState(card.selectFirst("a > p")),
			authors = emptySet(),
			contentRating = if (card.isAdult()) ContentRating.ADULT else null,
			source = source,
		)
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		// jangan pernah memakai selectFirstOrThrow: satu class berganti, seluruh detail gagal
		val info = doc.selectFirst("article.comic-detail")
			?: doc.selectFirst("article")
			?: doc
		val ld = doc.select("script[type=\"application/ld+json\"]").firstNotNullOfOrNull { script ->
			script.data().toJSONObjectOrNull()?.takeIf { it.optString("@type") == "ComicSeries" }
		}
		val cover = info.selectFirst(".comic-cover") ?: info.selectFirst("div:has(img)")
		val coverImg = cover?.selectFirst("img") ?: info.selectFirst("img")
		val title = ld?.optString("name")?.takeIf { it.isNotBlank() }
			?: info.selectFirst("h1")?.text()?.removeSuffix(" Bahasa Indonesia")?.trim()?.takeIf { it.isNotEmpty() }
			?: doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
			?: manga.title
		val author = info.metaRow("Author")
		val tags = parseGenres(info)
		val description = (info.selectFirst(".synopsis-content") ?: doc.selectFirst(".synopsis-content"))
			?.html()
			?.replace(Regex("""\s*~\s*CGBUM\s*$"""), "")
		return manga.copy(
			title = title,
			altTitles = setOfNotNull(info.selectFirst(".comic-alt-title")?.text()?.trim()?.takeIf { it.isNotEmpty() }),
			coverUrl = coverImg?.let { it.rawAttr("data-src") ?: it.rawAttr("src") }?.let { resolveHttp(it) }
				?: manga.coverUrl,
			authors = author?.split(',')
				?.mapNotNullToSet { it.trim().takeIf { n -> n.isNotEmpty() && n != "-" } }
				.orEmpty(),
			tags = tags,
			state = parseState(cover?.selectFirst(".badge-status")) ?: parseState(cover) ?: manga.state,
			description = description,
			contentRating = if (cover?.isAdult() == true) ContentRating.ADULT else manga.contentRating,
			chapters = getChapters(doc),
		)
	}

	private suspend fun parseGenres(info: Element): Set<MangaTag> {
		val out = LinkedHashSet<MangaTag>()
		// samakan key-nya dengan key filter supaya search-by-tag cocok
		val known = runCatching { availableTags.get() }
			.getOrDefault(emptySet())
			.associateBy { it.title.lowercase(sourceLocale) }
		info.select(".comic-genres a.genre-pill, a[href*=\"/genre/\"]").forEach { a ->
			val name = a.text().trim()
			if (name.isEmpty()) return@forEach
			val hrefKey = a.attrOrNull("href")?.substringAfterLast('/')?.trim().orEmpty()
			val key = known[name.lowercase(sourceLocale)]?.key
				?: hrefKey.takeIf { it.isNotEmpty() }
				?: name
			out += MangaTag(title = name.toTitleCase(sourceLocale), key = key, source = source)
		}
		return out
	}

	private suspend fun getChapters(doc: Document): List<MangaChapter> {
		val list = doc.selectFirst(".chapter-list") ?: doc.selectFirst("#series-panel-chapters")
		val chapters = ArrayList<MangaChapter>()

		// urutan situs: terbaru di atas
		list?.select(".chapter-grid a.ch-grid-item, a.ch-grid-item")?.forEach { a ->
			val href = a.attrAsRelativeUrlOrNull("href") ?: return@forEach
			chapters += createChapter(
				url = href,
				chapter = a.attr("data-chapter").ifBlank { href.substringAfterLast('/') },
				sort = a.attr("data-chapter-sort").toDoubleOrNull(),
				label = a.text(),
			)
		}

		if (chapters.isEmpty()) {
			doc.select("a[href*=\"/chapter/\"]").forEach { a ->
				val href = a.attrAsRelativeUrlOrNull("href") ?: return@forEach
				val label = a.text().trim()
				if (label.isEmpty()) return@forEach
				chapters += createChapter(
					url = href,
					chapter = href.substringAfterLast('/'),
					sort = null,
					label = label,
				)
			}
		}

		// sisa chapter datang bertahap dari chapter-batch.php.
		// offset awal = jumlah yang benar-benar terparse (bukan atribut yang bisa basi),
		// terminator utama = has_more (bukan total yang bisa tertinggal satu).
		val total = (list?.attr("data-ch-total")?.toIntOrNull() ?: 0).coerceAtLeast(chapters.size)
		val slug = list?.attr("data-ch-slug").orEmpty()
		val endpoint = list?.attrAsAbsoluteUrlOrNull("data-ch-endpoint")
		var offset = chapters.size
		var hasMore = chapters.isEmpty() || offset < total
		var batches = 0
		while (hasMore && slug.isNotEmpty() && endpoint != null && batches++ < MAX_BATCHES) {
			val json = webClient
				.httpGet("$endpoint?slug=${slug.urlEncoded()}&offset=$offset&limit=100")
				.parseJson()
			val items = json.optJSONArray("items") ?: break
			if (items.length() == 0) break
			for (i in 0 until items.length()) {
				val item = items.optJSONObject(i) ?: continue
				chapters += parseBatchChapter(item, slug) ?: continue
			}
			val next = json.optInt("next_offset", offset + items.length())
			if (next <= offset) break
			offset = next
			hasMore = json.optBoolean("has_more", false)
		}
		return chapters.distinctBy { it.url }.reversed()
	}

	private fun parseBatchChapter(item: JSONObject, slug: String): MangaChapter? {
		val chapter = item.optString("chapter").trim()
		if (chapter.isEmpty()) return null
		val url = item.optString("url").takeIf { it.isNotBlank() }?.toRelativeUrl(domain)
			?: "/baca/$slug/chapter/$chapter"
		val title = item.optString("title").trim()
		return createChapter(
			url = url,
			chapter = chapter,
			sort = if (item.has("chapter_sort")) item.optDouble("chapter_sort") else null,
			label = item.optString("label").ifBlank {
				if (title.isEmpty()) "Ch. $chapter" else "Ch. $chapter - $title"
			},
		)
	}

	private fun createChapter(url: String, chapter: String, sort: Double?, label: String): MangaChapter {
		// sort dari situs sudah benar ("1053-5" -> 1053.005), jadi didahulukan
		val number = sort?.takeIf { !it.isNaN() }?.toFloat()
			?: Regex("""^(\d+)[-.](\d+)$""").find(chapter)
				?.let { "${it.groupValues[1]}.${it.groupValues[2]}" }?.toFloatOrNull()
			?: Regex("""\d+(?:\.\d+)?""").find(chapter)?.value?.toFloatOrNull()
			?: 0f
		return MangaChapter(
			id = generateUid(url),
			title = label.trim().ifEmpty { "Ch. $chapter" },
			number = number,
			volume = 0,
			url = url,
			scanlator = null,
			uploadDate = 0L,
			branch = null,
			source = source,
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val chapterUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(chapterUrl).parseHtml()

		// hanya dipakai bila situs menyerahkan path RELATIF; regexnya juga dilonggarkan
		// (kutip tunggal / tanpa spasi) supaya tidak diam-diam jatuh ke default yang salah
		val proxyBase = doc.select("script").firstNotNullOfOrNull { script ->
			Regex("""imageProxyBase\s*[:=]\s*["']([^"']+)["']""")
				.find(script.data())?.groupValues?.get(1)?.trim()
		}?.trimEnd('/')?.takeIf { it.startsWith("http") }

		val holder = doc.selectFirst("#readerImages") ?: doc

		// data-url / data-src menempel pada ELEMEN GAMBAR, bukan pada pembungkusnya.
		// src sengaja dikosongkan (lazy): membaca src() akan mengembalikan URL halaman
		// reader itu sendiri dan Kotatsu mencoba mendekode HTML sebagai gambar.
		val marked = holder.select("[data-url], [data-src]")
		val rawUrls: List<String> = if (marked.isNotEmpty()) {
			marked.mapNotNull { el -> el.rawAttr("data-url") ?: el.rawAttr("data-src") }
		} else {
			holder.select(".page-container, .reader-page, img").mapNotNull { el ->
				el.rawAttr("data-url")
					?: el.rawAttr("data-src")
					?: el.selectFirst("img")?.let { it.rawAttr("data-url") ?: it.rawAttr("data-src") }
					?: el.rawAttr("src")
			}
		}

		val strict = rawUrls.mapNotNull { resolveImageUrl(it, proxyBase, chapterUrl, requireExt = true) }
		// pembungkus dan <img> kadang sama-sama membawa data-url -> buang duplikat,
		// pertahankan urutan halaman
		val urls = strict.ifEmpty {
			rawUrls.mapNotNull { resolveImageUrl(it, proxyBase, chapterUrl, requireExt = false) }
		}.distinct()

		return urls.map { url ->
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	private fun resolveImageUrl(
		raw: String,
		proxyBase: String?,
		chapterUrl: String,
		requireExt: Boolean,
	): String? {
		val v = raw.trim()
		if (v.isEmpty() || v.startsWith("data:")) return null
		val base = proxyBase ?: "https://$domain"
		val abs = when {
			v.startsWith("https://") || v.startsWith("http://") -> v
			v.startsWith("//") -> "https:$v"
			v.startsWith("/") -> base + v
			else -> base + "/" + v.removePrefix("/")
		}
		if (abs == chapterUrl) return null
		if (requireExt) {
			val low = abs.lowercase()
			if (!IMAGE_EXT.any { low.contains(it) }) return null
		}
		return abs
	}

	private fun resolveHttp(raw: String): String? {
		val v = raw.trim()
		if (v.isEmpty() || v.startsWith("data:")) return null
		return when {
			v.startsWith("https://") || v.startsWith("http://") -> v
			v.startsWith("//") -> "https:$v"
			else -> "https://$domain" + if (v.startsWith("/")) v else "/$v"
		}
	}

	private suspend fun fetchTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/daftar-komik").parseHtml()
		// wadah live ber-id #genrePicker (bukan #genreList); nama parameternya genres[]
		var keys = doc.select("input[name='genres[]']")
			.mapNotNull { it.attrOrNull("value")?.trim()?.takeIf { v -> v.isNotEmpty() } }
		if (keys.isEmpty()) {
			keys = doc.select("#genrePicker label, #genreList label").mapNotNull { label ->
				(label.selectFirst("span") ?: label).text().trim().takeIf { t -> t.isNotEmpty() }
			}
		}
		// genre ditulis berulang dengan beda kapitalisasi ("Action" dan "action")
		return keys
			.distinctBy { it.lowercase(sourceLocale) }
			.mapTo(LinkedHashSet()) { name ->
				MangaTag(title = name.toTitleCase(sourceLocale), key = name, source = source)
			}
	}

	private fun Element.rawAttr(name: String): String? = attrOrNull(name)
		?.trim()
		?.takeIf { it.isNotEmpty() && !it.startsWith("data:") }

	private fun Element.isAdult(): Boolean =
		attr("data-adult") == "1" ||
			selectFirst(".badge-pornhwa") != null ||
			text().contains("18+")

	private fun Element.metaRow(label: String): String? {
		val rows = select(".comic-meta-simple .meta-row, .comic-meta .meta-row, article p")
		for (row in rows) {
			val key = row.selectFirst(".meta-label")?.text() ?: row.children().firstOrNull()?.text()
			if (key.equals(label, ignoreCase = true)) {
				val value = row.selectFirst(".meta-value")?.text() ?: row.children().getOrNull(1)?.text()
				value?.trim()?.takeIf { it.isNotEmpty() && it != "-" }?.let { return it }
			}
		}
		return null
	}

	private fun parseState(badge: Element?): MangaState? {
		badge ?: return null
		val text = badge.className() + " " + badge.text()
		return when {
			text.contains("completed", ignoreCase = true) || text.contains("tamat", ignoreCase = true) ->
				MangaState.FINISHED
			text.contains("ongoing", ignoreCase = true) || text.contains("berjalan", ignoreCase = true) ->
				MangaState.ONGOING
			text.contains("hiatus", ignoreCase = true) || text.contains("pause", ignoreCase = true) ->
				MangaState.PAUSED
			else -> null
		}
	}
}
