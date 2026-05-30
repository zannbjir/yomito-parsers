package org.koitharu.kotatsu.parsers.site.madara.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import java.util.Locale

@MangaSourceParser("OMICASO", "Omicaso", "id")
internal class Omicaso(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.OMICASO, "omicaso.org", 10) {
	override val tagPrefix = "genre/"
	override val listUrl = "comik/"
	override val datePattern = "MMMM dd, yyyy"
	override val sourceLocale: Locale = Locale.ENGLISH
}
