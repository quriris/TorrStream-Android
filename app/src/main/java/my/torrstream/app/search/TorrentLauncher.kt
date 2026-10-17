package my.torrstream.app.search

import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import my.torrstream.app.player.InternalPlayerActivity
import my.torrstream.app.torrserver.TorrServerManager
import java.io.IOException
import java.util.Locale

/**
 * Раздача → встроенный TorrServer → встроенный плеер. Показывает ход дела в диалоге,
 * а для раздачи из нескольких видео (сериал) — выбор серии; остальные серии уходят
 * в плейлист плеера, чтобы переключались сами.
 */
class TorrentLauncher(private val activity: AppCompatActivity) {

    private var job: Job? = null

    fun play(title: String, magnet: String?, torrentUrl: String?) {
        job?.cancel()
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage("Запускаю TorrServer…")
            .setNegativeButton(android.R.string.cancel) { _, _ -> job?.cancel() }
            .setCancelable(true)
            .create()
        dialog.setOnCancelListener { job?.cancel() }
        dialog.show()

        job = activity.lifecycleScope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    fun status(text: String) {
                        activity.runOnUiThread { if (dialog.isShowing) dialog.setMessage(text) }
                    }
                    startServer(::status)
                    status("Добавляю раздачу…")
                    val hash = when {
                        magnet != null -> TorrServerApi.addLink(magnet, title)
                        torrentUrl != null -> {
                            val (bytes, _, _) = SiteParser(activity).download(torrentUrl)
                            // .torrent — bencode-словарь, начинается с «d». HTML вместо него —
                            // сайт требует входа: войдите во встроенном браузере.
                            if (bytes.isEmpty() || bytes[0] != 'd'.code.toByte()) {
                                throw IOException(
                                    "Сайт не отдал .torrent-файл. Возможно, нужно войти на сайт " +
                                        "во встроенном браузере (Сайты → Открыть)."
                                )
                            }
                            TorrServerApi.addTorrentFile(bytes, title)
                        }
                        else -> throw IOException("У раздачи нет ссылки")
                    }
                    var list: List<TorrentFile> = emptyList()
                    for (i in 1..120) {
                        ensureActive()
                        list = TorrServerApi.files(hash)
                        if (list.isNotEmpty()) break
                        status("Получаю список файлов… ${i} с\n\nЕсли долго — у раздачи мало сидов.")
                        delay(1000)
                    }
                    if (list.isEmpty()) throw IOException("Не удалось получить список файлов: нет сидов?")
                    hash to list
                }
                dialog.dismiss()
                choose(title, files.first, files.second)
            } catch (e: kotlinx.coroutines.CancellationException) {
                dialog.dismiss()
            } catch (e: Exception) {
                if (dialog.isShowing) {
                    dialog.setMessage("Ошибка: ${e.message ?: e.javaClass.simpleName}")
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setText(android.R.string.ok)
                }
            }
        }
    }

    private suspend fun startServer(status: (String) -> Unit) {
        if (!TorrServerManager.isInstalled) {
            // Сборка без вшитого TorrServer: скачиваем официальный, как раньше
            TorrServerManager.installAsync(startAfter = true)
            delay(300)
            while (TorrServerManager.downloading) {
                status("Скачиваю TorrServer… ${TorrServerManager.progress}%")
                delay(500)
            }
            TorrServerManager.error?.let { throw IOException("TorrServer: $it") }
        }
        if (!TorrServerManager.ensureRunningBlocking()) {
            throw IOException("TorrServer не запустился. Попробуйте ещё раз или перезапустите приложение.")
        }
    }

    private fun choose(title: String, hash: String, all: List<TorrentFile>) {
        val videos = all.filter { it.isVideo }.ifEmpty { all }.sortedWith(NATURAL)
        if (videos.size == 1) {
            start(title, hash, videos, 0)
            return
        }
        val labels = videos.map { "${it.name}  ·  ${formatSize(it.length)}" }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("Что смотреть?")
            .setItems(labels) { _, which -> start(title, hash, videos, which) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun start(title: String, hash: String, files: List<TorrentFile>, index: Int) {
        val urls = files.map { TorrServerApi.streamUrl(hash, it) }
        val intent = Intent(activity, InternalPlayerActivity::class.java).apply {
            setDataAndType(urls[index].toUri(), "video/*")
            putExtra(InternalPlayerActivity.Extras.TITLE, if (files.size == 1) title else files[index].name)
            if (files.size > 1) {
                putExtra(InternalPlayerActivity.Extras.PLAYLIST_URLS, urls.toTypedArray())
                putExtra(InternalPlayerActivity.Extras.PLAYLIST_TITLES, files.map { it.name }.toTypedArray())
                putExtra(InternalPlayerActivity.Extras.PLAYLIST_INDEX, index)
            }
        }
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(activity, "Не удалось открыть плеер: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        fun formatSize(bytes: Long): String = when {
            bytes <= 0 -> "?"
            bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.2f ГБ", bytes / (1L shl 30).toDouble())
            bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.0f МБ", bytes / (1L shl 20).toDouble())
            else -> String.format(Locale.ROOT, "%.0f КБ", bytes / 1024.0)
        }

        /** «Серия 2» раньше «Серия 10» */
        private val NATURAL = Comparator<TorrentFile> { a, b -> naturalCompare(a.path, b.path) }

        private fun naturalCompare(x: String, y: String): Int {
            val re = Regex("\\d+|\\D+")
            val ax = re.findAll(x.lowercase(Locale.ROOT)).map { it.value }.toList()
            val bx = re.findAll(y.lowercase(Locale.ROOT)).map { it.value }.toList()
            for (i in 0 until minOf(ax.size, bx.size)) {
                val p = ax[i]
                val q = bx[i]
                val c = if (p[0].isDigit() && q[0].isDigit()) {
                    val pn = p.trimStart('0')
                    val qn = q.trimStart('0')
                    if (pn.length != qn.length) pn.length - qn.length else pn.compareTo(qn)
                } else p.compareTo(q)
                if (c != 0) return c
            }
            return ax.size - bx.size
        }
    }
}
