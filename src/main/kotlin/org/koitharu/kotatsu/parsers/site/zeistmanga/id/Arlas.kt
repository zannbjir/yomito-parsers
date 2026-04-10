package org.koitharu.kotatsu.parsers.site.zeistmanga.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.zeistmanga.ZeistMangaParser

@MangaSourceParser("ARLAS", "Arlas", "id")
internal class Lepoytl(context: MangaLoaderContext) :
	ZeistMangaParser(context, MangaParserSource.ARLAS, "https://www.arlas.my.id")
