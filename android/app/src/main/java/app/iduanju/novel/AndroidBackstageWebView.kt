/* Android port of legado-E help/http/BackstageWebView.kt at 8b87c5aba4df91c39a3a0939a68a1180b9f2ee1c
 * (Copyright Legado contributors, GPL-3.0): off-screen WebView, main-thread only, evaluate webJs after
 * 100+delay ms with the upstream retry ladder (<=30 retries), or sniff resources with sourceRegex.
 * Adapted: no addJavascriptInterface bridge, TLS errors are NOT proceeded, private-network
 * navigation/subresources are blocked, file/content access off, interrupt cancels and destroys. */
package app.iduanju.novel

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import app.iduanju.core.novel.BackstageRequest
import app.iduanju.core.novel.LegadoBackstage
import app.iduanju.core.novel.LegadoWebView
import app.iduanju.core.novel.NovelSources
import app.iduanju.core.util.NetGuard
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AndroidBackstageWebView(context: Context) : LegadoBackstage {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** Main-thread safe: scheme, credentials, IP literals and localhost names only (no DNS). */
    private fun allowedCheap(url: String): Boolean = runCatching {
        val u = URI(url); val host = u.host ?: return false
        val h = host.lowercase()
        u.scheme in listOf("http", "https") && u.userInfo == null && h != "localhost" && !h.endsWith(".localhost") && !h.endsWith(".local") &&
            !(NetGuard.isIPLiteral(host) && NetGuard.isBlockedIP(host.trim('[', ']')))
    }.getOrDefault(false)
    /** WebView network thread: additionally resolves the host (DNS rebinding to private ranges blocked). */
    private fun allowed(url: String): Boolean = allowedCheap(url) && runCatching { val h = URI(url).host; NetGuard.isIPLiteral(h) || !NetGuard.resolvesToBlocked(h) }.getOrDefault(false)

    override fun load(request: BackstageRequest): NovelSources.PageResponse {
        check(Looper.myLooper() != Looper.getMainLooper()) { "WebView解析不能阻塞主线程" }
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<NovelSources.PageResponse>?>(null)
        val holder = arrayOfNulls<WebView>(1)
        fun finish(r: Result<NovelSources.PageResponse>) {
            if (!result.compareAndSet(null, r)) return
            latch.countDown()
            main.post { holder[0]?.let { it.stopLoading(); it.webViewClient = WebViewClient(); it.destroy() }; holder[0] = null }
        }
        main.post {
            try {
                val web = create(request, ::finish, { result.get() != null }); holder[0] = web
                if (result.get() == null) web.loadUrl(request.url, request.headers)
            } catch (e: Throwable) { finish(Result.failure(e)) }
        }
        try {
            if (!latch.await(request.timeoutMs, TimeUnit.MILLISECONDS)) finish(Result.failure(IllegalStateException("WebView 解析超时（${request.timeoutMs / 1000}秒）")))
        } catch (e: InterruptedException) {
            finish(Result.failure(e)); Thread.currentThread().interrupt(); throw e
        }
        return result.get()!!.getOrThrow()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(request: BackstageRequest, finish: (Result<NovelSources.PageResponse>) -> Unit, done: () -> Boolean): WebView {
        val web = WebView(app)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            blockNetworkImage = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
            request.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.let { userAgentString = it.value }
        }
        require(allowedCheap(request.url)) { "WebView 页面地址不允许或指向内网" }
        val regex = request.sourceRegex
        web.webViewClient = object : WebViewClient() {
            private var retry = 0
            private var scheduled: Runnable? = null
            override fun shouldOverrideUrlLoading(view: WebView, r: WebResourceRequest): Boolean = !allowedCheap(r.url.toString())
            override fun shouldInterceptRequest(view: WebView, r: WebResourceRequest): WebResourceResponse? {
                val url = r.url.toString()
                if (url.startsWith("http") && !allowed(url)) return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                if (regex != null && url.startsWith("http") && LegadoWebView.matches(regex, url)) {
                    // Upstream SnifferWebClient: the matched resource URL is the response body.
                    finish(Result.success(NovelSources.PageResponse(url, request.url)))
                    return WebResourceResponse("text/plain", "utf-8", 204, "Captured", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.cancel(); finish(Result.failure(IllegalStateException("WebView TLS 证书错误，未忽略（${error?.primaryError}）")))
            }
            override fun onReceivedHttpError(view: WebView, r: WebResourceRequest, response: WebResourceResponse) {
                if (r.isForMainFrame && response.statusCode >= 400) finish(Result.failure(IllegalStateException("WebView 页面 HTTP ${response.statusCode}")))
            }
            override fun onReceivedError(view: WebView, r: WebResourceRequest, error: WebResourceError) {
                if (r.isForMainFrame) finish(Result.failure(IllegalStateException("WebView 页面加载失败：${error.description}")))
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (done()) return
                scheduled?.let { main.removeCallbacks(it) }
                val js = request.javaScript
                if (regex != null) {
                    if (!js.isNullOrEmpty()) main.postDelayed({ if (!done()) view.evaluateJavascript(js, null) }, 100L + request.delayMs)
                    val sniff = object : Runnable { override fun run() {
                        if (done()) return
                        view.evaluateJavascript(LegadoWebView.SNIFF_JS) { v ->
                            LegadoWebView.firstSniffed(regex, v)?.let { finish(Result.success(NovelSources.PageResponse(it, request.url))); return@evaluateJavascript }
                            if (!done()) main.postDelayed(this, 500L)
                        }
                    } }
                    scheduled = sniff; main.postDelayed(sniff, 100L + request.delayMs)
                    return
                }
                val code = js?.takeIf { it.isNotEmpty() } ?: LegadoWebView.DEFAULT_JS
                val eval = object : Runnable { override fun run() {
                    if (done()) return
                    view.evaluateJavascript(code) { v ->
                        val text = LegadoWebView.evalResult(v)
                        if (text != null) { finish(Result.success(NovelSources.PageResponse(text, view.url ?: url))); return@evaluateJavascript }
                        if (retry > LegadoWebView.MAX_RETRY) { finish(Result.failure(IllegalStateException("js执行超时"))); return@evaluateJavascript }
                        val next = LegadoWebView.retryIntervals.getOrElse(retry) { LegadoWebView.retryIntervals.last() }
                        retry++; main.postDelayed(this, next)
                    }
                } }
                scheduled = eval; main.postDelayed(eval, 100L + request.delayMs)
            }
        }
        return web
    }
}
