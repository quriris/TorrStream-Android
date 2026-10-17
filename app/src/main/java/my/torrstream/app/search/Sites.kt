package my.torrstream.app.search

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

/**
 * Сайт для поиска торрентов. [searchUrl] — адрес страницы с результатами, где вместо
 * искомого слова стоит {q}. [encoding] — в какой кодировке сайт ждёт запрос в адресе
 * (старые русские трекеры — windows-1251).
 */
data class Site(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val searchUrl: String,
    val encoding: String = "UTF-8",
    val enabled: Boolean = true
) {
    val host: String get() = Uri.parse(searchUrl.replace("{q}", "x")).host.orEmpty()

    /** Адрес главной страницы — для встроенного браузера */
    val homeUrl: String
        get() {
            val u = Uri.parse(searchUrl.replace("{q}", "x"))
            return "${u.scheme ?: "https"}://${u.host.orEmpty()}/"
        }

    fun urlFor(query: String): String =
        searchUrl.replace("{q}", URLEncoder.encode(query.trim(), encoding))

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("url", searchUrl)
        .put("enc", encoding).put("on", enabled)

    companion object {
        const val PLACEHOLDER = "{q}"

        fun fromJson(o: JSONObject) = Site(
            id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
            name = o.optString("name"),
            searchUrl = o.optString("url"),
            encoding = o.optString("enc").ifEmpty { "UTF-8" },
            enabled = o.optBoolean("on", true)
        )

        /**
         * Шаблон поиска из адреса страницы результатов и слова, которое на ней искали.
         * Слово ищем в адресе в разных записях: как есть, в UTF-8 и в windows-1251
         * (с «+» и «%20» вместо пробела). Возвращает шаблон и кодировку или null.
         */
        fun template(resultsUrl: String, searched: String): Pair<String, String>? {
            val url = resultsUrl.trim()
            if (url.contains(PLACEHOLDER)) return url to "UTF-8"
            val word = searched.trim()
            if (word.isEmpty()) return null
            for (enc in listOf("UTF-8", "windows-1251")) {
                val plus = try { URLEncoder.encode(word, enc) } catch (e: Exception) { continue }
                val variants = linkedSetOf(plus, plus.replace("+", "%20"), word, word.replace(" ", "+"))
                for (v in variants) {
                    val i = url.indexOf(v, ignoreCase = true)
                    if (i >= 0) {
                        return url.substring(0, i) + PLACEHOLDER + url.substring(i + v.length) to enc
                    }
                }
            }
            return null
        }
    }
}

/** Список сайтов пользователя — в SharedPreferences, JSON-массивом */
object SiteStore {
    private const val PREFS = "torrent_search"
    private const val KEY_SITES = "sites"
    private const val KEY_START = "start_screen"
    private const val KEY_LAST_QUERY = "last_query"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): MutableList<Site> {
        val raw = prefs(ctx).getString(KEY_SITES, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { Site.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(ctx: Context, sites: List<Site>) {
        val arr = JSONArray()
        sites.forEach { arr.put(it.toJson()) }
        prefs(ctx).edit { putString(KEY_SITES, arr.toString()) }
    }

    fun upsert(ctx: Context, site: Site) {
        val list = load(ctx)
        val i = list.indexOfFirst { it.id == site.id }
        if (i >= 0) list[i] = site else list.add(site)
        save(ctx, list)
    }

    fun remove(ctx: Context, id: String) = save(ctx, load(ctx).filterNot { it.id == id })

    /** true — при запуске сразу открывать каталог TorrStream, а не поиск */
    fun startWithCatalog(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_START, false)
    fun setStartWithCatalog(ctx: Context, value: Boolean) =
        prefs(ctx).edit { putBoolean(KEY_START, value) }

    fun lastQuery(ctx: Context): String = prefs(ctx).getString(KEY_LAST_QUERY, "").orEmpty()
    fun setLastQuery(ctx: Context, q: String) = prefs(ctx).edit { putString(KEY_LAST_QUERY, q) }
}
