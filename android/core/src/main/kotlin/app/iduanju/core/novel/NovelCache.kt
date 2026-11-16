package app.iduanju.core.novel

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Source + book + chapter URL keys survive TOC reordering without mixing books or sources. */
class NovelCache(private val root: File) {
    data class Saved(val source: String, val book: NovelHit, val chapters: List<NovelChapter>)
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun dir(source: String, book: String) = File(root, hash(source + "\n" + book))
    private fun chapter(source: String, book: String, url: String) = File(dir(source,book), hash(url) + ".txt")
    fun read(source: String, book: String, url: String): String? = chapter(source,book,url).takeIf { it.isFile && it.length() > 0 }?.readText()
    fun count(source: String, book: String, toc: List<NovelChapter>) = toc.count { chapter(source,book,it.url).let { f -> f.isFile && f.length() > 0 } }
    private fun atomic(file: File, text: String) {
        file.parentFile!!.mkdirs()
        val tmp = File.createTempFile("chapter-", ".part", file.parentFile)
        try { tmp.writeText(text); Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) } finally { tmp.delete() }
    }
    @Synchronized fun save(source: String, book: String, url: String, text: String) {
        require(text.isNotBlank()) { "正文为空，未写入缓存" }
        atomic(chapter(source,book,url),text)
    }
    @Synchronized fun saveBook(source: String, book: NovelHit, chapters: List<NovelChapter>) {
        val json = JSONObject().put("source",source).put("name",book.name).put("author",book.author).put("url",book.url).put("cover",book.cover)
            .put("chapters",JSONArray(chapters.map { JSONObject().put("title",it.title).put("url",it.url) }))
        atomic(File(dir(source,book.url),"book.json"),json.toString())
    }
    fun books(): List<Saved> = root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { directory ->
        runCatching { val j=JSONObject(File(directory,"book.json").readText());val a=j.getJSONArray("chapters")
            Saved(j.getString("source"),NovelHit(j.getString("name"),j.optString("author"),j.getString("url"),j.optString("cover")),
                (0 until a.length()).map { a.getJSONObject(it).let { c -> NovelChapter(c.getString("title"),c.getString("url")) } })
        }.getOrNull()
    }
}
