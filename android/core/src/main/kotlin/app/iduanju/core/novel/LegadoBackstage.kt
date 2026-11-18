/* Port of the request contract in legado-E help/http/BackstageWebView.kt and
 * model/analyzeRule/AnalyzeUrl.kt (useWebView/webJs/webViewDelayTime/sourceRegex) at
 * 8b87c5aba4df91c39a3a0939a68a1180b9f2ee1c, Copyright Legado contributors, GPL-3.0.
 * Adapted for iDuanJu: platform WebView is injected; no JS bridge objects, no TLS-error proceed,
 * private-network navigation blocked, cookie/authorization only for the source's own origin. */
package app.iduanju.core.novel

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class BackstageRequest(
    val url: String,
    val headers: Map<String, String>,
    val javaScript: String?,
    val sourceRegex: String?,
    val delayMs: Long,
    val timeoutMs: Long = 60_000L,
)

/** Platform implementation (Android WebView). Blocking; never call on the main thread. Interrupt cancels. */
fun interface LegadoBackstage { fun load(request: BackstageRequest): NovelSources.PageResponse }

object LegadoWebView {
    @Volatile var backstage: LegadoBackstage? = null
    /** Upstream BackstageWebView.JS */
    const val DEFAULT_JS = "document.documentElement.outerHTML"
    /** Upstream EvalJsRunnable: first try after 100+delay ms, then these intervals, give up after 30 retries. */
    val retryIntervals = listOf(200L, 400L, 600L, 800L, 1000L)
    const val MAX_RETRY = 30
    /** Resource names the page loaded plus current media element sources; matched against sourceRegex in native code. */
    const val SNIFF_JS = "(function(){var a=[];try{performance.getEntriesByType('resource').forEach(function(e){a.push(e.name)})}catch(e){}" +
        "document.querySelectorAll('video,audio,source').forEach(function(e){if(e.currentSrc)a.push(e.currentSrc);if(e.src)a.push(e.src)});return JSON.stringify(a)})()"

    /** Upstream Options.useWebView(): null, "", false, "false" are false; any other value (even “true”) is true. */
    fun useWebView(value: Any?): Boolean = when (value) {
        null, JSONObject.NULL -> false
        is Boolean -> value
        else -> value.toString().let { it.isNotEmpty() && it != "false" }
    }

    /** WebView evaluateJavascript result (JSON encoded) -> body, or null meaning "not ready, retry". */
    fun evalResult(raw: String?): String? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty() || t == "null" || t == "undefined") return null
        val v = if (t.startsWith("\"")) runCatching { JSONTokener(t).nextValue() as? String }.getOrNull() ?: t.trim('"') else t
        return v.takeIf { it.isNotEmpty() && it != "null" }
    }

    /** Upstream SnifferWebClient uses String.matches (whole URL). Invalid regex never matches. */
    fun matches(regex: String, url: String): Boolean = runCatching { Regex(regex).matches(url) }.getOrDefault(false)

    fun firstSniffed(regex: String, sniffJson: String?): String? {
        val text = evalResult(sniffJson) ?: return null
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return null
        return (0 until arr.length()).map { arr.optString(it) }.firstOrNull { it.startsWith("http") && matches(regex, it) }
    }

    /** Lenient URL options like `{webView:“true”}` written with typographic quotes in real sources. */
    fun options(raw: String): JSONObject {
        val normalized = raw.replace('“', '"').replace('”', '"')
        return runCatching { JSONObject(normalized) }.getOrDefault(JSONObject())
    }
}
