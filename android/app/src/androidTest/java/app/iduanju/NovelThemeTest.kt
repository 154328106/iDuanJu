package app.iduanju

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.iduanju.core.novel.NovelSources
import app.iduanju.novel.NovelScreen
import app.iduanju.ui.theme.IdjTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class NovelThemeTest {
    @get:Rule val ui = createComposeRule()
    @Before fun seed() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val source=JSONObject().put("bookSourceUrl","https://preview.invalid").put("bookSourceName","界面验收 · 合成数据")
            .put("searchUrl","/search").put("exploreUrl","全部::\n月票榜::/rank/month\n畅销榜::/rank/sales\n玄幻::\n热门榜::/fantasy")
            .put("ruleSearch",JSONObject().put("bookList","$.books[*]").put("name","$.name").put("bookUrl","$.url").put("author","$.author").put("kind","$.kind"))
            .put("ruleBookInfo",JSONObject().put("tocUrl","$.toc"))
            .put("ruleToc",JSONObject().put("chapterList","$.chapters[*]").put("chapterName","$.title").put("chapterUrl","$.url"))
            .put("ruleContent",JSONObject().put("content","$.content"))
        context.getSharedPreferences("novel",0).edit().clear().putString("sources",JSONArray().put(source).toString()).commit()
        NovelSources.fixture={
            val books=JSONArray()
            listOf("雾港","灯塔来信","长夜星河","山海之间").forEachIndexed { i,title ->
                books.put(JSONObject().put("name",title).put("author","测试作者").put("kind","奇幻 · 连载").put("url","https://preview.invalid/book/$i"))
            }
            val chapters=JSONArray()
            repeat(200){i->chapters.put(JSONObject().put("title","第${i+1}章").put("url","https://preview.invalid/chapter/$i"))}
            JSONObject().put("books",books).put("toc","https://preview.invalid/toc").put("chapters",chapters).put("content","合成离线正文").toString()
        }
        org.junit.Assert.assertEquals(4,NovelSources.search(app.iduanju.core.novel.NovelSource(source),"",category=app.iduanju.core.novel.NovelCategory("月票榜","/rank/month")).size)
    }
    @After fun cleanup(){NovelSources.fixture=null}
    private fun screenshot(name:String){
        ui.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val path=File(instrumentation.targetContext.getExternalFilesDir(null),"novel-$name.png")
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        try { path.outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)} }
        finally {bitmap.recycle()}
    }
    private fun verify(dark:Boolean){
        val style=if(dark)"dark" else "light"
        ui.setContent{IdjTheme(dark=dark){NovelScreen(onBack={})}}
        ui.waitUntil(10000){ui.onAllNodesWithText("月票榜").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("月票榜").performClick()
        try { ui.waitUntil(10000){ui.onAllNodesWithText("1. 雾港").fetchSemanticsNodes().isNotEmpty()} }
        catch(e:Exception) { screenshot("$style-rank-failed"); ui.onRoot().printToLog("NovelUI"); throw e }
        screenshot("$style-rank")
        ui.onNodeWithText("书源").performClick()
        ui.onNodeWithText("书源配置").assertIsDisplayed()
        screenshot("$style-sources")
        ui.onAllNodesWithText("返回").onLast().performClick()
        ui.onNodeWithText("搜索书名或作者").performClick()
        ui.onNodeWithText("书名 / 作者").performTextInput("雾港")
        ui.onNodeWithText("搜索").performClick()
        ui.waitUntil(10000){ui.onAllNodesWithText("雾港",substring=false).fetchSemanticsNodes().size>=2}
        screenshot("$style-search")
        ui.onAllNodesWithText("雾港",substring=false).onLast().performClick()
        ui.waitUntil(10000){ui.onAllNodesWithText("缓存 / 下载").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("缓存 / 下载").performClick()
        ui.onNodeWithText("缓存与下载").assertIsDisplayed()
        screenshot("$style-download")
        ui.onNodeWithText("缓存后 50 章").performClick()
        ui.waitUntil(15000){ui.onAllNodesWithText("已缓存 50 / 200 章").fetchSemanticsNodes().isNotEmpty()}
    }
    @Test fun lightThemeFlow(){verify(false)}
    @Test fun darkThemeFlow(){verify(true)}
}
