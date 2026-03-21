package org.koitharu.kotatsu.parsers.site.mangareader.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser

@MangaSourceParser("GREEDSCANS", "Greed Scans", "en")
internal class GreedScans(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.GREEDSCANS, "greedscans.com", pageSize = 20, searchPageSize = 10) {

	override val detailsDescriptionSelector = "div.entry-content[itemprop=description], div.entry-content.entry-content-single"
}
