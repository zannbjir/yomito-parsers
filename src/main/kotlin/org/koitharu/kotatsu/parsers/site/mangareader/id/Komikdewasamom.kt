package org.koitharu.kotatsu.parsers.site.mangareader.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser

@MangaSourceParser("KOMIKDEWASA_MOM", "komikdewasa.mom", "id", ContentType.HENTAI)
internal class komikdewasamom(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaParserSource.KOMIKDEWASA_MOM, "komikdewasa.mom", pageSize = 20, searchPageSize = 10) {
	override val datePattern = "MMMM d, yyyy"
}
