package my.torrstream.app.search

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Одна найденная раздача */
data class TorrentResult(
    val title: String,
    val magnet: String?,          // magnet-ссылка, если нашлась
    val torrentUrl: String?,      // иначе — ссылка на .torrent (качаем с куками сайта)
    val size: String?,
    val sizeBytes: Long,
    val seeds: Int?,
    val siteName: String,
    val pageUrl: String?
) {
    val hash: String?
        get() = magnet?.let { Regex("xt=urn:btih:([^&]+)", RegexOption.IGNORE_CASE).find(it) }
            ?.groupValues?.get(1)?.lowercase(Locale.ROOT)
}

/**
 * Универсальный разбор страниц поиска: под конкретные сайты ничего не зашито.
 *
 * 1. Есть magnet-ссылки прямо в результатах — берём их; название, размер и сиды ищем
 *    в той же строке таблицы (или блоке), где лежит ссылка.
 * 2. Нет — берём ссылки на страницы раздач (текст ссылки содержит слово из запроса),
 *    открываем первые [MAX_DETAILS] и достаём magnet или .torrent оттуда.
 *
 * Запросы идут с куками и User-Agent встроенного браузера: вошли на сайт во встроенном
 * браузере (или прошли проверку Cloudflare) — поиск тоже видит сайт «залогиненным».
 */
class SiteParser(context: Context) {

    private val userAgent: String = try {
        WebSettings.getDefaultUserAgent(context)
    } catch (e: Exception) {
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun search(site: Site, query: String): List<TorrentResult> = withContext(Dispatchers.IO) {
        val doc = fetch(site.urlFor(query))
        val direct = fromMagnets(doc, site)
        if (direct.isNotEmpty()) return@withContext direct
        fromDetailPages(doc, site, query)
    }

    // ==================== ЗАГРУЗКА ====================

    fun fetch(url: String): Document {
        val (bytes, finalUrl, contentType) = download(url)
        val charset = detectCharset(bytes, contentType)
        return Jsoup.parse(String(bytes, charset), finalUrl)
    }

    /** Байты по адресу с куками встроенного браузера: тело, итоговый адрес, Content-Type */
    fun download(url: String): Triple<ByteArray, String, String?> {
        val builder = Request.Builder().url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
        cookiesFor(url)?.let { builder.header("Cookie", it) }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code()}")
            val body = resp.body() ?: throw IOException("пустой ответ")
            return Triple(body.bytes(), resp.request().url().toString(), resp.header("Content-Type"))
        }
    }

    private fun cookiesFor(url: String): String? = try {
        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    private fun detectCharset(bytes: ByteArray, contentType: String?): Charset {
        val fromHeader = contentType?.let { CHARSET_RE.find(it)?.groupValues?.get(1) }
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        val fromMeta = CHARSET_RE.find(head)?.groupValues?.get(1)
        for (name in listOfNotNull(fromHeader, fromMeta)) {
            try { return Charset.forName(name.trim('"', '\'', ' ')) } catch (_: Exception) { }
        }
        return Charsets.UTF_8
    }

    // ==================== РЕЖИМ 1: MAGNET В РЕЗУЛЬТАТАХ ====================

    private fun fromMagnets(doc: Document, site: Site): List<TorrentResult> {
        val links = doc.select("a[href^=magnet:]")
        if (links.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<TorrentResult>()
        for (a in links) {
            val magnet = a.attr("href")
            val hash = HASH_RE.find(magnet)?.groupValues?.get(1)?.lowercase(Locale.ROOT) ?: magnet
            if (!seen.add(hash)) continue
            val row = rowOf(a)
            val titleLink = bestTitleLink(row)
            val title = titleLink?.text()?.trim()?.takeIf { it.length >= 3 }
                ?: magnetName(magnet)
                ?: a.text().trim().ifEmpty { "Без названия" }
            val sizeMatch = SIZE_RE.find(row.text())
            out += TorrentResult(
                title = title,
                magnet = magnet,
                torrentUrl = null,
                size = sizeMatch?.value?.replace(' ', ' '),
                sizeBytes = sizeMatch?.let { parseSize(it) } ?: magnetSize(magnet),
                seeds = seedsIn(row),
                siteName = site.name,
                pageUrl = titleLink?.absUrl("href")?.takeIf { it.isNotEmpty() }
            )
        }
        return out
    }

    /**
     * Строка результата: поднимаемся от ссылки, пока в родителе нет других magnet.
     * Так получаем ячейку → строку таблицы (или карточку), но не всю таблицу.
     */
    private fun rowOf(a: Element): Element {
        var cur: Element = a
        repeat(8) {
            val parent = cur.parent() ?: return cur
            if (parent.tagName() == "body") return cur
            if (parent.select("a[href^=magnet:]").size > 1) return cur
            cur = parent
            if (cur.tagName() == "tr" || cur.tagName() == "li" || cur.tagName() == "article") return cur
        }
        return cur
    }

    /** Самая длинная обычная ссылка строки — почти всегда название раздачи */
    private fun bestTitleLink(row: Element): Element? =
        row.select("a[href]")
            .filter { a ->
                val href = a.attr("href")
                !href.startsWith("magnet:") && !href.startsWith("javascript") && !href.startsWith("#") &&
                    !href.contains(".torrent", ignoreCase = true)
            }
            .maxByOrNull { it.text().trim().length }

    private fun seedsIn(row: Element): Int? {
        val tagged = row.select(
            "[class*=seed], [class*=Seed], [class*=sid], [class*=green], [title*=Сид], [title*=сид], [title*=Seed]"
        )
        for (el in tagged) {
            el.text().trim().replace(Regex("\\D"), "").toIntOrNull()?.let { return it }
        }
        return null
    }

    // ==================== РЕЖИМ 2: СТРАНИЦЫ РАЗДАЧ ====================

    private suspend fun fromDetailPages(doc: Document, site: Site, query: String): List<TorrentResult> {
        val words = query.lowercase(Locale.ROOT).split(Regex("[\\s\\p{Punct}]+")).filter { it.length >= 3 }
            .ifEmpty { listOf(query.lowercase(Locale.ROOT).trim()) }
        val host = Uri.parse(doc.location()).host.orEmpty()
        val seen = HashSet<String>()
        val candidates = doc.select("a[href]").filter { a ->
            val text = a.text().trim()
            val url = a.absUrl("href")
            val lower = text.lowercase(Locale.ROOT)
            text.length >= 8 && url.startsWith("http") &&
                Uri.parse(url).host == host && url != doc.location() &&
                words.any { lower.contains(it) } && seen.add(url.substringBefore('#'))
        }.take(MAX_DETAILS)
        if (candidates.isEmpty()) return emptyList()

        val gate = Semaphore(4)
        return coroutineScope {
            candidates.map { a ->
                async(Dispatchers.IO) {
                    gate.withPermit { detail(a, site) }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private fun detail(link: Element, site: Site): TorrentResult? {
        val url = link.absUrl("href")
        val page = try { fetch(url) } catch (e: Exception) { return null }
        val magnet = page.selectFirst("a[href^=magnet:]")?.attr("href")
        val torrent = if (magnet == null) {
            page.select("a[href]").firstOrNull { a ->
                val h = a.attr("href").lowercase(Locale.ROOT)
                h.contains(".torrent") || h.contains("dl.php") || h.contains("download")
            }?.absUrl("href")?.takeIf { it.startsWith("http") }
        } else null
        if (magnet == null && torrent == null) return null
        // Размер — сначала в строке результатов, потом на странице раздачи
        val rowText = (link.parent()?.parent() ?: link).text()
        val sizeMatch = SIZE_RE.find(rowText) ?: SIZE_RE.find(page.body()?.text().orEmpty())
        return TorrentResult(
            title = link.text().trim(),
            magnet = magnet,
            torrentUrl = torrent,
            size = sizeMatch?.value?.replace(' ', ' '),
            sizeBytes = sizeMatch?.let { parseSize(it) } ?: magnet?.let { magnetSize(it) } ?: 0L,
            seeds = (link.parent()?.parent())?.let { seedsIn(it) },
            siteName = site.name,
            pageUrl = url
        )
    }

    // ==================== РАЗМЕР И ИМЯ ====================

    private fun parseSize(m: MatchResult): Long {
        val num = m.groupValues[1].replace(',', '.').replace(" ", "").toDoubleOrNull() ?: return 0
        val unit = m.groupValues[2].lowercase(Locale.ROOT)
        val mult = when {
            unit.startsWith("t") || unit.startsWith("т") -> 1L shl 40
            unit.startsWith("g") || unit.startsWith("г") -> 1L shl 30
            unit.startsWith("m") || unit.startsWith("м") -> 1L shl 20
            unit.startsWith("k") || unit.startsWith("к") -> 1L shl 10
            else -> 1L
        }
        return (num * mult).toLong()
    }

    private fun magnetName(magnet: String): String? =
        Regex("[?&]dn=([^&]+)").find(magnet)?.groupValues?.get(1)
            ?.let { try { Uri.decode(it.replace('+', ' ')) } catch (e: Exception) { it } }

    private fun magnetSize(magnet: String): Long =
        Regex("[?&]xl=(\\d+)").find(magnet)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    companion object {
        private const val MAX_DETAILS = 15
        private val CHARSET_RE = Regex("charset=([\\w\"'-]+)", RegexOption.IGNORE_CASE)
        private val HASH_RE = Regex("xt=urn:btih:([^&]+)", RegexOption.IGNORE_CASE)
        val SIZE_RE = Regex(
            "(\\d{1,4}(?:[.,]\\d{1,3})?)[\\s\\u00a0]*(TiB|GiB|MiB|KiB|TB|GB|MB|KB|ТБ|ГБ|МБ|КБ|Гб|Мб|Тб)(?![\\p{L}])",
            RegexOption.IGNORE_CASE
        )
    }
}
