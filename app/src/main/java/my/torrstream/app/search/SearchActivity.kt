package my.torrstream.app.search

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import my.torrstream.app.MainActivity
import my.torrstream.app.R
import my.torrstream.app.torrserver.TorrServerManager

/**
 * Стартовый экран: одна строка поиска по всем сайтам пользователя. Результаты
 * появляются по мере ответа сайтов; нажатие на раздачу — просмотр во встроенном плеере.
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var empty: TextView
    private lateinit var sortButton: TextView
    private val adapter = ResultsAdapter()
    private lateinit var launcher: TorrentLauncher

    private var searchJob: Job? = null
    private val results = ArrayList<TorrentResult>()
    private var sortBySeeds = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Каталог TorrStream выбран стартовым экраном — сразу туда
        if (savedInstanceState == null && intent?.action == Intent.ACTION_MAIN &&
            SiteStore.startWithCatalog(this)
        ) {
            openCatalog()
        }
        setContentView(R.layout.activity_torrent_search)
        launcher = TorrentLauncher(this)
        TorrServerManager.autostartIfNeeded()

        input = findViewById(R.id.search_input)
        status = findViewById(R.id.search_status)
        progress = findViewById(R.id.search_progress)
        empty = findViewById(R.id.empty_text)
        sortButton = findViewById(R.id.btn_sort)

        val list = findViewById<RecyclerView>(R.id.results)
        val columns = if (resources.configuration.screenWidthDp >= 840) 2 else 1
        list.layoutManager = GridLayoutManager(this, columns)
        list.adapter = adapter

        input.setText(SiteStore.lastQuery(this))
        input.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                runSearch(); true
            } else false
        }
        findViewById<View>(R.id.btn_search).setOnClickListener { runSearch() }
        findViewById<View>(R.id.btn_sites).setOnClickListener {
            startActivity(Intent(this, SitesActivity::class.java))
        }
        findViewById<View>(R.id.btn_catalog).setOnClickListener { openCatalog() }
        findViewById<View>(R.id.btn_catalog).setOnLongClickListener { askStartScreen(); true }
        sortButton.setOnClickListener {
            sortBySeeds = !sortBySeeds
            showResults()
        }
        updateSortLabel()
        showEmptyHint()
        if (savedInstanceState == null) handleMagnet(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleMagnet(intent)
    }

    /** magnet-ссылка из браузера или другого приложения — сразу смотреть */
    private fun handleMagnet(intent: Intent?) {
        val link = intent?.data?.toString() ?: return
        if (intent.action != Intent.ACTION_VIEW || !link.startsWith("magnet:", ignoreCase = true)) return
        val name = Regex("[?&]dn=([^&]+)").find(link)?.groupValues?.get(1)
            ?.let { android.net.Uri.decode(it.replace('+', ' ')) } ?: "Раздача"
        launcher.play(name, link, null)
    }

    override fun onResume() {
        super.onResume()
        if (results.isEmpty() && searchJob?.isActive != true) showEmptyHint()
    }

    private fun openCatalog() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun askStartScreen() {
        val items = arrayOf("Поиск торрентов", "Каталог TorrStream")
        AlertDialog.Builder(this)
            .setTitle("Что открывать при запуске")
            .setSingleChoiceItems(items, if (SiteStore.startWithCatalog(this)) 1 else 0) { d, which ->
                SiteStore.setStartWithCatalog(this, which == 1)
                d.dismiss()
            }
            .show()
    }

    private fun showEmptyHint() {
        val sites = SiteStore.load(this).filter { it.enabled }
        empty.visibility = View.VISIBLE
        empty.text = if (sites.isEmpty()) {
            "Сайтов для поиска пока нет.\n\nНажмите «Сайты» и добавьте сайт с торрентами — " +
                "это займёт минуту.\n\nДолгое нажатие на «Каталог» — выбрать, что открывать при запуске."
        } else {
            "Поиск по сайтам: ${sites.joinToString { it.name }}"
        }
    }

    private fun runSearch() {
        val query = input.text.toString().trim()
        if (query.isEmpty()) return
        val sites = SiteStore.load(this).filter { it.enabled }
        if (sites.isEmpty()) {
            startActivity(Intent(this, SitesActivity::class.java))
            return
        }
        SiteStore.setLastQuery(this, query)
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(input.windowToken, 0)

        searchJob?.cancel()
        results.clear()
        showResults()
        empty.visibility = View.GONE
        progress.visibility = View.VISIBLE
        val siteStatus = LinkedHashMap<String, String>()
        sites.forEach { siteStatus[it.name] = "…" }
        fun renderStatus() {
            status.text = siteStatus.entries.joinToString("   ·   ") { "${it.key}: ${it.value}" }
        }
        renderStatus()

        val parser = SiteParser(this)
        searchJob = lifecycleScope.launch {
            val jobs = sites.map { site ->
                launch {
                    val text = try {
                        val found = parser.search(site, query)
                        results.addAll(found)
                        showResults()
                        if (found.isEmpty()) "ничего" else found.size.toString()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        "ошибка (${e.message ?: e.javaClass.simpleName})"
                    }
                    siteStatus[site.name] = text
                    renderStatus()
                }
            }
            jobs.forEach { it.join() }
            progress.visibility = View.INVISIBLE
            if (results.isEmpty()) {
                empty.visibility = View.VISIBLE
                empty.text = "Ничего не найдено.\n\nЕсли у сайта ошибка — откройте его в «Сайты» → " +
                    "«Открыть»: возможно, нужно войти или пройти проверку «я не робот». " +
                    "После этого поиск тоже заработает."
            }
        }
    }

    private fun updateSortLabel() {
        sortButton.text = if (sortBySeeds) "Сортировка: по сидам" else "Сортировка: по размеру"
    }

    private fun showResults() {
        updateSortLabel()
        val sorted = if (sortBySeeds) {
            results.sortedWith(compareByDescending<TorrentResult> { it.seeds ?: -1 })
        } else {
            results.sortedByDescending { it.sizeBytes }
        }
        adapter.submit(sorted)
    }

    private fun onResultClick(r: TorrentResult) {
        launcher.play(r.title, r.magnet, r.torrentUrl)
    }

    private fun onResultLongClick(r: TorrentResult) {
        val actions = ArrayList<Pair<String, () -> Unit>>()
        actions += "Смотреть" to { onResultClick(r) }
        r.pageUrl?.let { url ->
            actions += "Открыть страницу раздачи" to {
                startActivity(SiteBrowserActivity.newIntent(this, url, r.siteName))
            }
        }
        AlertDialog.Builder(this)
            .setTitle(r.title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private inner class ResultsAdapter : RecyclerView.Adapter<ResultsAdapter.Holder>() {
        private var items: List<TorrentResult> = emptyList()

        fun submit(list: List<TorrentResult>) {
            items = list
            notifyDataSetChanged()
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.title)
            val meta: TextView = view.findViewById(R.id.meta)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_torrent_result, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val r = items[position]
            holder.title.text = r.title
            val parts = ArrayList<String>()
            parts += r.siteName
            r.size?.let { parts += it } ?: run {
                if (r.sizeBytes > 0) parts += TorrentLauncher.formatSize(r.sizeBytes)
            }
            r.seeds?.let { parts += "сиды: $it" }
            if (r.magnet == null) parts += ".torrent"
            holder.meta.text = parts.joinToString("   ·   ")
            holder.itemView.setOnClickListener { onResultClick(r) }
            holder.itemView.setOnLongClickListener { onResultLongClick(r); true }
        }
    }
}
