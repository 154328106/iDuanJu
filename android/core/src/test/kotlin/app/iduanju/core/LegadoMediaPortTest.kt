package app.iduanju.core
import app.iduanju.core.novel.*
import app.iduanju.core.util.NetGuard
import org.json.*
import org.junit.*
import org.junit.Assert.*
class LegadoMediaPortTest {
 @After fun cleanup(){NovelSources.fixture=null;NetGuard.resolverOverride=null;LegadoWebView.backstage=null}
 @Test fun sharedAudioVideoCatalogDetailTocAndRawMediaContract(){
  NetGuard.resolverOverride={listOf("8.8.8.8")}
  val cases=JSONArray(javaClass.getResource("/media-cases.json")!!.readText())
  for(i in 0 until cases.length()){
   val c=cases.getJSONObject(i);val s=NovelSources.import(c.getJSONObject("source").toString()).single();assertTrue(s.isMedia)
   NovelSources.fixture={url->when{url.endsWith("catalog")->c.getString("catalog");url.endsWith("book")->c.getString("detail");url.endsWith("toc")->c.getString("toc");else->c.getString("chapter")}}
   val hit=NovelSources.search(s,"",category=NovelSources.categories(s).first()).single()
   val info=NovelSources.details(s,hit.url);assertEquals("Owned media book",info.name);assertEquals(1,info.chapters.size)
   val media=LegadoMedia.resolve(s,info.chapters.single(),hit.url)
   assertEquals(c.getString("expectedURL"),media.url);assertEquals("https://example.org/",media.headers["Referer"])
   assertFalse(media.headers.keys.any{it.equals("Cookie",true)||it.equals("Authorization",true)})
   assertEquals(c.getJSONObject("source").getInt("bookSourceType"),media.sourceType)
   assertTrue(runCatching{NovelSources.content(s,info.chapters.single())}.isFailure)
   val raw=JSONObject(s.json.toString()).put("ruleContent",JSONObject().put("content",c.getString("contextRule")))
   val contextual=LegadoMedia.resolve(NovelSources.import(raw.toString()).single(),NovelChapter("episode",info.chapters.single().url,7),hit.url,"https://example.org/next")
   assertEquals(c.getString("contextExpectedURL"),contextual.url)
   assertNull(NovelScript.currentChapter.get());assertNull(NovelScript.nextChapterURL.get())
  }
 }
 @Test fun mediaWebJsImportsAndUsesUpstreamWebViewContract(){
  NetGuard.resolverOverride={listOf("8.8.8.8")}
  val report=NovelSources.importReport("""{"bookSourceUrl":"https://example.org","bookSourceType":4,"ruleContent":{"webJs":"document.title"}}""")
  assertEquals(1,report.sources.size);assertEquals(0,report.unsupported)
  val cases=JSONArray(javaClass.getResource("/webview-media-cases.json")!!.readText())
  for(i in 0 until cases.length()){
   val c=cases.getJSONObject(i);val e=c.getJSONObject("expect");val s=NovelSources.import(c.getJSONObject("source").toString()).single()
   var seen:BackstageRequest?=null
   LegadoWebView.backstage=LegadoBackstage{r->seen=r;val b=c.getJSONObject("backstage");NovelSources.PageResponse(b.getString("text"),b.getString("url"))}
   NovelSources.fixture=if(c.has("http")){{c.getString("http")}}else{{error("webView chapter must not use plain HTTP")}}
   val media=LegadoMedia.resolve(s,NovelChapter("ep",c.getString("chapterURL")))
   assertEquals(c.getString("name"),e.getString("mediaURL"),media.url)
   if(e.has("backstageCalled")){assertNull(seen);continue}
   val r=seen!!
   assertEquals(e.getString("pageURL"),r.url);assertEquals(e.opt("js").takeIf{it!=JSONObject.NULL},r.javaScript)
   assertEquals(e.opt("sourceRegex").takeIf{it!=JSONObject.NULL},r.sourceRegex);assertEquals(e.getLong("delayMs"),r.delayMs);assertEquals(60000L,r.timeoutMs)
   assertEquals(e.getBoolean("pageCookie"),r.headers.keys.any{it.equals("Cookie",true)})
   assertEquals(e.getBoolean("mediaCookie"),media.headers.keys.any{it.equals("Cookie",true)})
   assertEquals(e.opt("mediaReferer").takeIf{it!=JSONObject.NULL},media.headers["Referer"])
  }
 }
 @Test fun webViewSafetyAndResultSemantics(){
  NetGuard.resolverOverride={listOf("8.8.8.8")}
  for(v in listOf(null,"",false,"false"))assertFalse(LegadoWebView.useWebView(v));for(v in listOf(true,"true","“true”","1",1))assertTrue(LegadoWebView.useWebView(v))
  assertNull(LegadoWebView.evalResult("null"));assertNull(LegadoWebView.evalResult("\"\""));assertEquals("a\"b",LegadoWebView.evalResult("\"a\\\"b\""))
  assertEquals("https://cdn.example.net/x.mp3",LegadoWebView.firstSniffed(".*\\.mp3.*", "\"[\\\"https://a.example/page.js\\\",\\\"https://cdn.example.net/x.mp3\\\"]\""))
  assertFalse(LegadoWebView.matches("([",""))
  val s=NovelSources.import("""{"bookSourceUrl":"https://page.example.org","bookSourceType":4,"ruleContent":{"content":"<js>result</js>"}}""").single()
  LegadoWebView.backstage=LegadoBackstage{error("private page must be rejected before WebView")}
  assertTrue(runCatching{LegadoMedia.resolve(s,NovelChapter("x","http://127.0.0.1/p,{\"webView\":true}"))}.isFailure)
  LegadoWebView.backstage=LegadoBackstage{NovelSources.PageResponse("http://10.0.0.2/x.mp4",it.url)}
  assertTrue(runCatching{LegadoMedia.resolve(s,NovelChapter("x","https://page.example.org/p,{\"webView\":true}"))}.isFailure)
  LegadoWebView.backstage=null
  assertTrue(runCatching{LegadoMedia.resolve(s,NovelChapter("x","https://page.example.org/p,{\"webView\":true}"))}.exceptionOrNull()!!.message!!.contains("WebView"))
 }
 @Test fun directMediaAndSafetyContract(){
  NetGuard.resolverOverride={listOf("8.8.8.8")}
  assertTrue(LegadoMedia.sameOrigin("https://media.example.org/file","https://media.example.org:443/root"))
  assertFalse(LegadoMedia.sameOrigin("https://media.example.org/file","http://media.example.org/root"))
  val s=NovelSources.import("""{"bookSourceUrl":"https://direct.example.org","bookSourceType":1}""").single()
  assertEquals("https://direct.example.org/one.mp3",LegadoMedia.resolve(s,NovelChapter("one","https://direct.example.org/one.mp3")).url)
  for(url in listOf("file:///etc/passwd","http://127.0.0.1/x","http://[::1]/x","https://u:p@example.org/x","https://example.org:80/x"))assertTrue(url,runCatching{LegadoMedia.checkedURL(url)}.isFailure)
  assertTrue(runCatching{LegadoMedia.resolve(s,NovelChapter("post","https://direct.example.org/x,{\"method\":\"POST\"}"))}.isFailure)
 }
 @Test fun standalonePublicDemoConfigsNeedNoFixtureServer(){
  NetGuard.resolverOverride={listOf("8.8.8.8")};NovelSources.fixture={error("Static catalog must not contact fixture network")}
  for(folder in listOf("audio-static-public","video-static-public")){
   val s=NovelSources.import(javaClass.getResource("/$folder/sources.json")!!.readText()).single()
   val hit=NovelSources.search(s,"",category=NovelSources.categories(s).first()).single();val detail=NovelSources.details(s,hit.url)
   val media=LegadoMedia.resolve(s,detail.chapters.single(),hit.url)
   assertTrue(media.url.startsWith("https://"));assertEquals(s.json.getInt("bookSourceType"),media.sourceType)
  }
 }

 @Test fun identityMediaRulesNeverDownloadTheWholeRecording(){
  NetGuard.resolverOverride={listOf("8.8.8.8")};NovelSources.fixture={error("Identity rule must not fetch media bytes")}
  val cases=JSONArray(javaClass.getResource("/media-identity-cases.json")!!.readText())
  for(i in 0 until cases.length()) { val c=cases.getJSONObject(i);val raw=JSONObject().put("bookSourceUrl","https://media.example.org").put("bookSourceType",1).put("ruleContent",JSONObject().put("content",c.getString("rule")));val s=NovelSources.import(raw.toString()).single();assertEquals(c.getString("url"),LegadoMedia.resolve(s,NovelChapter("episode",c.getString("url"))).url) }
 }
 @Test fun missingNonVolumeChapterURLUsesTocBaseLikeUpstream(){
  NovelSources.fixture={"<h2>Original public podcast</h2>"}
  val s=NovelSources.import("""{"bookSourceUrl":"https://example.org","bookSourceType":1,"ruleToc":{"chapterList":"tag.h2","chapterName":"text"}}""").single()
  assertEquals("https://example.org/episode",NovelSources.details(s,"https://example.org/episode").chapters.single().url)
 }
 @Test fun jsonResultKeepsPropertiesAndUpstreamStringCoercion(){
  val source=NovelSources.import("""{"bookSourceUrl":"https://example.org"}""").single();val cases=JSONArray(javaClass.getResource("/json-result-coercion-cases.json")!!.readText())
  for(i in 0 until cases.length()){val c=cases.getJSONObject(i);assertEquals(c.getString("expected"),NovelScript(source,source.id).eval(c.getString("script"),c.get("input")))}
 }
}
