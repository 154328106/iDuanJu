package app.iduanju

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.iduanju.core.novel.LegadoMedia
import app.iduanju.core.novel.NovelSources
import app.iduanju.novel.NovelPlayback
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Background playback on the real NASA public audio library (actual HTTP, no fixture): an audiobook keeps playing with
 * its activity stopped and the screen off, answers media keys and the controls of its lock-screen notification, pauses
 * for audio-focus interruptions and resumes by itself only after transient ones, moves on to the next chapter when one
 * ends, and is still the same playback when the user comes back through the notification. No screenshots are taken.
 */
@OptIn(ExperimentalTestApi::class)
class NovelBackgroundPlaybackTest {
    // Not a StandardTestDispatcher as in M3UImportNativeTest: under it the screen's withContext(Dispatchers.IO) blocks run on
    // the main thread (NetworkOnMainThreadException). The screen returns to the main thread itself before touching ExoPlayer.
    @get:Rule val ui = createEmptyComposeRule()
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context get() = ins.targetContext
    private val steps = JSONArray()

    private class Snap(val chapter: Int, val title: String, val playing: Boolean, val playWhenReady: Boolean, val suppression: Int,
                       val positionMs: Long, val durationMs: Long, val failure: String?) {
        override fun toString() = "chapter=$chapter playing=$playing playWhenReady=$playWhenReady suppression=$suppression position=${positionMs}ms failure=$failure"
    }

    private fun shell(cmd: String) =
        ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(cmd)).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun <T> onMain(block: () -> T): T {
        var out: Result<T>? = null
        ins.runOnMainSync { out = runCatching(block) }
        return out!!.getOrThrow()
    }

    private fun snap() = onMain {
        val s = NovelPlayback.state; val p = NovelPlayback.player
        Snap(s?.index ?: -1, s?.title.orEmpty(), p?.isPlaying == true, p?.playWhenReady == true, p?.playbackSuppressionReason ?: -1,
            p?.currentPosition ?: -1, p?.duration ?: -1, s?.failure)
    }

    private fun until(what: String, ms: Long, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!runCatching(condition).getOrDefault(false)) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what")
            Thread.sleep(150)
        }
    }

    private fun await(what: String, ms: Long, condition: (Snap) -> Boolean): Snap {
        val end = System.currentTimeMillis() + ms
        while (true) {
            val s = snap()
            if (condition(s)) return s
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what; $s")
            Thread.sleep(150)
        }
    }

    /** The position really moves on, not just the playing flag. */
    private fun advancing(what: String, byMs: Long = 2000): Snap {
        val from = await("$what: playing", 60000) { it.playing && it.failure == null }
        return await("$what: +$byMs ms", byMs + 20000) { it.playing && it.chapter == from.chapter && it.positionMs >= from.positionMs + byMs }
    }

    private fun halted(what: String, playWhenReady: Boolean): Snap {
        val a = await("$what: halted", 15000) { !it.playing && it.playWhenReady == playWhenReady }
        Thread.sleep(1500)
        val b = snap()
        assertFalse("$what: stays halted", b.playing)
        assertTrue("$what: position held (${a.positionMs} -> ${b.positionMs})", b.positionMs - a.positionMs < 300)
        return b
    }

    private fun record(step: String, s: Snap, vararg extra: Pair<String, Any>) {
        val row = JSONObject().put("step", step).put("chapter", s.chapter).put("title", s.title).put("playing", s.playing)
            .put("play_when_ready", s.playWhenReady).put("position_ms", s.positionMs)
        for ((k, v) in extra) row.put(k, v)
        steps.put(row)
    }

    private fun focus(gain: Int, usage: Int): AudioFocusRequest {
        val request = AudioFocusRequest.Builder(gain).setAudioAttributes(AudioAttributes.Builder().setUsage(usage).build())
            .setOnAudioFocusChangeListener {}.build()
        assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, ctx.getSystemService(AudioManager::class.java).requestAudioFocus(request))
        return request
    }

    private fun abandon(request: AudioFocusRequest) { ctx.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(request) }

    private fun mediaNotification(): Notification? = ctx.getSystemService(NotificationManager::class.java).activeNotifications
        .map { it.notification }.firstOrNull { it.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) }

    private fun action(n: Notification, vararg words: String) =
        n.actions.orEmpty().map { it.title.toString() }.first { t -> words.any { t.equals(it, ignoreCase = true) } }

    /** Taps the first visible node of any window whose text or description is [label]; returns that window's package. */
    private fun tap(label: String, ms: Long = 10000): String? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            val roots = ins.uiAutomation.windows.mapNotNull { it.root } + listOfNotNull(ins.uiAutomation.rootInActiveWindow)
            val node = roots.firstNotNullOfOrNull { root ->
                root.findAccessibilityNodeInfosByText(label).firstOrNull { it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label) }
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

    private fun onScreen(what: String, ms: Long, condition: () -> Boolean) {
        try { ui.waitUntil(ms) { runCatching(condition).getOrDefault(false) } } catch (e: ComposeTimeoutException) {
            val shown = runCatching { ui.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes().flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } } }.getOrDefault(emptyList())
            throw AssertionError("timed out: $what; on screen: ${shown.take(12)}", e)
        }
    }

    private fun mainActivities(vararg stages: Stage) = onMain {
        stages.flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.filterIsInstance<MainActivity>()
    }

    private fun save(name: String, evidence: JSONObject) {
        val out = File(ctx.getExternalFilesDir(null), name).apply { writeText(evidence.toString(1)) }
        shell("mkdir -p /sdcard/Download/novel-qa")
        shell("cp ${out.absolutePath} /sdcard/Download/novel-qa/$name")
    }

    private fun asset(path: String) = ins.context.assets.open(path).bufferedReader().use { it.readText() }

    @Test fun audiobookPlaysOnWithScreenOffLockScreenControlsAndInterruptions() {
        val raw = asset("nasa-audio-public/sources.json")
        val source = NovelSources.import(raw).single()
        val book = NovelSources.search(source, "", category = NovelSources.categories(source).first()).first().name
        val prefs = ctx.getSharedPreferences("novel", 0); val old = prefs.all
        val power = ctx.getSystemService(PowerManager::class.java)
        val keyguard = ctx.getSystemService(KeyguardManager::class.java)
        val lockDisabled = shell("locksettings get-disabled").trim()
        ins.uiAutomation.serviceInfo = ins.uiAutomation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        NovelSources.configureStorage(File(ctx.cacheDir, "legado-port-background"))
        prefs.edit().putString("sources", "[]").remove("rankSource").commit()
        // A swipe-only lock screen, so that its media controls exist to be tapped; the device setting is restored below.
        shell("locksettings set-disabled false")
        shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
        try {
            // The debug build opens the source import panel straight away for qaPage=import, as the other source tests do.
            ActivityScenario.launch<MainActivity>(Intent(ctx, MainActivity::class.java).putExtra("route", "novel").putExtra("qaPage", "import")).use { scenario ->
                onScreen("source import box", 20000) { ui.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
                ui.onAllNodes(hasSetTextAction()).onFirst().performTextInput(raw)
                ui.onNode(hasText("导入书源") and hasClickAction()).performClick()
                onScreen("source imported", 15000) { ui.onAllNodesWithText("已导入", substring = true).fetchSemanticsNodes().isNotEmpty() }
                ui.onNodeWithContentDescription("关闭").performClick()
                ui.onNodeWithText("排行榜").performClick()
                onScreen("book listed", 30000) { ui.onAllNodesWithText(book).fetchSemanticsNodes().isNotEmpty() }
                // The source and its book share a title; the last match is the book.
                ui.onAllNodesWithText(book).onLast().performClick()
                onScreen("chapters loaded", 30000) { ui.onNodeWithTag("novel.detail.read").assertIsEnabled(); true }
                ui.onNodeWithTag("novel.detail.read").performClick()
                onScreen("player on screen", 60000) { ui.onAllNodesWithTag("novel.media.player").fetchSemanticsNodes().isNotEmpty() }
                val session = onMain { NovelPlayback.session }
                val key = onMain { NovelPlayback.state?.queue?.key }
                record("foreground", advancing("foreground"))

                shell("input keyevent KEYCODE_HOME")
                until("activity stopped", 10000) { scenario.state == Lifecycle.State.CREATED }
                record("home: activity stopped", advancing("after Home", 3000), "activity" to scenario.state.name)

                shell("input keyevent KEYCODE_SLEEP")
                until("screen off", 10000) { !power.isInteractive }
                record("screen off", advancing("screen off", 3000), "interactive" to power.isInteractive)

                shell("input keyevent KEYCODE_MEDIA_PAUSE")
                record("screen off: media key pause", halted("media key pause", playWhenReady = false))
                shell("input keyevent KEYCODE_MEDIA_PLAY")
                record("screen off: media key play", advancing("media key play"))
                val c0 = snap().chapter
                shell("input keyevent KEYCODE_MEDIA_NEXT")
                await("media key next", 60000) { it.chapter == c0 + 1 }
                record("screen off: media key next chapter", advancing("next chapter"))
                shell("input keyevent KEYCODE_MEDIA_PREVIOUS")
                // Past its first 3 s "previous" restarts the chapter, as on any player; a second press then goes back.
                runCatching { await("media key previous", 6000) { it.chapter == c0 } }.getOrElse {
                    shell("input keyevent KEYCODE_MEDIA_PREVIOUS")
                    await("media key previous, second press", 6000) { it.chapter == c0 }
                }
                record("screen off: media key previous chapter", advancing("previous chapter"))

                val call = focus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, AudioAttributes.USAGE_VOICE_COMMUNICATION)
                val held = halted("call", playWhenReady = true)
                assertEquals(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS, held.suppression)
                record("call: paused", held)
                abandon(call)
                record("call ended: resumed by itself", advancing("after the call"))
                val prompt = focus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                record("navigation prompt: spoken audio paused", halted("navigation prompt", playWhenReady = true))
                abandon(prompt)
                record("prompt ended: resumed by itself", advancing("after the prompt"))
                val other = focus(AudioManager.AUDIOFOCUS_GAIN, AudioAttributes.USAGE_MEDIA)
                record("another app takes over: paused", halted("taken over", playWhenReady = false))
                abandon(other)
                Thread.sleep(2000)
                assertFalse("waits for the user once another app took over", snap().playing)
                shell("input keyevent KEYCODE_MEDIA_PLAY")
                record("user plays again", advancing("play after the takeover"))
                if (shell("id -u").trim() == "0") {
                    shell("am broadcast -a android.media.AUDIO_BECOMING_NOISY")
                    record("headphones unplugged: paused", halted("becoming noisy", playWhenReady = false))
                    shell("input keyevent KEYCODE_MEDIA_PLAY")
                    advancing("play after unplugging")
                } else steps.put(JSONObject().put("step", "headphones unplugged").put("skipped", "shell is not root; the system broadcast cannot be sent"))

                val ending = snap()
                assertTrue("chapter duration known", ending.durationMs > 10000)
                onMain { NovelPlayback.player!!.seekTo(ending.durationMs - 4000) }
                await("next chapter by itself", 90000) { it.chapter == ending.chapter + 1 }
                record("screen off: chapter ended, next chapter by itself", advancing("chapter after the end"))

                shell("input keyevent KEYCODE_WAKEUP")
                until("screen on", 10000) { power.isInteractive }
                until("lock screen", 10000) { keyguard.isKeyguardLocked }
                val shown = mediaNotification()
                assertNotNull("media notification", shown)
                assertEquals("shown in full on the lock screen", Notification.VISIBILITY_PUBLIC, shown!!.visibility)
                val pauseLabel = action(shown, "Pause", "暂停")
                // A compact keyguard may show only notification icons until the locked shade is expanded.
                fun inspectControls() {
                    fun visit(n: android.view.accessibility.AccessibilityNodeInfo) {
                        val id = n.viewIdResourceName.orEmpty()
                        if (n.packageName == "com.android.systemui" && (id.contains("action") || id.contains("media") || id.contains("expand"))) android.util.Log.i("LockQA", "control $id ${n.contentDescription} visible=${n.isVisibleToUser}")
                        for (i in 0 until n.childCount) n.getChild(i)?.let(::visit)
                    }
                    ins.uiAutomation.windows.mapNotNull { it.root }.forEach { android.util.Log.i("LockQA", "window ${it.packageName} expected=$pauseLabel"); visit(it) }
                }
                val pauseWindow = tap(pauseLabel, 2000) ?: run {
                    inspectControls()
                    shell("cmd statusbar expand-notifications")
                    assertTrue("still on the lock screen", keyguard.isKeyguardLocked)
                    Thread.sleep(400); inspectControls()
                    tap(pauseLabel)
                }
                assertEquals("com.android.systemui", pauseWindow)
                record("lock screen: pause tapped", halted("lock screen pause", playWhenReady = false), "keyguard_locked" to keyguard.isKeyguardLocked)
                assertEquals("com.android.systemui", tap(action(mediaNotification()!!, "Play", "播放")))
                record("lock screen: play tapped", advancing("lock screen play"), "keyguard_locked" to keyguard.isKeyguardLocked)

                shell("wm dismiss-keyguard")
                until("unlocked", 10000) { !keyguard.isKeyguardLocked }
                val before = mainActivities(Stage.STOPPED).toSet()
                shell("cmd statusbar expand-notifications")
                val title = snap().title
                val tapped = tap(title, 8000) != null
                if (!tapped) mediaNotification()!!.contentIntent.send()
                until("app in front", 20000) { mainActivities(Stage.RESUMED).isNotEmpty() }
                onScreen("same player on screen", 30000) {
                    ui.onAllNodesWithTag("novel.media.player").fetchSemanticsNodes().isNotEmpty() && ui.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
                }
                assertSame("same media session", session, onMain { NovelPlayback.session })
                assertEquals("same book", key, onMain { NovelPlayback.state?.queue?.key })
                val sameActivity = mainActivities(Stage.RESUMED).any { it in before }
                record("back in the app: same playback", advancing("back in the app"),
                    "via" to if (tapped) "notification tapped in the shade" else "notification content intent", "same_activity" to sameActivity)

                save("background-playback-android.json", JSONObject().put("source", "NASA public audio library (images-api.nasa.gov): actual HTTP, no fixture")
                    .put("book", book).put("device", "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
                    .put("lock_screen", "swipe lock enabled for the test and restored afterwards (locksettings get-disabled was $lockDisabled)")
                    .put("screenshots", 0).put("steps", steps))
            }
        } finally {
            shell("cmd statusbar collapse")
            app.iduanju.ui.UiEvidence.page = ""
            onMain { NovelPlayback.stop() }
            if (lockDisabled == "true") shell("locksettings set-disabled true")
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
            mainActivities(Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED).forEach { a -> onMain { a.finish() } }
            NovelSources.configureStorage(ctx.filesDir)
            val edit = prefs.edit().clear()
            for ((k, v) in old) when (v) { is String -> edit.putString(k, v); is Int -> edit.putInt(k, v); is Boolean -> edit.putBoolean(k, v); is Float -> edit.putFloat(k, v); is Long -> edit.putLong(k, v) }
            edit.commit()
        }
    }

    @Test fun expiredQueuedChapterIsResolvedAgain() {
        val source = NovelSources.import(asset("nasa-audio-public/sources.json")).single()
        val book = NovelSources.search(source, "", category = NovelSources.categories(source).first()).first()
        val toc = NovelSources.details(source, book.url).chapters
        assertTrue("at least two chapters", toc.size >= 2)
        fun media(i: Int) = LegadoMedia.resolve(source, toc[i].copy(index = i), book.url, toc.getOrNull(i + 1)?.url)
        val resolves = AtomicInteger()
        // The first address handed out for chapter 2 has expired by the time it plays: the server refuses it.
        val queue = NovelPlayback.Queue("qa-expired-address", book.name, 1, toc.map { it.title }) { i ->
            media(i).let { if (i == 1 && resolves.getAndIncrement() == 0) it.copy(url = it.url.substringBeforeLast('/') + "/expired-address.mp3") else it }
        }
        val first = media(0)
        onMain { NovelPlayback.start(ctx, queue, 0, first) }
        try {
            record("chapter 1", advancing("chapter 1"))
            until("chapter 2 prefetched", 30000) { resolves.get() == 1 }
            val ending = snap()
            assertTrue("chapter duration known", ending.durationMs > 10000)
            onMain { NovelPlayback.player!!.seekTo(ending.durationMs - 3000) }
            await("chapter 2", 90000) { it.chapter == 1 }
            val playing = advancing("chapter 2 after resolving it again")
            assertNull(playing.failure)
            assertEquals("chapter 2 resolved again", 2, resolves.get())
            record("expired address of chapter 2 resolved again", playing, "resolves" to resolves.get())
            save("background-expired-address-android.json", JSONObject()
                .put("source", "NASA public audio library (images-api.nasa.gov): actual HTTP; only the first chapter-2 address is replaced by a refused one")
                .put("book", book.name).put("screenshots", 0).put("steps", steps))
        } finally {
            onMain { NovelPlayback.stop() }
        }
    }
}
