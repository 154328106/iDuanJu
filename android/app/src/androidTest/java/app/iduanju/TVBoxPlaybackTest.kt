package app.iduanju

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import android.os.SystemClock
import org.junit.Assert.assertTrue
import androidx.test.platform.app.InstrumentationRegistry
import app.iduanju.core.cms.CMSSite
import app.iduanju.core.cms.MacCMS
import app.iduanju.plugin.PluginStore
import app.iduanju.ui.UiEvidence
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in device check against an actual pasted TVBox collection and one caller-selected catalog. */
class TVBoxPlaybackTest {
    @Test fun importedCatalogPlaysInSeriesPlayer() {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("tvboxUrl")
        val api = args.getString("cmsApi")
        assumeTrue(url != null && api != null)
        val app = IdjApp.instance
        val config = (app.plugins.interpret(url!!) as PluginStore.Pasted.TVBox).config
        val site = config.sites.first { it.api == MacCMS.normalize(api!!) }
        val key = CMSSite.key(site.api)
        val existing = app.cms.site(key)
        val saved = existing ?: app.cms.add(site.name, site.api, site.adult, "TVBox QA")
        try {
            val series = MacCMS.list(saved, "", 1).items.first()
            UiEvidence.playerPositionMs = 0
            UiEvidence.playerPlaying = false
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val intent = Intent(ctx, MainActivity::class.java).putExtra("route", "player/${android.net.Uri.encode(series.seriesId)}/1")
            ActivityScenario.launch<MainActivity>(intent).use {
                val deadline = SystemClock.elapsedRealtime() + 90000
                while (SystemClock.elapsedRealtime() < deadline && !(UiEvidence.playerPlaying && UiEvidence.playerPositionMs > 2000)) SystemClock.sleep(200)
                assertTrue("player position ${UiEvidence.playerPositionMs} ms", UiEvidence.playerPlaying && UiEvidence.playerPositionMs > 2000)
            }
        } finally { if (existing == null) InstrumentationRegistry.getInstrumentation().runOnMainSync { app.cms.remove(key) } }
    }
}
