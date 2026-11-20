package app.iduanju.core

import app.iduanju.core.novel.NovelImport
import java.nio.charset.Charset
import org.junit.Assert.*
import org.junit.Test

class NovelImportTest {
    @Test fun decodesBomAndSplitsHeadings() {
        val raw = "\uFEFF书名\n\n第一章 雨夜\n正文\n\n第二章 渡口\n结尾".toByteArray(Charsets.UTF_8)
        val parsed = NovelImport.parse(raw, "雾港.txt")
        assertEquals("雾港", parsed.title)
        assertEquals(listOf("第一章 雨夜", "第二章 渡口"), parsed.chapters.map { it.title })
        assertEquals(parsed.text.length, parsed.chapters.last().end)
    }

    @Test fun noHeadingTextIsChunked() {
        val parsed = NovelImport.parse("a\n".repeat(5000).toByteArray(), "plain.txt")
        assertTrue(parsed.chapters.size > 1)
        assertEquals(parsed.text.length, parsed.chapters.last().end)
    }

    @Test fun decodesGB18030AndUTF16BOM() {
        val text = "第一章 雾港\n正文"
        val gb = NovelImport.parse(text.toByteArray(Charset.forName("GB18030")), "gb.txt")
        val utf16 = NovelImport.parse(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE), "wide.txt")
        assertEquals(text, gb.text)
        assertEquals(gb.text, utf16.text)
        assertEquals(gb.chapters, utf16.chapters)
    }
}
