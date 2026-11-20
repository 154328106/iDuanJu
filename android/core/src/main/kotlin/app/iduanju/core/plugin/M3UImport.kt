package app.iduanju.core.plugin

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64

/**
 * In-app port of scripts/source-compat/m3u_to_idj.py: a reviewed, legal extended-M3U channel list becomes a static,
 * permission-scoped idj1 video bundle. Channel URLs are never fetched here, nothing from the list is executed and no
 * cookie is copied. Rejections match the script case by case (shared/legado-port/m3u-cases.json), and main.js is
 * byte-identical to the script's output, so the integrity hash is the same.
 */
object M3UImport {
    class Rejected(val code: String, message: String) : Exception(message)

    data class Channel(val title: String, val group: String, val cover: String?, val id: String, val url: String, val headers: Map<String, String>)

    private data class Meta(val title: String, val group: String, val cover: String?)

    private val messages = mapOf(
        "not_channel_list" to "不是扩展 M3U 频道列表（第一行必须是 #EXTM3U）",
        "hls_playlist" to "这是 HLS 播放清单（m3u8），不是频道列表",
        "missing_url" to "有频道缺少播放地址",
        "missing_name" to "#EXTINF 缺少频道名",
        "unsafe_option" to "不支持或不安全的 VLC 选项：",
        "drm_directive" to "包含 DRM / 认证 / 额外 HTTP 指令，需要单独审核的适配器",
        "url_before_extinf" to "播放地址前缺少 #EXTINF",
        "inline_headers" to "不支持地址里内联的 HTTP 头（|…），请改用 #EXTVLCOPT",
        "https_required" to "只支持 HTTPS 播放地址",
        "empty_list" to "频道列表为空或被截断",
        "url_chars" to "地址含空白、控制字符或反斜杠",
        "url_scheme" to "只支持不带账号密码的 HTTP(S) 地址",
        "url_port" to "非标准端口需要单独审核的适配器",
        "url_ambiguous_host" to "纯数字、末尾带点或非 ASCII 的主机名需要单独审核的适配器",
        "url_invalid_host" to "主机名无效",
        "url_local_host" to "不支持本地主机名",
        "url_signed" to "带签名或凭证参数的地址需要单独审核的适配器，不会保存凭证",
        "bad_plugin_id" to "插件 ID 必须是反向域名",
    )
    private fun reject(code: String, detail: String = ""): Nothing = throw Rejected(code, messages.getValue(code) + detail)

    /** True when the text claims to be an extended M3U, so the caller routes it here instead of the .idjplugin parser. */
    fun isChannelList(text: String) = text.trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("#EXTM3U")

    private val lineBreak = Regex("\r\n|[\n\r\u000B\u000C\u001C\u001D\u001E\u0085\u2028\u2029]")
    private val extinf = Regex("#EXTINF:(?:[^\",]|\"[^\"]*\")*,(.*)")
    // m3u_to_idj.py's str \w: Android's ICU regex rejects (?U), and ICU's own \w also matches combining marks.
    private val attribute = Regex("([\\p{L}\\p{N}_-]+)=\"([^\"]*)\"")

    fun parse(text: String, base: String? = null): List<Channel> {
        val body = text.trimStart('\uFEFF')
        val parts = body.split(lineBreak)
        val lines = if (body.isEmpty()) emptyList() else if (parts.last().isEmpty()) parts.dropLast(1) else parts
        if (lines.isEmpty() || lines[0].trim() != "#EXTM3U") reject("not_channel_list")
        if (lines.any { it.trim().startsWith("#EXT-X-", ignoreCase = true) }) reject("hls_playlist")
        val items = ArrayList<Channel>()
        var meta: Meta? = null
        var headers = LinkedHashMap<String, String>()
        for (raw in lines.drop(1)) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF:") -> {
                    if (meta != null) reject("missing_url")
                    val m = extinf.matchEntire(line)
                    val title = m?.groupValues?.get(1)?.trim()
                    if (m == null || title.isNullOrEmpty()) reject("missing_name")
                    val attrs = LinkedHashMap<String, String>()
                    attribute.findAll(line.substring(0, m.groups[1]!!.range.first)).forEach { attrs[it.groupValues[1]] = it.groupValues[2] }
                    val group = attrs["group-title"]?.takeIf { it.isNotEmpty() } ?: "全部"
                    val cover = attrs["tvg-logo"]?.takeIf { it.isNotEmpty() }?.let { publicUrl(it, base).first }
                    meta = Meta(title, group, cover)
                    headers = LinkedHashMap()
                }
                line.startsWith("#EXTGRP:") && meta != null -> meta = meta.copy(group = line.substringAfter(':').trim().ifEmpty { "全部" })
                line.startsWith("#EXTVLCOPT:") -> {
                    val option = line.substring("#EXTVLCOPT:".length)
                    val key = option.substringBefore('=')
                    val value = option.substringAfter('=', "")
                    val mapped = when (key.lowercase()) { "http-referrer" -> "Referer"; "http-user-agent" -> "User-Agent"; else -> null }
                    if (meta == null || mapped == null || value.length > 500 || value.any { it.code < 32 || it.code == 127 }) reject("unsafe_option", key)
                    if (mapped == "Referer") publicUrl(value, null)
                    headers[mapped] = value
                }
                line.startsWith("#KODIPROP:") || line.startsWith("#EXTHTTP:") -> reject("drm_directive")
                line.startsWith("#") -> {}
                else -> {
                    val m = meta ?: reject("url_before_extinf")
                    if ('|' in line) reject("inline_headers")
                    val url = publicUrl(line, base).first
                    if (split(url).scheme != "https") reject("https_required")
                    items += Channel(m.title, m.group, m.cover, (items.size + 1).toString(), url, headers)
                    meta = null
                }
            }
        }
        if (meta != null || items.isEmpty()) reject("empty_list")
        return items
    }

    private class Parts(val scheme: String, val host: String?, val portText: String?, val credentials: Boolean, val query: String, val fragment: String)
    private val schemePrefix = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

    /** urllib.parse.urlsplit, reduced to what [publicUrl] reads. */
    private fun split(url: String): Parts {
        var rest = url
        var scheme = ""
        schemePrefix.find(url)?.let { scheme = it.groupValues[1].lowercase(); rest = url.substring(it.value.length) }
        var netloc: String? = null
        if (rest.startsWith("//")) {
            val end = rest.indexOfAny(charArrayOf('/', '?', '#'), 2).let { if (it < 0) rest.length else it }
            netloc = rest.substring(2, end)
            rest = rest.substring(end)
        }
        val fragment = rest.substringAfter('#', "")
        val query = rest.substringBefore('#').substringAfter('?', "")
        if (netloc == null) return Parts(scheme, null, null, false, query, fragment)
        val at = netloc.lastIndexOf('@')
        val user = if (at >= 0) netloc.substring(0, at) else null
        val credentials = user != null && (user.substringBefore(':').isNotEmpty() || user.substringAfter(':', "").isNotEmpty())
        val hostinfo = netloc.substring(at + 1)
        val host: String
        val port: String
        if ('[' in hostinfo) {
            val bracketed = hostinfo.substringAfter('[')
            host = bracketed.substringBefore(']')
            port = bracketed.substringAfter(']', "").substringAfter(':', "")
        } else {
            host = hostinfo.substringBefore(':')
            port = hostinfo.substringAfter(':', "")
        }
        return Parts(scheme, host.lowercase().ifEmpty { null }, port.ifEmpty { null }, credentials, query, fragment)
    }

    private fun join(base: String?, raw: String): String {
        if (base.isNullOrEmpty() || schemePrefix.containsMatchIn(raw)) return raw
        return runCatching {
            val b = URI(base)
            (if (b.rawAuthority != null && b.rawPath.isNullOrEmpty()) URI("$base/") else b).resolve(raw).toString()
        }.getOrDefault(raw)
    }

    private val unsafeChar = Regex("[\\x00-\\x20\\x7f\\\\]")
    private val numericLabel = Regex("(?i)0x[0-9a-f]+|[0-9]+")
    private val dnsName = Regex("(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
    private val sensitiveKey = Regex("(?i)token|access[_-]?token|auth|auth_key|authorization|key|api[_-]?key|password|pass|secret|sign|signature|sig|cookie|x-amz-.+|x-goog-.+")

    /** The script's `public_url`: the joined URL and its lowercased host, or [Rejected] with the script's reason. */
    fun publicUrl(raw: String, base: String?): Pair<String, String> {
        val url = join(base, raw.trim())
        if (unsafeChar.containsMatchIn(url)) reject("url_chars")
        val u = split(url)
        val host = u.host
        if ((u.scheme != "https" && u.scheme != "http") || host == null || u.credentials) reject("url_scheme")
        u.portText?.let { text ->
            val port = text.takeIf { t -> t.all { it in '0'..'9' } }?.toIntOrNull()?.takeIf { it in 0..65535 } ?: reject("url_port")
            if (port != 0 && port != (if (u.scheme == "https") 443 else 80)) reject("url_port")
        }
        if (host.any { it.code > 127 } || host.endsWith(".") || host.split('.').all { numericLabel.matches(it) }) reject("url_ambiguous_host")
        if (!dnsName.matches(host)) reject("url_invalid_host")
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".localhost") || host.endsWith(".internal")) reject("url_local_host")
        val keys = (u.query.split('&') + u.fragment.split('&')).filter { it.isNotEmpty() }.map { decode(it.substringBefore('=')) }
        if (keys.any { sensitiveKey.matches(it) }) reject("url_signed")
        return url to host
    }

    private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private val pluginId = Regex("[a-z0-9]+(?:\\.[a-z0-9-]+){2,}")

    /** The .idjplugin text for [channels]; [author] defaults to the script's label so the parity cases stay exact. */
    fun bundle(channels: List<Channel>, name: String, id: String, author: String = "iDuanJu compatibility test"): String {
        if (!pluginId.matches(id)) reject("bad_plugin_id")
        val domains = channels.flatMap { listOfNotNull(it.url, it.cover) }.map { publicUrl(it, null).second }.toSortedSet().toList()
        val source = mainJs(channels)
        val hash = "sha256-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(source.toByteArray()))
        val manifest = JSONObject().put("schema", 1).put("id", id).put("name", name).put("version", "1.0.0")
            .put("author", JSONObject().put("name", author)).put("type", "video").put("adult", false).put("minAppVersion", "1.5")
            .put("entry", "main.js").put("permissions", JSONObject().put("http", JSONArray(domains)).put("media", JSONArray(domains)))
            .put("capabilities", JSONArray(listOf("home", "category", "search", "detail", "play")))
            .put("integrity", JSONObject().put("files", JSONObject().put("main.js", hash)))
        return JSONObject().put("manifest", manifest).put("files", JSONObject().put("main.js", source)).toString()
    }

    /** `json.dumps(items, ensure_ascii=False)` followed by the script's fixed module text. */
    fun mainJs(channels: List<Channel>): String = buildString {
        append("const channels = [")
        channels.forEachIndexed { i, c ->
            if (i > 0) append(", ")
            append("{\"title\": ").append(quote(c.title)).append(", \"group\": ").append(quote(c.group))
            c.cover?.let { append(", \"cover\": ").append(quote(it)) }
            append(", \"id\": ").append(quote(c.id)).append(", \"url\": ").append(quote(c.url)).append(", \"headers\": {")
            c.headers.entries.forEachIndexed { j, (k, v) -> if (j > 0) append(", "); append(quote(k)).append(": ").append(quote(v)) }
            append("}}")
        }
        append("];\n").append(module)
    }

    private fun quote(s: String) = buildString {
        append('"')
        for (ch in s) when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else -> if (ch < ' ') append("\\u").append(ch.code.toString(16).padStart(4, '0')) else append(ch)
        }
        append('"')
    }

    private val module = listOf(
        "",
        "function find(id) { const c=channels.find(x => x.id===String(id)); if (!c) throw host.error('E_NOT_FOUND','频道不存在'); return c; }",
        "function page(items, n) { const size=50, p=Math.max(1,Number(n)||1); return {items:items.slice((p-1)*size,p*size),page:p,hasMore:p*size<items.length}; }",
        "export default {",
        " home() { return {categories:Array.from(new Set(channels.map(x=>x.group))).map(x=>({id:x,name:x})),items:channels.slice(0,50)}; },",
        " category({id,page:p}) { return page(channels.filter(x=>!id||x.group===id),p); },",
        " search({keyword,page:p}) { return page(channels.filter(x=>x.title.toLowerCase().includes(String(keyword||'').toLowerCase())),p); },",
        " detail({id}) { const c=find(id); return {title:c.title,cover:c.cover,desc:'M3U 静态导入；原始内容归其权利人所有。',lines:[{name:c.group,episodes:[{id:c.id,name:c.title}]}]}; },",
        " play({episodeId}) { const c=find(episodeId); return {streams:[{url:c.url,headers:c.headers,format:/\\.m3u8(?:[?#]|\$)/i.test(c.url)?'hls':'mp4'}]}; }",
        "};",
        "",
    ).joinToString("\n")

    /** Stable plugin id for an imported list: re-importing the same link (or the same file content) updates it in place. */
    fun importId(seed: String) = "com.idj.m3u." + MessageDigest.getInstance("SHA-256").digest(seed.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)

    /** Display name from a list URL's last path segment, without its extension. */
    fun nameFromUrl(url: String): String? {
        val path = runCatching { URI(url).rawPath }.getOrNull() ?: return null
        return decode(path.trimEnd('/').substringAfterLast('/').substringBeforeLast('.')).trim().ifEmpty { null }
    }
}