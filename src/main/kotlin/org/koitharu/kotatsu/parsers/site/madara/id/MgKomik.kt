package org.koitharu.kotatsu.parsers.site.madara.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("MGKOMIK", "MgKomik", "id")
internal class MgKomik(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.MGKOMIK, "id.mgkomik.cc") {

	// id.mgkomik.cc is a Madara WordPress site (mangaSubString=komik). The previous
	// MangaReaderParser config was wrong and produced an empty list once Cloudflare
	// cleared; the Tachiyomi-side keiyoushi extension confirms it's a Madara site.
	override val listUrl = "komik/"
	override val tagPrefix = "komik-genre/"
	override val datePattern = "dd MMM yy"
}
