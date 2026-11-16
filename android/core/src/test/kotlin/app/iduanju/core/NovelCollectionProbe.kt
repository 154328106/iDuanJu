package app.iduanju.core

import app.iduanju.core.novel.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Bounded subprocess runner for the user-supplied collection; no source scripts are bundled. */
object NovelCollectionProbe {
    @JvmStatic fun main(args: Array<String>) {
        val raw = JSONArray(File(args[0]).readText())
        val source = NovelSource(raw.getJSONObject(args[1].toInt()))
        val report = JSONObject().put("index", args[1].toInt()).put("name", source.name)
        fun stage(name: String, block: () -> Int) {
            try { report.put(name, JSONObject().put("status", "ok").put("count", block())) }
            catch (e: Exception) { report.put(name, JSONObject().put("status", "failed").put("error", e.message.orEmpty().replace(Regex("https?://[^\\s]+"), "[source URL]").take(240))) }
        }
        var categories = emptyList<NovelCategory>()
        stage("categories") { categories = NovelSources.categories(source); categories.size }
        if (args.getOrNull(2) == "live") {
            var hits = emptyList<NovelHit>()
            categories.firstOrNull { it.url.isNotBlank() }?.let { cat -> stage("explore") {
                hits = NovelSources.search(source, "", category = cat)
                check(hits.isNotEmpty()) { "empty book list" }; hits.size
            } }
            var found = emptyList<NovelHit>()
            stage("search") { found = NovelSources.search(source, "西游记"); check(found.isNotEmpty()) { "empty search" }; found.size }
            (found.firstOrNull() ?: hits.firstOrNull())?.let { book ->
                var toc = emptyList<NovelChapter>()
                stage("toc") { toc = NovelSources.chapters(source, book.url); check(toc.isNotEmpty()) { "empty chapter list" }; toc.size }
                toc.firstOrNull()?.let { chapter -> stage("content") {
                    val body = NovelSources.content(source, chapter, toc.getOrNull(1)?.url)
                    check(body.length > 80) { "body too short" }
                    val root = java.nio.file.Files.createTempDirectory("novel-probe").toFile()
                    try {
                        val cache = NovelCache(root)
                        cache.saveBook(source.id, book, toc); cache.save(source.id, book.url, chapter.url, body)
                        check(NovelCache(root).read(source.id, book.url, chapter.url) == body) { "offline cache mismatch" }
                        report.put("offlineCache", true)
                    } finally { root.deleteRecursively() }
                    body.length
                } }
            }
        }
        println(report)
    }
}
