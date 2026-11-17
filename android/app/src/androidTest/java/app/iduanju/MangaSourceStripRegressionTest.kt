package app.iduanju

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.iduanju.manga.MangaSourceStrip
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Deterministic layout test of the REAL screen-local component, not a fake plugin/runtime. */
class MangaSourceStripRegressionTest {
    @get:Rule val ui=createComposeRule()
    private val pepper="Pepper&Carrot 官方开放源（非官方测试插件）"
    private val halloween="The Last Halloween 官方恐怖（非色情）"
    private fun checkStrip(width:Int,fontScale:Float) {
        ui.setContent {
            val density=LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density,fontScale)) {
                var selected by remember { mutableStateOf("baozi") }
                Box(Modifier.width(width.dp)) {
                    MangaSourceStrip(listOf("baozi" to "包子漫画","mangadex" to "MangaDex","pepper" to pepper,"halloween" to halloween),selected,{selected=it})
                }
            }
        }
        val strip=ui.onNodeWithTag("manga.source.strip")
        strip.assertWidthIsEqualTo(width.dp)
        assertTrue("Source strip height must remain bounded",strip.fetchSemanticsNode().size.height<=88*ui.density.density+1)
        val evidence=org.json.JSONArray()
        for((id,label) in listOf("halloween" to halloween,"pepper" to pepper,"halloween" to halloween)) {
            val chip=ui.onNodeWithTag("manga.source."+id)
            chip.performScrollTo().assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            val size=chip.fetchSemanticsNode().size
            assertTrue("Source chip max width",size.width<=240*ui.density.density+1)
            assertTrue("Source chip max height",size.height<=88*ui.density.density+1)
            chip.performClick().assertIsSelected()
            val layouts=mutableListOf<TextLayoutResult>()
            ui.onNodeWithText(label,useUnmergedTree=true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1,layouts.single().lineCount)
            assertTrue("Long source label must ellipsize, not wrap or become zero-width",layouts.single().isLineEllipsized(0))
            evidence.put(org.json.JSONObject().put("id",id).put("widthPx",size.width).put("heightPx",size.height).put("lineCount",layouts.single().lineCount).put("ellipsized",true).put("selectedAfterClick",true))
        }
        val ctx=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val dir=java.io.File(ctx.getExternalFilesDir(null),"source-strip-regression-proof").apply { mkdirs() }
        java.io.File(dir,"width-$width-font-$fontScale.json").writeText(org.json.JSONObject().put("viewportWidthDp",width).put("fontScale",fontScale.toDouble()).put("density",ui.density.density.toDouble()).put("passed",true).put("measurements",evidence).toString(2))
    }
    @Test fun twoLongLabelsAt320dp()=checkStrip(320,1f)
    @Test fun twoLongLabelsAt320dpDoubleFont()=checkStrip(320,2f)
    @Test fun twoLongLabelsAt240dpDoubleFont()=checkStrip(240,2f)
}
