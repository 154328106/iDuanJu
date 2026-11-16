package app.iduanju.core

import app.iduanju.core.novel.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class NovelSourcesTest {
    @After fun cleanup() { NovelSources.fixture = null }
    @Test fun htmlDiscoverySearchAndRead() {
        val source = NovelSources.import("""{"bookSourceUrl":"https://book.example","bookSourceName":"书站","searchUrl":"/search?q={{key}}&page={{page}}","exploreUrl":"热门::/rank?page={{page}}&&最新::/new","ruleSearch":{"bookList":"class.books@tag.li","name":"tag.a@text","bookUrl":"tag.a@href","author":"tag.span@text"},"ruleBookInfo":{"tocUrl":"id.toc@href"},"ruleToc":{"chapterList":"tag.li","chapterName":"tag.a@text","chapterUrl":"tag.a@href"},"ruleContent":{"content":"id.content@html"}}""").single()
        val categories = NovelSources.categories(source)
        assertEquals(listOf("热门", "最新"), categories.map { it.title })
        NovelSources.fixture = { u -> when {
            u.contains("/search") || u.contains("/rank") -> "<ul class='books'><li><a href='/book/1'>雾港</a><span>作者</span></li></ul>"
            u.endsWith("/book/1") -> "<a id='toc' href='/toc/1'>目录</a>"
            u.endsWith("/toc/1") -> "<ul><li><a href='/chapter/1'>第一章</a></li></ul>"
            u.endsWith("/chapter/1") -> "<div id='content'>第一段<br>第二段</div>"
            else -> error(u)
        } }
        val book = NovelSources.search(source, "雾港").single()
        assertEquals("雾港", book.name)
        assertEquals("https://book.example/book/1", book.url)
        assertEquals(book, NovelSources.search(source, "", category = categories.first()).single())
        val chapters = NovelSources.chapters(source, book.url)
        assertEquals("第一章", chapters.single().title)
        assertEquals("第一段\n第二段", NovelSources.content(source, chapters.single()))
    }
    @Test fun jsonDiscoveryAndExplicitUnsupportedRules() {
        val s = NovelSources.import("""[{"bookSourceUrl":"https://example.com","searchUrl":"/s","exploreUrl":[{"title":"榜单","url":"/rank"}],"ruleSearch":{"bookList":"$.list[*]","name":"$.name","bookUrl":"$.url"}}]""").single()
        assertEquals("榜单", NovelSources.categories(s).single().title)
        NovelSources.fixture = { """{"list":[{"name":"测试","url":"/book/1"}]}""" }
        assertEquals("测试", NovelSources.search(s, "").single().name)
        s.json.put("searchUrl", "@js:java.ajax('x')")
        assertThrows(Exception::class.java) { NovelSources.search(s, "") }
    }
}
