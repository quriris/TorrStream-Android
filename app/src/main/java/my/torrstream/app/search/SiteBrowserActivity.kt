package my.torrstream.app.search

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import my.torrstream.app.R

/**
 * Обычный браузер по сайту с торрентами. Нажатие на magnet-ссылку или «скачать
 * .torrent» сразу запускает просмотр. Здесь же можно войти на сайт (куки потом
 * использует общий поиск) и сохранить страницу результатов как шаблон поиска.
 */
class SiteBrowserActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var titleView: TextView
    private lateinit var launcher: TorrentLauncher
    private var siteName: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcher = TorrentLauncher(this)
        siteName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val dp = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.lampa_background))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
        }
        fun button(text: String, onClick: () -> Unit) = MaterialButton(
            this, null, androidx.appcompat.R.attr.borderlessButtonStyle
        ).apply {
            this.text = text
            setTextColor(getColor(R.color.tv_white))
            setOnClickListener { onClick() }
        }
        bar.addView(button("←") { goBack() })
        titleView = TextView(this).apply {
            setTextColor(getColor(R.color.tv_white))
            textSize = 15f
            maxLines = 1
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
        }
        bar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(button("Сохранить как поиск") { saveAsSearch() })
        bar.addView(button("✕") { finish() })
        root.addView(bar)

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (3 * dp).toInt()))

        web = WebView(this)
        web.setBackgroundColor(Color.WHITE)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                intercept(request.url.toString())

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = intercept(url)

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                titleView.text = url
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                titleView.text = view.title?.takeIf { it.isNotBlank() } ?: url
                progress.visibility = View.INVISIBLE
                CookieManager.getInstance().flush()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
            }
        }
        // «Скачать .torrent» — файл забирает приложение (с куками сайта) и сразу играет
        web.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            if (mimeType == "application/x-bittorrent" || name.endsWith(".torrent", ignoreCase = true)) {
                launcher.play(currentTitle(), null, url)
            } else {
                Toast.makeText(this, "Это не торрент-файл: $name", Toast.LENGTH_SHORT).show()
            }
        }

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl(intent.getStringExtra(EXTRA_URL) ?: "about:blank")
    }

    private fun intercept(url: String): Boolean {
        if (url.startsWith("magnet:", ignoreCase = true)) {
            launcher.play(currentTitle(), url, null)
            return true
        }
        val path = Uri.parse(url).path.orEmpty()
        if (url.startsWith("http") && path.endsWith(".torrent", ignoreCase = true)) {
            launcher.play(currentTitle(), null, url)
            return true
        }
        if (!url.startsWith("http") && !url.startsWith("about:")) return true // tg://, intent:// и пр.
        return false
    }

    private fun currentTitle(): String =
        web.title?.takeIf { it.isNotBlank() && !it.startsWith("http") } ?: siteName.ifEmpty { "Раздача" }

    private fun saveAsSearch() {
        val url = web.url ?: return
        val dp = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
        }
        val word = EditText(this).apply {
            hint = "Что вы искали на этой странице"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val name = EditText(this).apply {
            hint = "Название сайта"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(siteName.ifEmpty { Uri.parse(url).host.orEmpty().removePrefix("www.") })
        }
        box.addView(word)
        box.addView(name)
        AlertDialog.Builder(this)
            .setTitle("Сохранить как поиск")
            .setMessage(
                "Сначала найдите на сайте любой фильм, чтобы открылась страница с результатами. " +
                    "Потом нажмите эту кнопку и напишите, что искали."
            )
            .setView(box)
            .setPositiveButton("Сохранить") { _, _ ->
                val tpl = Site.template(url, word.text.toString())
                if (tpl == null) {
                    AlertDialog.Builder(this)
                        .setTitle("Не получилось")
                        .setMessage(
                            "В адресе страницы нет слова «${word.text}». Либо это ещё не страница " +
                                "результатов, либо сайт ищет без смены адреса — такой сайт в общий " +
                                "поиск не добавить, но здесь, в браузере, он работает."
                        )
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else {
                    val siteTitle = name.text.toString().trim().ifEmpty { Uri.parse(url).host.orEmpty() }
                    val existing = SiteStore.load(this).firstOrNull { it.name == siteTitle }
                    SiteStore.upsert(
                        this,
                        (existing ?: Site(name = siteTitle, searchUrl = tpl.first))
                            .copy(name = siteTitle, searchUrl = tpl.first, encoding = tpl.second)
                    )
                    Toast.makeText(this, "Сайт «$siteTitle» добавлен в поиск", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun goBack() {
        if (web.canGoBack()) web.goBack() else finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_NAME = "name"

        fun newIntent(ctx: Context, url: String, siteName: String): Intent =
            Intent(ctx, SiteBrowserActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_NAME, siteName)
    }
}
