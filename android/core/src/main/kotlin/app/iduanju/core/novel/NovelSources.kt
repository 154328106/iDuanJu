package app.iduanju.core.novel

import app.iduanju.core.util.NetGuard
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class NovelSource(val json: JSONObject) {
    val id: String get() = json.optString("bookSourceUrl")
    val name: String get() = json.optString("bookSourceName", id)
    val enabled: Boolean get() = json.optBoolean("enabled", true)
    fun rules(name: String): JSONObject = json.optJSONObject(name)
        ?: runCatching { JSONObject(json.optString(name)) }.getOrDefault(JSONObject())
}
data class NovelHit(val name: String, val author: String, val url: String, val cover: String = "")
data class NovelCategory(val title: String, val url: String)
data class NovelChapter(val title: String, val url: String)

/** Text-source subset of Legado: CSS/Jsoup rules, simple JSON paths, GET/POST and discovery. */
object NovelSources {
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).followRedirects(false).build()
    @Volatile var fixture: ((String) -> String)? = null

    fun import(text: String): List<NovelSource> {
        val trimmed = text.trim().removePrefix("\uFEFF")
        val array = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONArray().put(JSONObject(trimmed))
        return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { o ->
            if (o.optInt("bookSourceType", 0) != 0 || o.optString("bookSourceUrl").isBlank()) null else NovelSource(o)
        } }.distinctBy { it.id }
    }

    fun absolute(raw: String, base: String): String = if (raw.isBlank()) "" else URI(base).resolve(raw.trim()).toString()

    fun fetch(raw: String, source: NovelSource, key: String = "", page: Int = 1): String {
        if (raw.contains("@js:") || raw.contains("<js>")) error("此书源需要 JavaScript 规则，Android 尚不支持")
        val pieces = raw.split(Regex(",\\s*(?=\\{)"), limit = 2)
        val options = if (pieces.size == 2) JSONObject(pieces[1]) else JSONObject()
        val charset = options.optString("charset", "UTF-8")
        fun template(s: String) = s.replace("{{key}}", URLEncoder.encode(key, charset)).replace("{{page}}", page.toString())
        var address = absolute(template(pieces[0]), source.id)
        fixture?.let { return it(address) }
        val headers = source.json.optJSONObject("header") ?: runCatching { JSONObject(source.json.optString("header")) }.getOrDefault(JSONObject())
        val originalHost = URI(address).host
        repeat(6) {
            val uri = URI(address)
            require(uri.scheme in listOf("https", "http") && !uri.host.isNullOrEmpty() && !NetGuard.isIPLiteral(uri.host) && !NetGuard.resolvesToBlocked(uri.host)) { "书源地址无效或指向内网" }
            val req = Request.Builder().url(address).header("User-Agent", "Mozilla/5.0 iDuanJu")
            if (uri.host == originalHost) for (name in headers.keys()) req.header(name, headers.getString(name))
            if (it == 0 && options.optString("method").equals("POST", true)) {
                val body = template(options.optString("body"))
                req.post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            }
            http.newCall(req.build()).execute().use { response ->
                if (response.isRedirect) {
                    address = absolute(response.header("Location") ?: error("书源跳转缺少地址"), address)
                } else {
                    check(response.isSuccessful) { "书源 HTTP ${response.code}" }
                    val bytes = response.body?.byteStream()?.use { stream ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) { val n = stream.read(buffer); if (n < 0) break; require(out.size() + n <= 8 * 1024 * 1024) { "书源页面过大" }; out.write(buffer, 0, n) }
                        out.toByteArray()
                    } ?: error("书源空响应")
                    return String(bytes, java.nio.charset.Charset.forName(charset))
                }
            }
        }
        error("书源跳转次数过多")
    }

    fun categories(source: NovelSource): List<NovelCategory> {
        val raw = source.json.optString("exploreUrl").trim()
        if (raw.isEmpty()) return emptyList()
        require(!raw.startsWith("@js:") && !raw.startsWith("<js>")) { "此发现栏目需要 JavaScript，Android 尚不支持" }
        if (raw.startsWith("[")) {
            val a = JSONArray(raw)
            return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o ->
                o.optString("url").takeIf { it.isNotBlank() }?.let { NovelCategory(o.optString("title", "推荐"), it) }
            } }.distinctBy { it.url }
        }
        return raw.split("&&", "\n").filter { it.isNotBlank() }.map { s ->
            val p = s.trim().split("::", limit = 2)
            NovelCategory(if (p.size == 2) p[0] else "推荐", p.last())
        }.distinctBy { it.url }
    }

    private fun jsonNodes(value: Any, path: String): List<Any> {
        var current = listOf(value)
        for (token in path.removePrefix("$").trimStart('.').split('.').filter { it.isNotEmpty() }) {
            val name = token.substringBefore('[')
            val index = token.substringAfter('[', "").substringBefore(']')
            current = current.flatMap { node ->
                val v = if (name.isEmpty()) node else (node as? JSONObject)?.opt(name)
                when {
                    v == null || v == JSONObject.NULL -> emptyList()
                    index == "*" && v is JSONArray -> (0 until v.length()).map { v.get(it) }
                    index.toIntOrNull() != null && v is JSONArray -> listOfNotNull(v.opt(index.toInt()))
                    else -> listOf(v)
                }
            }
        }
        return current
    }

    private fun select(root: Element, rule: String): List<Element> {
        if (rule.isBlank()) return listOf(root)
        var current = listOf(root)
        for (part in rule.removePrefix("@css:").split('@').filter { it.isNotBlank() }) {
            val numbered = Regex("^(.*)\\.(-?\\d+)$").matchEntire(part)
            val selector = numbered?.groupValues?.get(1) ?: part
            val css = when {
                selector.startsWith("class.") -> "." + selector.removePrefix("class.")
                selector.startsWith("id.") -> "#" + selector.removePrefix("id.")
                selector.startsWith("tag.") -> selector.removePrefix("tag.")
                else -> selector
            }
            current = current.flatMap { it.select(css).toList() }
            if (numbered != null) { val i = numbered.groupValues[2].toInt(); current = listOfNotNull(current.getOrNull(if (i < 0) current.size + i else i)) }
        }
        return current
    }

    private fun nodes(content: Any, raw: String): List<Any> {
        require(!raw.contains("@js:") && !raw.contains("<js>") && !raw.startsWith("//")) { "此书源需要 JavaScript / XPath，Android 尚不支持" }
        val reverse = raw.startsWith('-'); val rule = raw.removePrefix("-")
        val result = if (rule.startsWith('$')) jsonNodes(content, rule) else select(content as? Element ?: Jsoup.parse(content.toString()), rule)
        return if (reverse) result.reversed() else result
    }

    private fun value(content: Any, raw: String): String {
        if (raw.isEmpty()) return ""
        val parts = raw.split("##")
        val rule = parts[0]
        require(!rule.contains("@js:") && !rule.contains("<js>")) { "此书源需要 JavaScript，Android 尚不支持" }
        var text = if (rule.startsWith('$')) jsonNodes(content, rule).joinToString("\n") else if (rule.startsWith("http://") || rule.startsWith("https://")) rule else {
            val at = rule.lastIndexOf('@')
            val attr = if (at >= 0) rule.substring(at + 1) else "text"
            val selector = if (at >= 0) rule.substring(0, at) else rule
            val root = content as? Element ?: Jsoup.parse(content.toString())
            select(root, selector).joinToString("\n") { el -> when (attr) { "text" -> el.text(); "html" -> el.html(); "ownText" -> el.ownText(); else -> el.attr(attr) } }
        }
        if (parts.size >= 2) text = text.replace(Regex(parts[1]), parts.getOrElse(2) { "" })
        return text.trim()
    }

    private fun document(text: String): Any = when { text.trimStart().startsWith('{') -> JSONObject(text); text.trimStart().startsWith('[') -> JSONArray(text); else -> Jsoup.parse(text) }

    fun search(source: NovelSource, key: String, page: Int = 1, category: NovelCategory? = null): List<NovelHit> {
        val raw = category?.url ?: source.json.optString("searchUrl")
        require(raw.isNotBlank()) { "书源没有搜索地址" }
        val text = fetch(raw, source, key, page)
        val rules = if (category != null && source.rules("ruleExplore").length() > 0) source.rules("ruleExplore") else source.rules("ruleSearch")
        val base = absolute(raw.substringBefore(",{").replace("{{key}}", URLEncoder.encode(key, "UTF-8")).replace("{{page}}", page.toString()), source.id)
        return nodes(document(text), rules.optString("bookList")).mapNotNull { node ->
            val name = value(node, rules.optString("name")); val url = value(node, rules.optString("bookUrl"))
            if (name.isBlank() || url.isBlank()) null else NovelHit(name, value(node, rules.optString("author")), absolute(url, base), absolute(value(node, rules.optString("coverUrl")), base))
        }.distinctBy { it.url }
    }

    fun chapters(source: NovelSource, bookURL: String): List<NovelChapter> {
        val detail = document(fetch(bookURL, source))
        val toc = value(detail, source.rules("ruleBookInfo").optString("tocUrl"))
        var url = if (toc.isBlank()) bookURL else absolute(toc, bookURL)
        val rules = source.rules("ruleToc"); val out = arrayListOf<NovelChapter>(); val seen = hashSetOf<String>()
        while (seen.size < 100 && seen.add(url)) {
            val doc = if (url == bookURL) detail else document(fetch(url, source))
            for (n in nodes(doc, rules.optString("chapterList"))) {
                val title = value(n, rules.optString("chapterName")); val address = value(n, rules.optString("chapterUrl"))
                if (title.isNotBlank() && address.isNotBlank()) out += NovelChapter(title, absolute(address, url))
            }
            val next = value(doc, rules.optString("nextTocUrl")); if (next.isBlank()) break
            url = absolute(next, url)
        }
        return out.distinctBy { it.url }
    }

    fun content(source: NovelSource, chapter: NovelChapter, nextChapter: String? = null): String {
        val rules = source.rules("ruleContent"); var url = chapter.url; val seen = hashSetOf<String>(); val parts = arrayListOf<String>()
        while (seen.size < 30 && seen.add(url)) {
            val doc = document(fetch(url, source))
            parts += cleanText(value(doc, rules.optString("content")))
            val next = value(doc, rules.optString("nextContentUrl")); if (next.isBlank()) break
            url = absolute(next, url); if (url == nextChapter) break
        }
        var text = parts.joinToString("\n\n")
        for (rule in rules.optString("replaceRegex").lines().filter { it.isNotBlank() }) {
            val p = rule.removePrefix("##").split("##"); text = text.replace(Regex(p[0]), p.getOrElse(1) { "" })
        }
        require(text.isNotBlank()) { "章节正文为空或书源规则不兼容" }
        return text
    }

    fun cleanText(raw: String): String = Jsoup.parse(raw.replace(Regex("(?i)<br\\s*/?>|</p>|</div>"), "\n")).wholeText()
        .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
}
