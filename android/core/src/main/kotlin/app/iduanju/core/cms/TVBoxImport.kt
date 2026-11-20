package app.iduanju.core.cms

import org.json.JSONObject

/** Imports HTTP CMS catalogs and standalone HTTPS live lists from TVBox. */
object TVBoxImport {
    data class Site(val name: String, val api: String, val adult: Boolean)
    data class Live(val name: String, val url: String)
    data class Config(val name: String, val sites: List<Site>, val skipped: Int, val lives: List<Live>)

    fun parse(text: String): Config {
        require(text.toByteArray(Charsets.UTF_8).size <= 4 shl 20) { "TVBox 配置超过 4 MB" }
        val root = try { JSONObject(text) } catch (_: Exception) { throw IllegalArgumentException("TVBox 配置不是 JSON 对象") }
        require(root.has("sites") || root.has("spider") || root.has("lives")) { "不是 TVBox 配置（缺少 sites / spider / lives）" }
        val entries = root.optJSONArray("sites") ?: org.json.JSONArray()
        require(entries.length() <= 5000) { "TVBox 配置站点超过 5000 个" }

        val sites = arrayListOf<Site>()
        val seen = hashSetOf<String>()
        var skipped = 0
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i)
            val type = entry?.opt("type")?.toString()?.toIntOrNull()
            val api = entry?.optString("api")?.trim().orEmpty()
            val name = entry?.optString("name")?.trim()?.take(80).orEmpty()
            if (type !in listOf(0, 1, 4) || (type == 4 && !entry?.optString("ext").isNullOrBlank()) || api.isEmpty()) { skipped++; continue }
            val normalized = runCatching { MacCMS.normalize(api) }.getOrNull()
            if (normalized == null || !seen.add(normalized)) { skipped++; continue }
            sites += Site(name.ifEmpty { runCatching { java.net.URI(normalized).host }.getOrNull() ?: "TVBox 站点 ${sites.size + 1}" }, normalized,
                entry!!.optBoolean("adult", false))
        }
        val playlists = root.optJSONArray("lives") ?: org.json.JSONArray()
        require(playlists.length() <= 5000) { "直播列表过大" }
        val lives = (0 until playlists.length()).mapNotNull { i ->
            val row = playlists.optJSONObject(i) ?: return@mapNotNull null
            val url = row.optString("url").trim()
            val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return@mapNotNull null
            if (uri.scheme != "https" || uri.host.isNullOrEmpty() || uri.userInfo != null || uri.port !in listOf(-1, 443) || app.iduanju.core.util.NetGuard.isIPLiteral(uri.host)) return@mapNotNull null
            Live(row.optString("name").ifBlank { uri.host }.take(80), url)
        }.distinctBy { it.url }
        require(sites.isNotEmpty() || lives.isNotEmpty()) {
            "没有可导入的 CMS API（type=0 / type=1 / 兼容的 type=4） 站点；JAR / CatVod / Python spider 不会执行"
        }
        return Config(root.optString("name").trim().ifEmpty { "TVBox 配置" }, sites, skipped, lives)
    }
}
