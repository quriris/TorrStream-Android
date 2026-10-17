package my.torrstream.app.search

import android.net.Uri
import my.torrstream.app.torrserver.TorrServerManager
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Файл внутри раздачи */
data class TorrentFile(val id: Int, val path: String, val length: Long) {
    val name: String get() = path.substringAfterLast('/')
    val isVideo: Boolean
        get() = path.substringAfterLast('.', "").lowercase() in VIDEO_EXT

    companion object {
        private val VIDEO_EXT = setOf(
            "mkv", "mp4", "avi", "m4v", "mov", "wmv", "ts", "m2ts", "mts", "webm", "mpg", "mpeg", "vob", "flv", "3gp"
        )
    }
}

/**
 * Встроенный TorrServer по его HTTP API (MatriX): добавить раздачу, дождаться списка
 * файлов, получить ссылку на поток для плеера.
 */
object TorrServerApi {
    private val base get() = "http://127.0.0.1:${TorrServerManager.PORT}"
    private val JSON = MediaType.parse("application/json; charset=utf-8")
    private val TORRENT = MediaType.parse("application/x-bittorrent")

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun post(path: String, body: RequestBody): JSONObject {
        val req = Request.Builder().url(base + path).post(body).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body()?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("TorrServer: HTTP ${resp.code()} ${text.take(200)}")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        }
    }

    private fun torrents(payload: JSONObject): JSONObject =
        post("/torrents", RequestBody.create(JSON, payload.toString()))

    /** Добавить по magnet / хешу / http-ссылке. Возвращает хеш раздачи. */
    fun addLink(link: String, title: String): String {
        val r = torrents(
            JSONObject().put("action", "add").put("link", link)
                .put("title", title).put("save_to_db", true)
        )
        return r.optString("hash").ifEmpty { throw IOException("TorrServer не вернул хеш раздачи") }
    }

    /** Добавить .torrent-файл (скачанный приложением с куками сайта) */
    fun addTorrentFile(bytes: ByteArray, title: String): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "file.torrent", RequestBody.create(TORRENT, bytes))
            .addFormDataPart("title", title)
            .addFormDataPart("save", "true")
            .build()
        val r = post("/torrent/upload", body)
        return r.optString("hash").ifEmpty { throw IOException("TorrServer не принял .torrent-файл") }
    }

    /** Список файлов; пустой — метаданные раздачи ещё не получены */
    fun files(hash: String): List<TorrentFile> {
        val r = torrents(JSONObject().put("action", "get").put("hash", hash))
        val arr = r.optJSONArray("file_stats") ?: return emptyList()
        return List(arr.length()) {
            val f = arr.getJSONObject(it)
            TorrentFile(f.optInt("id"), f.optString("path"), f.optLong("length"))
        }
    }

    fun streamUrl(hash: String, file: TorrentFile): String =
        "$base/stream/${Uri.encode(file.name)}?link=$hash&index=${file.id}&play"
}
