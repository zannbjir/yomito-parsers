package org.koitharu.kotatsu.parsers.site.natsu.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.natsu.NatsuParser


@MangaSourceParser("KIRYUU", "Kiryuu", "id")
internal class Kiryuu(context: MangaLoaderContext) :
	NatsuParser(context, MangaParserSource.KIRYUU, pageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain(
		"v5.kiryuu.to",
		"v4.kiryuu.to",
		"v3.kiryuu.to",
		"v2.kiryuu.to",
		"v1.kiryuu.to",
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(ConfigKey.DisableUpdateChecking(defaultValue = true))
	}
}
