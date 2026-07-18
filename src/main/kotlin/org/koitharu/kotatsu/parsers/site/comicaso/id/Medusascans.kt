package org.koitharu.kotatsu.parsers.site.comicaso.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParserAuthProvider
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.comicaso.ComicasoParser
import org.koitharu.kotatsu.parsers.util.json.parseJson

@MangaSourceParser("MEDUSASCANS", "Medusascans", "id", ContentType.HENTAI)
internal class Medusascans(context: MangaLoaderContext) :
	ComicasoParser(context, MangaParserSource.MEDUSASCANS, "v3.comicaso.pro", pageSize = 20),
	MangaParserAuthProvider {

	override val apiSource: String = "medusa"

	/**
	 * Medusascans locks all manga detail / chapter endpoints for guests.
	 * `home.php` (listing) still works, so the app can render the catalog
	 * and prompt the user to sign in only when they open a title.
	 */
	override val loginRequired: Boolean = true

	override val authUrl: String
		get() = "https://$domain/?page=profile"

	override suspend fun isAuthorized(): Boolean {
		return try {
			val json = webClient.httpGet("https://$domain/api/me.php").parseJson()
			json.optBoolean("authenticated", false)
		} catch (_: Exception) {
			false
		}
	}

	override suspend fun getUsername(): String {
		val json = webClient.httpGet("https://$domain/api/me.php").parseJson()
		if (!json.optBoolean("authenticated", false)) {
			throw AuthRequiredException(source)
		}
		val data = json.optJSONObject("data") ?: throw AuthRequiredException(source)
		return data.optString("display_name").ifBlank {
			data.optString("email").ifBlank {
				throw AuthRequiredException(source)
			}
		}
	}
}
