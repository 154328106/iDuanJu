package app.iduanju

import android.content.Intent
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import app.iduanju.core.plugin.M3UImport
import app.iduanju.ui.UiEvidence
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/**
 * A reviewed M3U picked in the real system file picker (tapped like a user, no stubbed result) becomes a home channel
 * whose stream plays for at least 2 s. The picker is another app, so it is driven through platform UiAutomation.
 */
@OptIn(ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class M3UImportNativeTest {
    // Effects must resume on the main thread as in the app: the default unconfined test dispatcher resumes them on the
    // IO thread that finished the work, and ExoPlayer rejects calls from any thread but its own.
    @get:Rule val ui = createEmptyComposeRule(kotlinx.coroutines.test.StandardTestDispatcher())
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val fileName = "idj-qa-m3u-import.m3u"

    private fun shell(cmd: String) =
        ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(cmd)).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun waitFor(what: String, ms: Long, condition: () -> Boolean) {
        try { ui.waitUntil(ms) { runCatching(condition).getOrDefault(false) } } catch (e: ComposeTimeoutException) {
            val shown = runCatching { ui.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes().flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } } }.getOrDefault(emptyList())
            throw AssertionError("timed out: $what; on screen: ${shown.take(12)}", e)
        }
    }

    /** Taps the first visible node of the active window whose text or description is one of [labels]; returns that window's package. */
    private fun tapActiveWindow(labels: List<String>, ms: Long): String? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            val node = ins.uiAutomation.rootInActiveWindow?.let { root ->
                labels.firstNotNullOfOrNull { l ->
                    root.findAccessibilityNodeInfosByText(l).firstOrNull { it.isVisibleToUser && (it.text?.toString() == l || it.contentDescription?.toString() == l) }
                }
            }
            if (node != null) {
                val b = Rect().also { node.getBoundsInScreen(it) }
                shell("input tap ${b.centerX()} ${b.centerY()}")
                return node.packageName?.toString()
            }
            Thread.sleep(300)
        }
        return null
    }

    @Test fun pickedM3UBecomesAPlayingHomeChannel() {
        val ctx = ins.targetContext
        val case = JSONObject(ins.context.assets.open("m3u-cases.json").bufferedReader().use { it.readText() }).getJSONArray("accept").getJSONObject(0)
        val text = case.getString("m3u")
        val title = case.getJSONArray("channels").getJSONObject(0).getString("title")
        val id = M3UImport.importId(text.trim())
        val name = fileName.substringBeforeLast('.')
        val store = IdjApp.instance.plugins
        ins.runOnMainSync { store.remove(id) }
        val staged = File(ctx.getExternalFilesDir(null), fileName).apply { writeText(text) }
        shell("cp ${staged.absolutePath} /sdcard/Download/$fileName")
        try {
            assertTrue("staged into Download", shell("ls /sdcard/Download/").contains(fileName))
            ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java).putExtra("route", "plugins")).use {
                val importRow = "从文件导入（.idjplugin / .m3u）"
                waitFor("plugins screen", 15000) { ui.onAllNodesWithText(importRow).fetchSemanticsNodes().isNotEmpty() }
                ui.onNodeWithText(importRow).performClick()
                // The picker reopens wherever it was last left; fall back to its Downloads root.
                val picker = tapActiveWindow(listOf(fileName), 8000) ?: run {
                    assertNotNull("picker roots", tapActiveWindow(listOf("显示根目录", "Show roots"), 5000))
                    assertNotNull("picker Downloads root", tapActiveWindow(listOf("下载", "Downloads"), 5000))
                    tapActiveWindow(listOf(fileName), 10000)
                }
                assertNotNull("$fileName in the system picker", picker)
                assertNotEquals(ctx.packageName, picker)
                waitFor("install message", 30000) { ui.onAllNodesWithText("已安装「$name」", substring = true).fetchSemanticsNodes().isNotEmpty() }
                val rec = store.plugins.single { it.id == id }
                assertEquals(listOf(name, "video", "file"), listOf(rec.name, rec.type, rec.origin))

                Espresso.pressBack()
                val channel = "home.channel.$name"
                waitFor(channel, 15000) { ui.onAllNodesWithContentDescription(channel).fetchSemanticsNodes().isNotEmpty() }
                ui.onNodeWithContentDescription(channel).apply { runCatching { performScrollTo() } }.performClick()
                val card = hasClickAction() and hasText(title)
                waitFor("channel card", 30000) { ui.onAllNodes(card).fetchSemanticsNodes().isNotEmpty() }
                ui.onAllNodes(card).onFirst().performClick()
                val play = hasClickAction() and (hasText("开始播放") or hasText("继续第", substring = true))
                waitFor("detail play button", 30000) { ui.onAllNodes(play).fetchSemanticsNodes().isNotEmpty() }
                UiEvidence.playerPositionMs = 0; UiEvidence.playerPlaying = false
                ui.onAllNodes(play).onFirst().performClick()
                waitFor("2 s of playback", 60000) { UiEvidence.playerPlaying && UiEvidence.playerPositionMs >= 2000 }

                val evidence = JSONObject().put("picker_package", picker).put("plugin_id", id).put("plugin_name", rec.name)
                    .put("type", rec.type).put("origin", rec.origin).put("home_channel", channel).put("title", title)
                    .put("position_ms", UiEvidence.playerPositionMs).put("playing", UiEvidence.playerPlaying)
                val out = File(ctx.getExternalFilesDir(null), "m3u-import-android.json").apply { writeText(evidence.toString(1)) }
                shell("mkdir -p /sdcard/Download/novel-qa")
                shell("cp ${out.absolutePath} /sdcard/Download/novel-qa/${out.name}")
            }
        } finally {
            ins.runOnMainSync { store.remove(id) }
            shell("rm -f /sdcard/Download/$fileName")
            staged.delete()
        }
    }
}
