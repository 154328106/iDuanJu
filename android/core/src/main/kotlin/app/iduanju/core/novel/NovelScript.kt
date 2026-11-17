package app.iduanju.core.novel

import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.javascript.*
import java.security.MessageDigest
import java.util.Base64
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Source scripts get only explicit JSON host calls, never Java classes, files or processes. */
internal class NovelScript(private val source: NovelSource, private val base: String, private val key: String = "", private val page: Int = 1) {
    companion object {
        @Volatile var storage: MutableMap<String, String> = ConcurrentHashMap()
        internal val bookURL = ThreadLocal<String?>()
        private var stateFile: java.io.File? = null
        @Synchronized fun configure(root: java.io.File) {
            val file = java.io.File(root,"novel-script-state.json")
            if(stateFile == file)return
            stateFile=file
            val saved=runCatching{JSONObject(file.readText())}.getOrDefault(JSONObject())
            storage=ConcurrentHashMap<String,String>().apply{for(k in saved.keys())put(k,saved.getString(k))}
        }
        @Synchronized private fun put(k:String,v:String) {
            storage[k]=v
            stateFile?.let { file -> file.parentFile?.mkdirs();val tmp=java.io.File(file.parentFile,file.name+".tmp")
                tmp.writeText(JSONObject(storage.toMap()).toString()); java.nio.file.Files.move(tmp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
        internal fun <T> withBook(url:String?, block:()->T):T {
            val previous=bookURL.get();bookURL.set(url)
            try{return block()}finally{bookURL.set(previous)}
        }
        internal fun setVariable(source: String, key: String, value: String) {
            val scope = bookURL.get()?.let { "$source|book|$it|$key" } ?: "$source|$key"
            put(scope, value)
        }
        private val prelude by lazy { NovelScript::class.java.getResourceAsStream("/novel-prelude.js")!!.bufferedReader().use { it.readText() } }
    }
    private val deadline = System.nanoTime() + 25_000_000_000L
    private val factory = object : ContextFactory() {
        override fun makeContext(): Context = super.makeContext().apply {
            languageVersion = Context.VERSION_ES6; optimizationLevel = -1; instructionObserverThreshold = 10_000
            setClassShutter { false }
        }
        override fun observeInstructionCount(cx: Context, count: Int) {
            if (Thread.currentThread().isInterrupted || System.nanoTime() > deadline) throw IllegalStateException("书源脚本超时")
        }
    }
    private var content = ""
    fun eval(code: String, result: Any? = null): Any? = factory.call(ContextAction { cx ->
        val scope = cx.initSafeStandardObjects()
        content = result?.toString() ?: ""
        val host = object : BaseFunction() {
            override fun call(context: Context, s: Scriptable, thisObj: Scriptable, args: Array<out Any>): Any {
                val method = Context.toString(args.getOrNull(0))
                val raw = args.getOrNull(1)?.let { Context.toString(it) } ?: "{}"
                val a = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
                try {
                    val value = invoke(method, a)
                    return JSONObject.valueToString(value ?: JSONObject.NULL)
                } catch (e: Exception) {
                    throw Context.reportRuntimeError(e.message ?: "书源宿主调用失败")
                }
            }
        }
        ScriptableObject.putProperty(scope, "__host", host)
        cx.evaluateString(scope, "var globalThis=this;\n" + compatible(prelude), "novel-host", 1, null)
        val globals = "var source=${source.json}; __wrapSource(source); var baseUrl=${JSONObject.quote(base)}; var key=${JSONObject.quote(key)}; var page=$page; var book={bookUrl:${JSONObject.quote(bookURL.get().orEmpty())},getVariable:function(k){return java.get(k||'__bookVariable')}}; var result=${JSONObject.valueToString(result ?: JSONObject.NULL)};"
        cx.evaluateString(scope, globals, "novel-context", 1, null)
        val lib = source.json.optString("jsLib")
        if (lib.isNotBlank()) cx.evaluateString(scope, compatible(lib), "source-library", 1, null)
        val value = cx.evaluateString(scope, compatible(code), "source-rule", 1, null)
        ScriptableObject.putProperty(scope, "__result", value)
        val json = cx.evaluateString(scope, "JSON.stringify(__result === undefined ? null : __result)", "source-result", 1, null)
        val parsed = org.json.JSONTokener(Context.toString(json)).nextValue()
        if (parsed == JSONObject.NULL) null else parsed
    })
    private fun compatible(code: String) = code.replace(Regex("(\\b(?:map|forEach|filter)\\s*\\(\\s*)(\\[[A-Za-z_$][A-Za-z0-9_$,\\s]*\\])\\s*=>"), "$1($2)=>")
        .replace(Regex("for\\s*\\(const\\s+"), "for (var ")
    private fun scope(k: String) = source.id + "|" + k
    private fun variableScope(k:String)=bookURL.get()?.let{source.id+"|book|"+it+"|"+k} ?: scope(k)
    private fun bytes(a: Any?): ByteArray = when(a) {
        is JSONArray -> ByteArray(a.length()) { a.optInt(it).toByte() }
        null, JSONObject.NULL -> byteArrayOf()
        else -> a.toString().toByteArray()
    }
    private fun jsonBytes(b: ByteArray) = JSONArray(b.map { it.toInt() })
    private fun invoke(name: String, a: JSONObject): Any? {
        fun str(k: String) = a.optString(k).takeUnless { it == "null" } ?: ""
        when (name) {
            "getVar" -> return storage[variableScope(str("k"))] ?: storage[scope(str("k"))] ?: ""
            "putVar" -> { put(variableScope(str("k")),str("v")); return str("v") }
            "sourceGet", "cacheGet" -> return storage[scope(str("k"))] ?: ""
            "sourcePut", "cachePut" -> { put(scope(str("k")),str("v")); return str("v") }
            "loginInfo" -> return storage[scope("loginInfo")] ?: "{}"
            "loginHeader" -> return storage[scope("loginHeader")] ?: "{}"
            "putLoginInfo", "putLoginHeader" -> {put(scope(if(name=="putLoginInfo")"loginInfo" else "loginHeader"),str("v")); return true}
            "ajax" -> return NovelSources.fetch(str("url"), source, key, page)
            "http.bytes" -> {
                val response = NovelSources.fetchBytes(str("url"), str("method"), a.optJSONObject("headers"), bytes(a.opt("b")))
                return JSONObject().put("status",response.status).put("headers",JSONObject(response.headers)).put("b",jsonBytes(response.bytes))
            }
            "random.bytes" -> {
                val n = a.optInt("n",-1); require(n in 0..8192) { "随机字节长度无效" }
                return jsonBytes(ByteArray(n).also { java.security.SecureRandom().nextBytes(it) })
            }
            "gzip" -> {
                val input = bytes(a.opt("b")); require(input.size<=16*1024*1024) { "压缩输入过大" }
                val output=java.io.ByteArrayOutputStream()
                if(a.optBoolean("encode")) java.util.zip.GZIPOutputStream(output).use { it.write(input) }
                else java.util.zip.GZIPInputStream(input.inputStream()).use { stream ->
                    val buffer=ByteArray(16384)
                    while(true) { val n=stream.read(buffer);if(n<0)break;require(output.size()+n<=16*1024*1024){"GZIP 输出过大"};output.write(buffer,0,n) }
                }
                return jsonBytes(output.toByteArray())
            }
            "connect" -> {
                val opts = JSONObject().put("method", str("method").ifBlank { "GET" }).put("body", str("body"))
                a.optJSONObject("headers")?.let { opts.put("headers", it) }
                val url = str("url")
                val request = if (str("method").isBlank() && !a.has("headers")) url else "$url,$opts"
                val response = NovelSources.fetchResponse(request, source, key, page)
                return JSONObject().put("body", response.text).put("url", response.url).put("status", 200).put("headers", response.headers)
            }
            "getString", "getStringList" -> {
                val input = a.optString("content").takeUnless { it.isBlank() || it == "null" } ?: content
                val value = NovelSources.ruleValue(input, str("rule"), source, base, key, page)
                return if(name=="getString")value else JSONArray(value.lines())
            }
            "setContent" -> { content = str("content"); return null }
            "md5" -> return MessageDigest.getInstance("MD5").digest(str("s").toByteArray()).joinToString(""){"%02x".format(it)}
            "uuid" -> return java.util.UUID.randomUUID().toString()
            "ua" -> return NovelSources.userAgent
            "androidId" -> return MessageDigest.getInstance("SHA-256").digest(source.id.toByteArray()).take(8).joinToString(""){"%02x".format(it)}
            "b64e" -> return Base64.getEncoder().encodeToString(str("s").toByteArray())
            "b64.enc" -> return Base64.getEncoder().encodeToString(bytes(a.opt("b")))
            "b64d", "b64.dec" -> {
                val b = Base64.getDecoder().decode(str("s").replace('-','+').replace('_','/').filterNot { it.isWhitespace() })
                return if(name=="b64d")String(b,Charset.forName(str("cs").ifBlank{"UTF-8"})) else jsonBytes(b)
            }
            "bytes.enc" -> return jsonBytes(str("s").toByteArray(Charset.forName(str("cs").ifBlank{"UTF-8"})))
            "bytes.dec" -> return String(bytes(a.opt("b")),Charset.forName(str("cs").ifBlank{"UTF-8"}))
            "hex.dec" -> return jsonBytes(str("s").chunked(2).map{it.toInt(16).toByte()}.toByteArray())
            "hex.enc" -> return bytes(a.opt("b")).joinToString(""){"%02x".format(it)}
            "digest" -> return jsonBytes(MessageDigest.getInstance(str("alg").replace("SHA256","SHA-256").replace("SHA512","SHA-512")).digest(bytes(a.opt("b"))))
            "cipher" -> {
                val transformation = str("t").replace("PKCS7Padding","PKCS5Padding")
                val cipher = Cipher.getInstance(transformation)
                val secret = SecretKeySpec(bytes(a.opt("key")), transformation.substringBefore('/'))
                val iv = bytes(a.opt("iv"))
                if(iv.isEmpty())cipher.init(a.optInt("mode"),secret) else cipher.init(a.optInt("mode"),secret,IvParameterSpec(iv))
                return jsonBytes(cipher.doFinal(bytes(a.opt("b"))))
            }
            "rsa.dec", "rsa.enc" -> {
                val factory=java.security.KeyFactory.getInstance("RSA")
                val privateBytes=bytes(a.opt("priv"))
                val rsaKey=if(privateBytes.isNotEmpty())factory.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(privateBytes))
                    else factory.generatePublic(java.security.spec.X509EncodedKeySpec(bytes(a.opt("key"))))
                val size=((rsaKey as java.security.interfaces.RSAKey).modulus.bitLength()+7)/8
                val encrypt=name=="rsa.enc"
                val rawPublic = !encrypt && privateBytes.isEmpty()
                val cipher=Cipher.getInstance(if(rawPublic)"RSA/ECB/NoPadding" else "RSA/ECB/PKCS1Padding")
                cipher.init(if(encrypt)Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,rsaKey)
                val input=bytes(a.opt("b"));val result=java.io.ByteArrayOutputStream()
                val step=if(encrypt)size-11 else size
                require(encrypt || input.size%size==0){"RSA 数据长度不正确"}
                for(at in input.indices step step) {
                    val block = cipher.doFinal(input,at,minOf(step,input.size-at))
                    if(rawPublic) {
                        val sep = (2 until block.size).firstOrNull { block[it] == 0.toByte() }
                        val start = if(block.size>2 && block[0]==0.toByte() && block[1].toInt() in 1..2 && sep != null && sep>=10) sep+1
                            else block.indexOfFirst { it != 0.toByte() }.let { if(it<0)block.size else it }
                        result.write(block,start,block.size-start)
                    } else result.write(block)
                }
                return jsonBytes(result.toByteArray())
            }
            "inflate" -> {
                val inflater = java.util.zip.Inflater(a.optBoolean("nowrap"))
                val out = java.io.ByteArrayOutputStream()
                try {
                    inflater.setInput(bytes(a.opt("b")))
                    val buffer=ByteArray(8192)
                    while(!inflater.finished()) {
                        val n=inflater.inflate(buffer)
                        check(n>0 || inflater.finished()) { "压缩数据截断或需要字典" }
                        require(out.size()+n<=16*1024*1024) { "解压内容过大" }
                        out.write(buffer,0,n)
                    }
                    return jsonBytes(out.toByteArray())
                } finally { inflater.end() }
            }
            "encodeURI" -> return java.net.URLEncoder.encode(str("s"),str("cs").ifBlank{"UTF-8"}).replace("+","%20")
            "htmlFormat" -> return NovelSources.cleanText(str("s"))
            "headers" -> return source.json.optJSONObject("header") ?: runCatching { JSONObject(source.json.optString("header")) }.getOrDefault(JSONObject())
            "cookie", "log" -> return ""
            "convert" -> return str("s")
            "browser", "webView" -> error("此书源需要网页登录，请更换书源")
            else -> error("暂不支持的书源功能：$name")
        }
    }
}
