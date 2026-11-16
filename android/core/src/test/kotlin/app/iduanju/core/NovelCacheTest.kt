package app.iduanju.core

import app.iduanju.core.novel.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class NovelCacheTest {
    @Test fun offlineReloadAndSourceIsolation() {
        val dir=Files.createTempDirectory("novel-cache").toFile()
        try {
            val cache=NovelCache(dir);val book=NovelHit("雾港","作者","https://book.test/b")
            val toc=listOf(NovelChapter("一","https://book.test/1"),NovelChapter("二","https://book.test/2"))
            cache.saveBook("sourceA",book,toc);cache.save("sourceA",book.url,toc[1].url,"第二章正文")
            val fresh=NovelCache(dir)
            assertEquals(1,fresh.count("sourceA",book.url,toc))
            assertEquals("第二章正文",fresh.read("sourceA",book.url,toc.reversed()[0].url))
            assertNull(fresh.read("sourceB",book.url,toc[1].url))
            assertEquals(toc,fresh.books().single().chapters)
            assertThrows(Exception::class.java) { cache.save("sourceA",book.url,toc[0].url,"") }
        } finally { dir.deleteRecursively() }
    }
}
