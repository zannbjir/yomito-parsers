package org.koitharu.kotatsu.parsers.site.tr

import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.exception.ParseException
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("ELDERMANGA", "Elder Manga", "tr")
internal class ElderManga(context: MangaLoaderContext):
    PagedMangaParser(context, MangaParserSource.ELDERMANGA, 25) {

    override val configKeyDomain = ConfigKey.Domain("eldermanga.com")
    private val cdnSuffix = "https://eldermangacdn2.efsaneler.can.re/"

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC,
        SortOrder.NEWEST,
        SortOrder.POPULARITY,
		SortOrder.UPDATED,
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = true,
            isSearchWithFiltersSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchTags(),
        availableStates = EnumSet.of(MangaState.ONGOING, MangaState.FINISHED, MangaState.ABANDONED, MangaState.PAUSED),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
            ContentType.COMICS,
        ),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val url = buildString {
            append("https://")
			append(domain)
            append("/search")
            append("?page=")
			append(page.toString())

            if (!filter.query.isNullOrEmpty()) {
                append("&search=")
                append(filter.query.urlEncoded())
            }

            if (filter.tags.isNotEmpty()) {
                append("&categories=")
				filter.tags.joinTo(this, ",") { it.key }
			}

            if (filter.states.isNotEmpty()) {
                append("&publicStatus=")
				filter.states.oneOrThrowIfMany()?.let {
					append(
						when (it) {
							MangaState.ONGOING -> "1"
							MangaState.FINISHED -> "2"
                            MangaState.ABANDONED -> "3"
                            MangaState.PAUSED -> "4"
							else -> ""
						},
					)
				}
			}

            if (filter.types.isNotEmpty()) {
                append("&country=")
				filter.types.oneOrThrowIfMany()?.let {
					append(
						when (it) {
							ContentType.MANHUA -> "1"
							ContentType.MANHWA -> "2"
							ContentType.MANGA -> "3"
							ContentType.COMICS -> "4"
							else -> ""
						},
					)
				}
			}

            append("&order=")
            append(
                when (order) {
                    SortOrder.ALPHABETICAL -> "1"
                    SortOrder.ALPHABETICAL_DESC -> "2"
                    SortOrder.NEWEST -> "3"
                    SortOrder.POPULARITY -> "4"
					SortOrder.UPDATED -> "5"
                    else -> "1"
                }
            )
        }

        val doc = loadSiteDocument(url)
        return doc.select("section[aria-label='series area'] .card").map { card ->
            val href = card.selectFirstOrThrow("a").attrAsRelativeUrl("href")
            Manga(
                id = generateUid(href),
                title = card.selectFirst("h2")?.text().orEmpty(),
                altTitles = emptySet(),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = card.selectFirst("img")?.attrAsAbsoluteUrlOrNull("src"),
                tags = emptySet(),
                state = null,
                authors = emptySet(),
                source = source,
            )
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = loadSiteDocument(manga.url.toAbsoluteUrl(domain))
        val statusText = doc.selectFirst("span:contains(Durum) + span")?.text().orEmpty()
        return manga.copy(
            tags = doc.select("a[href^='search?categories']").mapToSet {
                val key = it.attr("href").substringAfter("?categories=")
                MangaTag(
                    key = key,
                    title = it.text(),
                    source = source,
                )
            },
            description = doc.selectFirst("div.grid h2 + p")?.text(),
            state = when (statusText) {
                "Devam Ediyor" -> MangaState.ONGOING
                "Birakildi" -> MangaState.ONGOING
                "Tamamlandi" -> MangaState.FINISHED
                else -> null
            },
            chapters = doc.select("div.list-episode a").mapChapters(reversed = true) { i, el ->
                val href = el.attrAsRelativeUrl("href")
                val dateFormat = SimpleDateFormat("MMM d ,yyyy", Locale("tr"))
                MangaChapter(
                    id = generateUid(href),
                    title = el.selectFirstOrThrow("h3").text(),
                    number = (i + 1).toFloat(),
                    volume = 0,
                    url = href,
                    scanlator = null,
                    uploadDate = el.selectFirst("span")?.text()?.let { dateFormat.parseSafe(it) } ?: 0L,
                    branch = null,
                    source = source,
                )
            },
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = loadSiteDocument(chapter.url.toAbsoluteUrl(domain))
        val pageRegex = Regex("\\\\\"path\\\\\":\\\\\"([^\"]+)\\\\\"")
        val script = doc.select("script").find { it.html().contains(pageRegex) }?.html() ?: return emptyList()
        return pageRegex.findAll(script).mapNotNull { result ->
            result.groups[1]?.value?.let { url ->
                MangaPage(
                    id = generateUid(url),
                    url = "https://$cdnSuffix/upload/series/$url",
                    preview = null,
                    source = source,
                )
            }
        }.toList()
    }

    private suspend fun fetchTags(): Set<MangaTag> {
        val doc = loadSiteDocument("https://$domain/search")
        val script = doc.select("script").find { it.html().contains("self.__next_f.push([1,\"10:[\\\"\\$,\\\"section") }?.html()
            ?: return emptySet()

        val jsonStr = script.substringAfter("\"category\":[")
            .substringBefore("],\"searchParams\":{}")
            .replace("\\", "")

        val jsonArray = JSONArray("[$jsonStr]")
        return jsonArray.mapJSONToSet { jo ->
            MangaTag(
                key = jo.getString("id"),
                title = jo.getString("name"),
                source = source
            )
        }
    }

    private suspend fun loadSiteDocument(url: String): Document {
        when (val result = tryHttpDocument(url)) {
            is HttpDocumentResult.Success -> return result.document
            HttpDocumentResult.CloudflareChallenge -> context.requestBrowserAction(this, url)
            HttpDocumentResult.SecondaryVerification,
            HttpDocumentResult.Failed,
            null -> Unit
        }
        return loadDocumentViaWebView(url)
            ?: throw ParseException("Failed to load page via automatic verification webview", url)
    }

    private suspend fun tryHttpDocument(url: String): HttpDocumentResult? {
        val response = runCatching { webClient.httpGet(url) }.getOrNull() ?: return null
        return response.use { res ->
            val doc = runCatching { res.parseHtml() }.getOrNull() ?: return HttpDocumentResult.Failed
            if (hasValidElderContent(doc)) {
                return HttpDocumentResult.Success(doc)
            }
            if (isCloudflareChallengePage(doc)) {
                return HttpDocumentResult.CloudflareChallenge
            }
            if (isShieldVerificationPage(doc)) {
                return HttpDocumentResult.SecondaryVerification
            }
            HttpDocumentResult.Success(doc)
        }
    }

    private suspend fun loadDocumentViaWebView(url: String): Document? {
        val html = context.evaluateJs(url, VERIFICATION_WAIT_SCRIPT, 20000L)
            ?.let(::decodeWebViewHtml)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val doc = Jsoup.parse(html, url)
        if (hasValidElderContent(doc)) {
            return doc
        }
        if (isShieldVerificationPage(doc)) {
            return null
        }
        return doc
    }

    private fun decodeWebViewHtml(raw: String): String {
        val unquoted = if (raw.startsWith("\"") && raw.endsWith("\"")) {
            raw.substring(1, raw.length - 1)
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
        } else {
            raw
        }
        return unquoted.replace(Regex("""\\u([0-9A-Fa-f]{4})""")) { match ->
            match.groupValues[1].toInt(16).toChar().toString()
        }
    }

    private fun isShieldVerificationPage(doc: Document): Boolean {
        val html = doc.outerHtml().lowercase(Locale.ROOT)
        return doc.selectFirst("#container.verified") != null ||
            html.contains("verification complete") ||
            html.contains("protected by waf security shield") ||
            html.contains("access granted!") ||
            html.contains("computing challenge") ||
            html.contains("solving proof of work")
    }

    private fun isCloudflareChallengePage(doc: Document): Boolean {
        if (doc.selectFirst("script[src*=challenge-platform]") != null) return true
        if (doc.getElementById("challenge-error-title") != null) return true
        if (doc.getElementById("challenge-error-text") != null) return true
        if (doc.selectFirst("form[action*=__cf_chl]") != null) return true
        return isCloudflareChallengePage(doc.outerHtml())
    }

    private fun isCloudflareChallengePage(html: String): Boolean {
        val lower = html.lowercase(Locale.ROOT)
        return (lower.contains("just a moment") && lower.contains("cloudflare")) ||
            (lower.contains("checking your browser") && lower.contains("cloudflare")) ||
            lower.contains("cf-chl-opt") ||
            lower.contains("cf-browser-verification") ||
            lower.contains("/cdn-cgi/challenge-platform/")
    }

    private fun hasValidElderContent(doc: Document): Boolean {
        return doc.select("section[aria-label='series area'] .card").isNotEmpty() ||
            doc.select("div.list-episode a").isNotEmpty() ||
            doc.select("a[href*='search?categories=']").isNotEmpty() ||
            doc.select("script").any { script ->
                val body = script.html()
                body.contains("\\\"path\\\":\\\"") || body.contains("\"path\"")
            }
    }

    private companion object {
        private sealed interface HttpDocumentResult {
            data class Success(val document: Document) : HttpDocumentResult
            data object CloudflareChallenge : HttpDocumentResult
            data object SecondaryVerification : HttpDocumentResult
            data object Failed : HttpDocumentResult
        }

        private val VERIFICATION_WAIT_SCRIPT = """
            (() => {
                const hasElderContent = () => {
                    if (!document.documentElement) {
                        return false;
                    }
                    return document.querySelector('section[aria-label="series area"] .card') !== null ||
                        document.querySelector('div.list-episode a') !== null ||
                        document.querySelector('a[href*="search?categories="]') !== null ||
                        Array.from(document.scripts).some(script => {
                            const text = script.textContent || '';
                            return text.includes('\\"path\\":\\"') || text.includes('"path"');
                        });
                };

                return new Promise(resolve => {
                    let observer = null;

                    const isVerificationPage = () => {
                        const html = (document.documentElement ? document.documentElement.outerHTML : '').toLowerCase();
                        return document.querySelector('#container.verified') !== null ||
                            html.includes('verification complete') ||
                            html.includes('protected by waf security shield') ||
                            html.includes('access granted!') ||
                            html.includes('computing challenge') ||
                            html.includes('solving proof of work');
                    };

                    const finish = () => {
                        if (observer) {
                            observer.disconnect();
                        }
                        resolve(document.documentElement ? document.documentElement.outerHTML : '');
                    };

                    const waitForContent = start => {
                        if ((hasElderContent() && !isVerificationPage()) || Date.now() - start > 4000) {
                            finish();
                        } else {
                            setTimeout(() => waitForContent(start), 200);
                        }
                    };

                    const startWaiting = () => waitForContent(Date.now());

                    if (document.readyState === 'complete') {
                        startWaiting();
                    } else {
                        window.addEventListener('load', startWaiting, { once: true });
                        setTimeout(startWaiting, 2500);
                    }

                    observer = new MutationObserver(() => {
                        if (hasElderContent() && !isVerificationPage()) {
                            finish();
                        }
                    });
                    if (document.documentElement) {
                        observer.observe(document.documentElement, { childList: true, subtree: true, attributes: true });
                    }

                    setTimeout(() => {
                        finish();
                    }, 20000);
                });
            })();
        """.trimIndent()
    }
}
