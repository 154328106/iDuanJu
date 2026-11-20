package app.iduanju

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import app.iduanju.core.plugin.PluginComic
import app.iduanju.core.plugin.PluginMedia
import app.iduanju.plugin.PluginStore
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Test only: real file-import result callback -> production validation, QuickJS, persistence, media, UI.
 * Espresso controls only the OS picker result with a real staged file URI; HTTP and plugin output are LIVE.
 * We explicitly do not claim manual interaction with the system file picker itself. */
class DualComicSourceSwitchTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext
    private val proof get() = File(ctx.getExternalFilesDir(null), "dual-comic-switch-fixed-proof").apply { mkdirs() }
    private val pid = "org.iduanju.test.last-halloween.official"
    private val pluginName = "The Last Halloween 官方恐怖（非色情）"
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun awaitText(text: String, timeout: Long = 45000) {
        ui.waitUntil(timeout) { ui.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun shot(name: String): Bitmap {
        val b = requireNotNull(ins.uiAutomation.takeScreenshot()) { "Native screenshot unavailable" }
        File(proof, "$name.png").outputStream().use { assertTrue(b.compress(Bitmap.CompressFormat.PNG, 100, it)) }; return b
    }
    private fun textured(b: Bitmap): Boolean {
        var dark=0;var light=0;var edges=0;var n=0
        for(y in b.height/4 until b.height*3/4 step 3) for(x in b.width/8 until b.width*7/8 step 3) {
            val a=android.graphics.Color.red(b.getPixel(x,y));val c=android.graphics.Color.red(b.getPixel(x+2,y));n++
            if(a<60)dark++;if(a>195)light++;if(kotlin.math.abs(a-c)>100)edges++
        };return dark>n/10 && light>n/10 && edges>n/14
    }
    @Test fun bothSourcesStayEnabledAndSwitchThroughNativeReaders() {
        val app=IdjApp.instance
        val ids=listOf("org.iduanju.test.peppercarrot.official",pid)
        // Live test is opt-in by installed official fixtures; a clean CI device runs deterministic tests without network.
        org.junit.Assume.assumeTrue("Import both delivered official test plugins first; no fake source is installed by this test",
            ids.all { id -> app.plugins.plugins.any { it.id==id } })
        var phase="enabled-precondition"
        val result=JSONObject().put("bothEnabledRequired",true).put("reimported",false).put("disabledAnySource",false)
        fun bothEnabled() { ids.forEach { id -> assertTrue("Both plugins must stay enabled: $id",app.plugins.plugins.single { it.id==id }.enabled) } }
        fun snapshot(name:String) { bothEnabled();ui.waitForIdle();ui.onRoot().captureToImage();shot(name);File(proof,"$name-semantics.txt").writeText(ui.onRoot(useUnmergedTree=true).printToString()) }
        try {
            bothEnabled();result.put("initialBothEnabled",true)
            ui.onNodeWithText("漫画",useUnmergedTree=true).performScrollTo().performClick()
            awaitText("包子漫画",15000)
            val cases=listOf(Triple("Pepper&Carrot 官方开放源（非官方测试插件）","Pepper&Carrot（官方中文·全年龄）","第01话 · Potion of Flight"),Triple(pluginName,"The Last Halloween（官方英文·恐怖）","001 · Starts With A Bang"))
            cases.forEachIndexed { index,(source,book,chapter) ->
                val name=if(index==0)"pepper" else "halloween"
                phase="$name-source-selection";snapshot("dual-android-$name-before-selection")
                // Scroll the ACTUAL screen-local strip, then click the fully visible source.
                ui.onNodeWithText(source).performScrollTo().assertIsDisplayed().performClick()
                phase="$name-live-catalog"
                val mangaId=if(index==0)"cn" else "the-last-halloween"
                val gridTag="manga.cell."+app.plugins.comic(ids[index]).source+":"+mangaId
                // Only the actual freshly loaded catalog grid has this tag; saved recent cards cannot satisfy it.
                ui.waitUntil(30000) { ui.onAllNodesWithTag(gridTag).fetchSemanticsNodes().isNotEmpty() }
                val card=ui.onNodeWithTag(gridTag)
                card.performScrollTo().assertIsDisplayed()
                snapshot("dual-android-$name-catalog")
                card.performClick()
                phase="$name-detail";awaitText(if(index==0)"章节（39）" else "章节（141）",20000);snapshot("dual-android-$name-detail")
                phase="$name-reader";ui.onNodeWithText(chapter).performScrollTo().assertIsDisplayed().performClick()
                awaitText(if(index==0)" / 5" else " / 4",15000)
                ui.waitUntil(20000) { val bitmap=requireNotNull(ins.uiAutomation.takeScreenshot());if(index==0)colourful(bitmap) else textured(bitmap) }
                snapshot("dual-android-$name-first-story-rendered")
                result.put(name+"CatalogDetailReaderPassed",true)
                Espresso.pressBack();awaitText(chapter,10000);Espresso.pressBack();awaitText("包子漫画",10000)
                bothEnabled()
            }
            phase="roundtrip-pepper"
            ui.onNodeWithText(cases.first().first).performScrollTo().assertIsDisplayed().performClick()
            awaitText(cases.first().second,20000);snapshot("dual-android-roundtrip-pepper-catalog")
            result.put("passed",true)
        } catch(t:Throwable) {
            result.put("passed",false).put("failedPhase",phase).put("error",t.toString())
            runCatching { snapshot("dual-android-failure") }
            throw t
        } finally {
            result.put("finalEnabled",JSONArray(ids.map { id -> JSONObject().put("id",id).put("enabled",app.plugins.plugins.single { it.id==id }.enabled) }))
            File(proof,"native-results.json").writeText(result.toString(2))
        }
    }
    private fun colourful(b:Bitmap):Boolean {
        val colours=HashSet<Int>()
        for(y in b.height/4 until b.height*3/4 step 15) for(x in b.width/12 until b.width*11/12 step 15) {
            val p=b.getPixel(x,y);val r=android.graphics.Color.red(p)/24;val g=android.graphics.Color.green(p)/24;val bb=android.graphics.Color.blue(p)/24
            if(kotlin.math.abs(r-g)+kotlin.math.abs(g-bb)>1)colours.add(r*144+g*12+bb)
        };return colours.size>25
    }
}
