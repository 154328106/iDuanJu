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
data class NovelHit(val name: String, val author: String, val url: String, val cover: String = "", val kind: String = "", val latest: String = "")
data class NovelCategory(val title: String, val url: String, val group: String = "推荐", val action: String? = null) {
    val id get() = "$group|$title|$url|${action.orEmpty()}"
}
data class NovelChapter(val title: String, val url: String)

/** Text-source subset of Legado: CSS/Jsoup rules, simple JSON paths, GET/POST and discovery. */
object NovelSources {
    fun configureStorage(root: java.io.File) { NovelScript.configure(root) }
    const val userAgent = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).followRedirects(false).build()
    @Volatile var fixture: ((String) -> String)? = null

    fun import(text: String): List<NovelSource> {
        val trimmed = text.trim().removePrefix("\uFEFF")
        val array = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONArray().put(JSONObject(trimmed))
        return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { o ->
            if (o.optInt("bookSourceType", 0) != 0 || o.optString("bookSourceUrl").isBlank()) null else NovelSource(o)
        } }.distinctBy { it.id }
    }

    fun absolute(raw: String, base: String): String {
        if (raw.isBlank()) return ""
        val parts = raw.trim().split(Regex(",\\s*(?=\\{)"), limit = 2)
        val baseURL = base.split(Regex(",\\s*(?=\\{)"), limit = 2)[0].substringBefore('#')
        return URI(baseURL).resolve(parts[0].replace(" ", "%20")).toString() + if(parts.size == 2) "," + parts[1] else ""
    }

    private fun script(code: String) = code.trim().removePrefix("@js:").removePrefix("<js>").removeSuffix("</js>")
    private fun template(raw: String, source: NovelSource, key: String, page: Int, charset: String): String {
        var text = raw
        // Balanced templates may contain nested object literals or braces inside strings.
        var at = 0
        while (true) {
            val start = text.indexOf("{{", at); if(start < 0) break
            var i = start + 2; var depth = 0; var quote: Char? = null
            while(i < text.length - 1) {
                val ch = text[i]
                if(quote != null) { if(ch == '\\') i++ else if(ch == quote) quote = null }
                else if(ch in "\"'`") quote = ch
                else if(ch == '{') depth++
                else if(ch == '}') { if(depth == 0 && text[i + 1] == '}') break; depth-- }
                i++
            }
            require(i < text.length - 1) { "网址模板没有结束" }
            val expr = text.substring(start + 2, i).trim()
            val value = when(expr) { "key" -> URLEncoder.encode(key, charset); "page" -> page.toString()
                else -> NovelScript(source, source.id, key, page).eval(expr)?.toString().orEmpty() }
            text = text.substring(0,start) + value + text.substring(i+2); at = start + value.length
        }
        return Regex("<([^<>]*,[^<>]*)>").replace(text) { m -> m.groupValues[1].split(',').let { it[minOf(page-1,it.lastIndex).coerceAtLeast(0)] } }
    }

    data class PageResponse(val text: String, val url: String, val headers: Map<String,String> = emptyMap())

    fun fetch(raw: String, source: NovelSource, key: String = "", page: Int = 1): String = fetchResponse(raw,source,key,page).text

    fun fetchResponse(raw: String, source: NovelSource, key: String = "", page: Int = 1): PageResponse {
        var rule = raw.trim()
        if(rule.startsWith("@js:") || rule.startsWith("<js>")) rule = NovelScript(source,source.id,key,page).eval(script(rule))?.toString().orEmpty()
        rule = template(rule,source,key,page,"UTF-8")
        val pieces = rule.split(Regex(",\\s*(?=\\{)"), limit = 2)
        val options = if (pieces.size == 2) JSONObject(pieces[1]) else JSONObject()
        val charset = options.optString("charset", "UTF-8")
        fun fill(s: String) = template(s,source,key,page,charset)
        var address = absolute(fill(pieces[0]), source.id)
        if(address.startsWith("data:")) {
            val data = address.substringAfter(',')
            val bytes = if(address.substringBefore(',').endsWith(";base64")) java.util.Base64.getDecoder().decode(data) else java.net.URLDecoder.decode(data.replace("+","%2B"),"UTF-8").toByteArray()
            return PageResponse(if(options.optString("type").isNotBlank())bytes.joinToString(""){"%02x".format(it)} else String(bytes),address)
        }
        fixture?.let { return PageResponse(it(address),address) }
        val headers = source.json.optJSONObject("header") ?: runCatching { JSONObject(source.json.optString("header")) }.getOrDefault(JSONObject())
        val originalHost = URI(address).host
        repeat(6) {
            val uri = URI(address)
            require(uri.scheme in listOf("https", "http") && !uri.host.isNullOrEmpty() && !NetGuard.isIPLiteral(uri.host) && !NetGuard.resolvesToBlocked(uri.host)) { "书源地址无效或指向内网" }
            val req = Request.Builder().url(address).header("User-Agent", userAgent)
            if (uri.host == originalHost) for (name in headers.keys()) req.header(name, headers.getString(name))
            if (uri.host == originalHost) options.optJSONObject("headers")?.let { extra -> for(name in extra.keys()) req.header(name,extra.get(name).toString()) }
            if (it == 0 && options.optString("method").equals("POST", true)) {
                val body = fill(options.optString("body"))
                val ct = if(body.trimStart().startsWith('{')) "application/json; charset=utf-8" else "application/x-www-form-urlencoded; charset=$charset"
                req.post(body.toRequestBody(ct.toMediaType()))
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
                    val text = if(options.optString("type").isNotBlank())bytes.joinToString(""){"%02x".format(it)} else String(bytes, java.nio.charset.Charset.forName(charset))
                    return PageResponse(text,address,response.headers.names().associateWith { response.header(it).orEmpty() })
                }
            }
        }
        error("书源跳转次数过多")
    }

    fun categories(source: NovelSource): List<NovelCategory> {
        var raw = source.json.optString("exploreUrl").trim()
        if (raw.isEmpty()) return emptyList()
        if(raw.startsWith("@js:") || raw.startsWith("<js>")) raw = NovelScript(source,source.id).eval(script(raw))?.toString().orEmpty()
        return parseCategories(raw)
    }

    fun categoryAction(source: NovelSource, category: NovelCategory) {
        category.action?.let { NovelScript(source,source.id).eval(it) }
    }

    fun parseCategories(raw: String): List<NovelCategory> {
        var group = "推荐"
        val out = arrayListOf<NovelCategory>()
        fun add(title: String, url: String, action: String? = null) {
            val t = title.trim(); val u = url.trim(); if(t.isEmpty())return
            if(!action.isNullOrBlank())out += NovelCategory(t,"",group,action)
            else if(u.isBlank())group=t
            else out += NovelCategory(t,u,group)
        }
        if (raw.startsWith("[")) {
            val a = JSONArray(raw)
            for(i in 0 until a.length()) a.optJSONObject(i)?.let { o ->
                add(o.optString("title"),o.optString("url").takeUnless { it=="null" }.orEmpty(),if(o.optString("type")=="button")o.optString("action") else null)
            }
        } else for(line in raw.split("&&","\n").filter { it.isNotBlank() }) {
            val p=line.trim().split("::",limit=2)
            if(p.size==2)add(p[0],p[1])
            else if(p[0].startsWith("http")||p[0].startsWith('/'))add("推荐",p[0]) else group=p[0]
        }
        return out.distinctBy { it.id }
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

    private fun nodes(content: Any, raw: String, source: NovelSource, base: String, key: String = "", page: Int = 1): List<Any> {
        if(raw.startsWith("<js>") && raw.contains("</js>")) {
            val end=raw.indexOf("</js>");val value=NovelScript(source,base,key,page).eval(raw.substring(4,end),content.toString()) ?: return emptyList()
            val tail=raw.substring(end+5).trim()
            return if(tail.isEmpty()) if(value is JSONArray)(0 until value.length()).map{value.get(it)} else listOf(value)
                else nodes(if(value is String)document(value) else value,tail,source,base,key,page)
        }
        if(raw.startsWith("@js:") || raw.startsWith("<js>")) {
            val result = NovelScript(source,base,key,page).eval(script(raw), content.toString())
            val list = if(result is String && result.trimStart().startsWith('['))JSONArray(result) else result
            return if(list is JSONArray)(0 until list.length()).map { val v=list.get(it);if(v is String)document(v) else v } else listOfNotNull(list)
        }
        val reverse = raw.startsWith('-'); val rule = raw.removePrefix("-")
        val root=content as? Element ?: Jsoup.parse(content.toString())
        val result = if (rule.startsWith('$') || content is JSONObject || content is JSONArray) jsonNodes(content, rule).flatMap { if(it is JSONArray)(0 until it.length()).map{n->it.get(n)} else listOf(it) } else if(rule.startsWith("//") || rule.startsWith("/")) root.selectXpath(rule).toList()
            else if(rule.contains("||")) rule.split("||").asSequence().map{select(root,it)}.firstOrNull{it.isNotEmpty()}?:emptyList()
            else select(root, rule)
        return if (reverse) result.reversed() else result
    }

    internal fun ruleValue(content: Any, raw: String, source: NovelSource, base: String, key: String = "", page: Int = 1): String {
        if (raw.isEmpty()) return ""
        val put = Regex("@put:(\\{[^}]*})").find(raw)
        if (put != null) {
            val vars=JSONObject(put.groupValues[1])
            for(k in vars.keys()) NovelScript.setVariable(source.id,k,ruleValue(content,vars.getString(k),source,base,key,page))
            return ruleValue(content,raw.removeRange(put.range),source,base,key,page)
        }
        val jsAt = raw.indexOf("@js:").takeIf{it>=0} ?: raw.indexOf("<js>")
        if(jsAt>=0) {
            val prefix=raw.substring(0,jsAt)
            val input=if(prefix.isBlank())content.toString() else ruleValue(content,prefix,source,base,key,page)
            val code=Regex("\\{\\{(.*?)}}",RegexOption.DOT_MATCHES_ALL).replace(script(raw.substring(jsAt))) { m ->
                ruleValue(content,m.groupValues[1],source,base,key,page).replace("\\","\\\\").replace("'","\\'").replace("\"","\\\"").replace("\n","\\n")
            }
            return NovelScript(source,base,key,page).eval(code,input)?.toString().orEmpty()
        }
        if(raw.contains("{{"))return Regex("\\{\\{(.*?)}}",RegexOption.DOT_MATCHES_ALL).replace(raw){m ->
            val expr=m.groupValues[1];if(expr.startsWith('$')||expr.startsWith('@'))ruleValue(content,expr,source,base,key,page)
            else NovelScript(source,base,key,page).eval(expr,content.toString())?.toString().orEmpty()
        }
        if(raw.contains("||")) return raw.split("||").asSequence().map{ruleValue(content,it,source,base,key,page)}.firstOrNull{it.isNotBlank()}.orEmpty()
        val parts = raw.split("##")
        val rule = parts[0]
        var text = if (rule.startsWith('$') || content is JSONObject || content is JSONArray) jsonNodes(if(content is String)document(content) else content, rule).joinToString("\n") else if (rule.startsWith("http://") || rule.startsWith("https://")) rule
        else if(rule.startsWith('/') || rule.startsWith("@xpath:")) {
            val root=content as? Element ?: Jsoup.parse(content.toString())
            val doc=org.jsoup.helper.W3CDom().fromJsoup(Jsoup.parse(root.outerHtml()))
            val xpath=javax.xml.xpath.XPathFactory.newInstance().newXPath()
            xpath.evaluate(rule.removePrefix("@xpath:"), doc, javax.xml.xpath.XPathConstants.NODESET).let { list ->
                val ns=list as org.w3c.dom.NodeList;(0 until ns.length).joinToString("\n"){ns.item(it).textContent}
            }
        } else if(rule in listOf("text","ownText","html","href","src","value")) {
            val root=content as? Element ?: Jsoup.parse(content.toString());when(rule){"ownText"->root.ownText();"html"->root.html();"text"->root.text();else->root.attr(rule)}
        } else {
            val at = rule.lastIndexOf('@')
            val attr = if (at >= 0) rule.substring(at + 1) else "text"
            val selector = if (at >= 0) rule.substring(0, at) else rule
            val root = content as? Element ?: Jsoup.parse(content.toString())
            select(root, selector).joinToString("\n") { el -> when (attr) { "text" -> el.text(); "html" -> el.html(); "ownText" -> el.ownText(); else -> el.attr(attr) } }
        }
        if (parts.size >= 2) {
            val regex=Regex(parts[1]); val replacement=parts.getOrElse(2){""}
            text = if(raw.endsWith("###"))regex.find(text)?.let { regex.replace(it.value,replacement) }.orEmpty() else regex.replace(text,replacement)
        }
        return text.trim()
    }

    private fun document(text: String): Any = when {
        text.trimStart().startsWith('{') -> JSONObject(text)
        text.trimStart().startsWith('[') -> JSONArray(text)
        Regex("<[A-Za-z!/]").containsMatchIn(text) -> Jsoup.parse(text)
        else -> text
    }

    fun search(source: NovelSource, key: String, page: Int = 1, category: NovelCategory? = null): List<NovelHit> {
        val raw = category?.url ?: source.json.optString("searchUrl")
        require(raw.isNotBlank()) { "书源没有搜索地址" }
        val response = fetchResponse(raw, source, key, page)
        val rules = JSONObject(source.rules("ruleSearch").toString())
        if(category!=null)source.rules("ruleExplore").let { extra -> for(k in extra.keys())if(extra.optString(k).isNotBlank())rules.put(k,extra.get(k)) }
        require(listOf("bookList","name","bookUrl").all{rules.optString(it).isNotBlank()}){"书源缺少列表、书名或详情地址规则"}
        val base = response.url
        return nodes(document(response.text), rules.optString("bookList"),source,base,key,page).mapNotNull { node ->
            fun v(k:String)=ruleValue(node,rules.optString(k),source,base,key,page)
            val name = v("name"); val url = v("bookUrl")
            if (name.isBlank() || url.isBlank()) null else NovelHit(name, v("author"), absolute(url, base), runCatching{absolute(v("coverUrl"),base)}.getOrDefault(""),v("kind"),v("lastChapter"))
        }.distinctBy { it.url }
    }

    fun chapters(source: NovelSource, bookURL: String): List<NovelChapter> = NovelScript.withBook(bookURL) {
        var detail:Any = document(fetch(bookURL, source))
        val info=source.rules("ruleBookInfo")
        if(info.optString("init").isNotBlank()) detail=nodes(detail,info.getString("init"),source,bookURL).firstOrNull() ?: detail
        // Book-info name rules often set variables required by TOC/content scripts.
        if(info.optString("name").isNotBlank())ruleValue(detail,info.getString("name"),source,bookURL)
        val toc = ruleValue(detail, info.optString("tocUrl"),source,bookURL)
        var url = if (toc.isBlank()) bookURL else absolute(toc, bookURL)
        val rules = source.rules("ruleToc"); val out = arrayListOf<NovelChapter>(); val seen = hashSetOf<String>();val queue=ArrayDeque(listOf(url))
        while (seen.size < 100 && queue.isNotEmpty()) {
            url=queue.removeFirst();if(!seen.add(url))continue
            val doc = if (url == bookURL) detail else document(fetch(url, source))
            for (n in nodes(doc, rules.optString("chapterList"),source,url)) {
                val title = ruleValue(n, rules.optString("chapterName"),source,url); val address = ruleValue(n, rules.optString("chapterUrl"),source,url)
                if (title.isNotBlank() && address.isNotBlank()) out += NovelChapter(title, absolute(address, url))
            }
            val next = ruleValue(doc, rules.optString("nextTocUrl"),source,url)
            for(link in next.lines().filter{it.isNotBlank()}){val target=absolute(link,url);if(target !in seen && target !in queue)queue.add(target)}
        }
        out.distinctBy { it.url }
    }

    fun content(source: NovelSource, chapter: NovelChapter, nextChapter: String? = null, bookURL: String? = null): String = NovelScript.withBook(bookURL) {
        val rules = source.rules("ruleContent"); var url = chapter.url; val seen = hashSetOf<String>(); val parts = arrayListOf<String>(); var imageOnly = false
        while (seen.size < 30 && seen.add(url)) {
            val doc = document(fetch(url, source))
            val raw = ruleValue(doc, rules.optString("content"),source,url)
            val cleaned = cleanText(raw)
            imageOnly = imageOnly || (cleaned.isBlank() && Regex("<img\\b",RegexOption.IGNORE_CASE).containsMatchIn(raw))
            parts += cleaned
            val next = ruleValue(doc, rules.optString("nextContentUrl"),source,url); if (next.isBlank()) break
            url = absolute(next, url); if (url == nextChapter) break
        }
        var text = parts.joinToString("\n\n")
        for (rule in rules.optString("replaceRegex").lines().filter { it.isNotBlank() }) {
            val p = rule.removePrefix("##").split("##"); text = text.replace(Regex(p[0]), p.getOrElse(1) { "" })
        }
        require(text.isNotBlank()) { if(imageOnly) "本章是图片页，请换文字版书源" else "章节正文为空或书源规则不兼容" }
        text
    }

    fun cleanText(raw: String): String = Jsoup.parse(raw.replace(Regex("(?i)<br\\s*/?>|</p>|</div>"), "\n")).wholeText()
        .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
}
