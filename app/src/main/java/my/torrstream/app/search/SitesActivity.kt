package my.torrstream.app.search

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import my.torrstream.app.R

/** Список сайтов для поиска: добавить, изменить, выключить, удалить, открыть в браузере */
class SitesActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private val dp get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.lampa_background))
            setPadding(px(16), px(12), px(16), px(12))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Сайты для поиска"
            textSize = 22f
            setTextColor(getColor(R.color.tv_white))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(MaterialButton(this).apply {
            text = "+ Добавить сайт"
            setOnClickListener { addSite() }
        })
        root.addView(header)
        root.addView(TextView(this).apply {
            text = "Поиск идёт по всем включённым сайтам сразу. Если сайт требует входа " +
                "или показывает проверку «я не робот» — откройте его кнопкой «Открыть» и войдите: " +
                "поиск будет использовать этот вход."
            textSize = 14f
            setTextColor(getColor(R.color.white_35))
            setPadding(0, px(8), 0, px(12))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        list.removeAllViews()
        val sites = SiteStore.load(this)
        if (sites.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Пока пусто. Нажмите «+ Добавить сайт»."
                textSize = 16f
                setTextColor(getColor(R.color.white_35))
                setPadding(0, px(24), 0, 0)
            })
            return
        }
        for (site in sites) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.result_item_bg)
                setPadding(px(12), px(8), px(8), px(8))
            }
            row.addView(CheckBox(this).apply {
                isChecked = site.enabled
                setOnCheckedChangeListener { _, on -> SiteStore.upsert(this@SitesActivity, site.copy(enabled = on)) }
            })
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                text = site.name
                textSize = 17f
                setTextColor(getColor(R.color.tv_white))
            })
            texts.addView(TextView(this).apply {
                text = site.searchUrl
                textSize = 12f
                maxLines = 1
                setTextColor(getColor(R.color.white_35))
            })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(smallButton("Открыть") {
                startActivity(SiteBrowserActivity.newIntent(this, site.homeUrl, site.name))
            })
            row.addView(smallButton("Изменить") { editSite(site) })
            row.addView(smallButton("Удалить") {
                AlertDialog.Builder(this)
                    .setMessage("Удалить «${site.name}»?")
                    .setPositiveButton("Удалить") { _, _ -> SiteStore.remove(this, site.id); render() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            })
            list.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = px(8) })
        }
    }

    private fun smallButton(text: String, onClick: () -> Unit) = MaterialButton(
        this, null, androidx.appcompat.R.attr.borderlessButtonStyle
    ).apply {
        this.text = text
        setTextColor(getColor(R.color.teal_200))
        setOnClickListener { onClick() }
    }

    private fun field(hint: String, value: String = "", uri: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = if (uri) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        else InputType.TYPE_CLASS_TEXT
    }

    /**
     * Добавление. Проще всего — указать адрес сайта: откроется браузер, там ищем любой
     * фильм и жмём «Сохранить как поиск». Кто знает адрес страницы результатов, может
     * вписать его сразу вместе со словом, которое искал.
     */
    private fun addSite() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), 0)
        }
        val address = field("Адрес сайта, например rutor.info", uri = true)
        box.addView(address)
        AlertDialog.Builder(this)
            .setTitle("Добавить сайт")
            .setMessage(
                "Впишите адрес сайта — он откроется во встроенном браузере. Найдите там любой " +
                    "фильм и нажмите «Сохранить как поиск» вверху."
            )
            .setView(box)
            .setPositiveButton("Открыть") { _, _ ->
                var url = address.text.toString().trim()
                if (url.isEmpty()) return@setPositiveButton
                if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
                startActivity(SiteBrowserActivity.newIntent(this, url, ""))
            }
            .setNeutralButton("Вручную") { _, _ -> editSite(null) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Ручной ввод: название, адрес страницы результатов и слово, которое искали */
    private fun editSite(site: Site?) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), 0)
        }
        val name = field("Название", site?.name.orEmpty())
        val url = field("Адрес страницы результатов поиска", site?.searchUrl.orEmpty(), uri = true)
        val word = field("Что искали (если в адресе нет {q})")
        box.addView(name)
        box.addView(url)
        box.addView(word)
        AlertDialog.Builder(this)
            .setTitle(if (site == null) "Новый сайт" else "Изменить сайт")
            .setMessage("В адресе вместо названия фильма должно стоять {q} — или впишите слово, которое искали, и приложение заменит его само.")
            .setView(box)
            .setPositiveButton("Сохранить") { _, _ ->
                val tpl = Site.template(url.text.toString(), word.text.toString())
                if (tpl == null) {
                    Toast.makeText(this, "В адресе нет ни {q}, ни слова «${word.text}»", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val title = name.text.toString().trim().ifEmpty { "Сайт" }
                val updated = site?.copy(name = title, searchUrl = tpl.first, encoding = tpl.second)
                    ?: Site(name = title, searchUrl = tpl.first, encoding = tpl.second)
                SiteStore.upsert(this, updated)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
