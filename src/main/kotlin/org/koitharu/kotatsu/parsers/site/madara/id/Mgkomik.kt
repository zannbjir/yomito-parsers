package org.koitharu.kotatsu.parsers.site.madara.id

import okhttp3.Headers
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import java.util.*
import kotlin.random.Random

@MangaSourceParser("MGKOMIK", "MgKomik", "id")
internal class Mgkomik(context: MangaLoaderContext) :
    MadaraParser(context, MangaParserSource.MGKOMIK, "id.mgkomik.cc", 20) {

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val tagPrefix = "genres/"
    override val listUrl = "komik/"
    override val datePattern = "dd MMM yy"
    override val sourceLocale: Locale = Locale.ENGLISH

    // ================== HEADERS SUPER KUAT (V2) ==================
    override fun getRequestHeaders(): Headers = Headers.Builder()
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
        .add("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
        .add("Accept-Encoding", "gzip, deflate, br")
        .add("Referer", "https://id.mgkomik.cc/")
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "same-origin")
        .add("Sec-Fetch-User", "?1")
        .add("Upgrade-Insecure-Requests", "1")
        .add("Cache-Control", "max-age=0")
        .add("Connection", "keep-alive")
        .add("X-Requested-With", generateRandomString(Random.nextInt(18, 24)))
        .add("User-Agent", getRandomUserAgent())
        .build()

    private fun getRandomUserAgent(): String {
        val agents = listOf(
            "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 12; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        )
        return agents.random()
    }

    private fun generateRandomString(length: Int): String {
        val charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { charset.random() }.joinToString("")
    }
}
