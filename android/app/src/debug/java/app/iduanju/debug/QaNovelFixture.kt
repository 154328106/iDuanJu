package app.iduanju.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.iduanju.core.novel.NovelCache
import app.iduanju.core.novel.NovelSource
import app.iduanju.core.novel.NovelSources
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Debug-only offline novel data (the same synthetic source and books as NovelThemeTest) so the novel
 * screens can be captured without network access or private sources. NovelSources.fixture lives in process
 * memory, so relaunch the activity without force-stopping the app afterwards.
 *   adb shell am broadcast -n <applicationId>/app.iduanju.debug.QaNovelFixtureReceiver
 */
class QaNovelFixtureReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        val context = ctx.applicationContext
        Thread {
            runCatching { seed(context) }
                .onSuccess { Log.i("IDJ-QA", "novel fixture seeded") }
                .onFailure { Log.w("IDJ-QA", "novel fixture failed", it) }
            pending.finish()
        }.start()
    }

    private fun seed(context: Context) {
        val source = JSONObject().put("bookSourceUrl", "https://preview.invalid").put("bookSourceName", "界面验收 · 合成数据")
            .put("searchUrl", "/search").put("exploreUrl", "全部::\n月票榜::/rank/month\n畅销榜::/rank/sales\n玄幻::\n热门榜::/fantasy")
            .put("ruleSearch", JSONObject().put("bookList", "$.books[*]").put("name", "$.name").put("bookUrl", "$.url").put("author", "$.author").put("kind", "$.kind"))
            .put("ruleBookInfo", JSONObject().put("tocUrl", "$.toc"))
            .put("ruleToc", JSONObject().put("chapterList", "$.chapters[*]").put("chapterName", "$.title").put("chapterUrl", "$.url"))
            .put("ruleContent", JSONObject().put("content", "$.content"))
        context.getSharedPreferences("novel", 0).edit().clear().putString("sources", JSONArray().put(source).toString()).commit()
        NovelSources.fixture = {
            val books = JSONArray()
            listOf("雾港", "灯塔来信", "长夜星河", "山海之间").forEachIndexed { i, title ->
                books.put(JSONObject().put("name", title).put("author", "测试作者").put("kind", "奇幻 · 连载").put("url", "https://preview.invalid/book/$i"))
            }
            val chapters = JSONArray()
            repeat(200) { i -> chapters.put(JSONObject().put("title", "第${i + 1}章").put("url", "https://preview.invalid/chapter/$i")) }
            JSONObject().put("books", books).put("toc", "https://preview.invalid/toc").put("chapters", chapters).put("content", "合成离线正文").toString()
        }
        val cache = NovelCache(File(context.filesDir, "novel-cache-v2"))
        NovelSources.search(NovelSource(source), "fixture").forEach { book ->
            cache.saveBook("https://preview.invalid", book, NovelSources.chapters(NovelSource(source), book.url))
        }
    }
}