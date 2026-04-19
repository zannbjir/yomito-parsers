package org.koitharu.kotatsu.parsers.site.natsu.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.site.natsu.NatsuParser
import org.koitharu.kotatsu.parsers.util.toTitleCase


@MangaSourceParser("IKIRU", "Ikiru", "id")
internal class Ikiru(context: MangaLoaderContext) :
	NatsuParser(context, MangaParserSource.IKIRU, pageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain("02.ikiru.wtf")

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}


	override suspend fun fetchAvailableTags(): Set<MangaTag> {
		return try {
			val response = webClient.httpGet("https://$domain/wp-json/wp/v2/genre?per_page=100&page=1&orderby=count&order=desc")
			val rawBody = response.body.use { it.string() }

			val jsonStart = rawBody.indexOfFirst { it == '{' || it == '[' }
			val jsonText = if (jsonStart >= 0) rawBody.substring(jsonStart) else rawBody

			val jsonArray = org.json.JSONArray(jsonText)
			val tags = mutableSetOf<MangaTag>()
			for (i in 0 until jsonArray.length()) {
				val item = jsonArray.getJSONObject(i)
				val slug = item.optString("slug").takeIf { it.isNotBlank() } ?: continue
				val name = item.optString("name").takeIf { it.isNotBlank() } ?: continue
				tags += MangaTag(title = name.toTitleCase(), key = slug, source = source)
			}
			tags
		} catch (_: Exception) {
			super.fetchAvailableTags()
		}
	}
}
