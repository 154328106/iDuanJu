package app.iduanju.core

import app.iduanju.core.plugin.*
import org.json.*
import org.junit.*
import org.junit.Assert.*

/** Native M3U import must accept, name and reject exactly like scripts/source-compat/m3u_to_idj.py (shared cases). */
class M3UImportTest {
    private val cases = JSONObject(javaClass.getResource("/m3u-cases.json")!!.readText())

    @Test fun channelsDomainsAndMainJsMatchTheReviewedScript() {
        val accept = cases.getJSONArray("accept")
        assertTrue(accept.length() >= 4)
        for (i in 0 until accept.length()) {
            val c = accept.getJSONObject(i)
            val channels = M3UImport.parse(c.getString("m3u"), if (c.isNull("base")) null else c.getString("base"))
            val expected = c.getJSONArray("channels")
            assertEquals(c.getString("id"), expected.length(), channels.size)
            channels.forEachIndexed { j, ch ->
                val e = expected.getJSONObject(j)
                assertEquals(e.getString("title"), ch.title)
                assertEquals(e.getString("group"), ch.group)
                assertEquals(if (e.has("cover")) e.getString("cover") else null, ch.cover)
                assertEquals(e.getString("id"), ch.id)
                assertEquals(e.getString("url"), ch.url)
                val h = e.getJSONObject("headers")
                assertEquals(h.keys().asSequence().associateWith { h.getString(it) }, ch.headers)
            }
            val text = M3UImport.bundle(channels, c.getString("name"), c.getString("id"))
            val manifest = JSONObject(text).getJSONObject("manifest")
            assertEquals(c.getString("id"), c.getString("integrity"), manifest.getJSONObject("integrity").getJSONObject("files").getString("main.js"))
            assertEquals(c.getJSONArray("domains").toList(), manifest.getJSONObject("permissions").getJSONArray("http").toList())
            assertEquals(c.getJSONArray("domains").toList(), manifest.getJSONObject("permissions").getJSONArray("media").toList())
            val bundle = PluginBundle.parse(text)
            assertEquals(c.getString("id"), bundle.manifest.id)
            assertEquals(c.getString("integrity"), "sha256-" + java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(bundle.source.toByteArray())))
        }
    }

    @Test fun rejectsExactlyWhatTheReviewedScriptRejects() {
        val reject = cases.getJSONArray("reject")
        assertTrue(reject.length() >= 30)
        for (i in 0 until reject.length()) {
            val c = reject.getJSONObject(i)
            val error = runCatching {
                val channels = M3UImport.parse(c.getString("m3u"))
                if (c.has("id")) M3UImport.bundle(channels, "X", c.getString("id"))
            }.exceptionOrNull()
            assertTrue("${c.getString("code")} accepted: ${c.getString("m3u")}", error is M3UImport.Rejected)
            assertEquals(c.getString("m3u"), c.getString("code"), (error as M3UImport.Rejected).code)
        }
    }

    @Test fun importIdsAndNamesAreStable() {
        assertEquals(M3UImport.importId("https://a.org/x.m3u"), M3UImport.importId("https://a.org/x.m3u"))
        assertTrue(Regex("com\\.idj\\.m3u\\.[0-9a-f]{12}").matches(M3UImport.importId("x")))
        assertEquals("公开 动画", M3UImport.nameFromUrl("https://a.org/lists/%E5%85%AC%E5%BC%80%20%E5%8A%A8%E7%94%BB.m3u?x=1"))
        assertNull(M3UImport.nameFromUrl("https://a.org/"))
        assertTrue(M3UImport.isChannelList("\uFEFF#EXTM3U\n"))
        assertFalse(M3UImport.isChannelList("{\"manifest\":{}}"))
    }
}
