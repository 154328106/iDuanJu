package app.iduanju.core

import app.iduanju.core.cms.TVBoxImport
import org.junit.Assert.*
import org.junit.Test

class TVBoxImportTest {
    @Test fun importsOnlyTypeOneHTTPSApis() {
        val c = TVBoxImport.parse("""{"name":"demo","sites":[
            {"key":"cms","name":"CMS","type":1,"api":"http://cms.example/api.php/provide/vod/"},
            {"key":"jar","name":"JAR","type":3,"api":"csp_demo"},
            {"key":"dup","name":"Duplicate","type":1,"api":"https://cms.example/api.php/provide/vod/"}
        ],"spider":"https://example.org/a.jar"}""")
        assertEquals("demo", c.name)
        assertEquals(1, c.sites.size)
        assertEquals("https://cms.example/api.php/provide/vod/", c.sites.single().api)
        assertEquals(2, c.skipped)
    }

    @Test fun importsLargeMixedCatalogAndReadsXmlPlaybackLines() {
        val rows = (0..600).map { """{"type":3,"api":"csp_$it"}""" } + (0..65).map { """{"type":0,"api":"https://cms.example/$it"}""" }
        val config = TVBoxImport.parse("""{"sites":[${rows.joinToString(",")}] }""")
        assertEquals(66, config.sites.size)
        val xml = """<rss><list pagecount="2"><video><id>42</id><name>海岸</name><dl><dd flag="高清"><![CDATA[第一集${'$'}https://media.example/a.m3u8]]></dd></dl></video></list><class><ty id="7">纪录片</ty></class></rss>"""
        val doc = app.iduanju.core.cms.MacCMSXml.parse(xml)
        assertEquals("42", doc.getJSONArray("list").getJSONObject(0).getString("vod_id"))
        val record = doc.getJSONArray("list").getJSONObject(0)
        assertEquals("https://media.example/a.m3u8", app.iduanju.core.cms.MacCMS.lines(record.getString("vod_play_from"), record.getString("vod_play_url")).single().eps.single().second)
        assertEquals("纪录片", app.iduanju.core.cms.MacCMS.parseCategories(doc).single().name)
        assertThrows(IllegalArgumentException::class.java) { app.iduanju.core.cms.MacCMSXml.parse("<!DOCTYPE rss>" + xml) }
    }

    @Test fun refusesConfigWithoutImportableApi() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            TVBoxImport.parse("""{"sites":[{"type":3,"api":"csp_demo"}],"spider":"x.jar"}""")
        }
        assertTrue(e.message.orEmpty().contains("type=1"))
    }
}
