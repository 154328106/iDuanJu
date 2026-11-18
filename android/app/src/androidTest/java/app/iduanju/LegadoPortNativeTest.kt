package app.iduanju
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.iduanju.core.novel.*
import app.iduanju.novel.NovelScreen
import app.iduanju.novel.guardedMediaRequest
import app.iduanju.ui.theme.IdjTheme
import org.json.*
import org.junit.*
import org.junit.Assert.*

/** Controlled source pages + actual native reader/player. Public media playback is NOT a public-source catalog test. */
class LegadoPortNativeTest {
 @get:Rule val ui=createAndroidComposeRule<androidx.activity.ComponentActivity>()
 private fun asset(path:String)=InstrumentationRegistry.getInstrumentation().context.assets.open(path).bufferedReader().use{it.readText()}
 private fun shot(name:String){ui.waitForIdle();val b=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot();try{val o=java.io.ByteArrayOutputStream();b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,o);saveNovelEvidence(name,o.toByteArray())}finally{b.recycle()}}
 private fun flow(folder:String,title:String?,media:Boolean,publicSource:Boolean=false,sourceOverride:String?=null,searchQuery:String?=null){
  val ctx=InstrumentationRegistry.getInstrumentation().targetContext;val prefs=ctx.getSharedPreferences("novel",0);val old=prefs.all;val oldPage=app.iduanju.ui.UiEvidence.page
  val raw=sourceOverride?:asset("$folder/sources.json")
  if(publicSource){NovelSources.fixture=null}else{val routes=JSONObject(asset("$folder/routes.json"));NovelSources.fixture={url->asset(folder+"/"+routes.getString(url))}}
  NovelSources.configureStorage(java.io.File(ctx.cacheDir,"legado-port-$folder"));app.iduanju.ui.UiEvidence.page="import"
  try{
   val s=NovelSources.import(raw).single()
   val liveHit=if(publicSource){if(searchQuery!=null)NovelSources.search(s,searchQuery).first{it.name==title}else NovelSources.search(s,"",category=NovelSources.categories(s).first()).first()}else null
   val actualTitle=title?:liveHit!!.name
   prefs.edit().putString("sources","[]").remove("rankSource").commit()
   ui.setContent{IdjTheme(dark=false){NovelScreen({})}}
   ui.onNode(hasSetTextAction()).performTextInput(raw)
   ui.onNode(hasText("导入书源") and hasClickAction()).performClick()
   ui.waitUntil(10000){ui.onAllNodesWithText("已导入",substring=true).fetchSemanticsNodes().isNotEmpty()}
   assertEquals(1,JSONArray(prefs.getString("sources","[]")).length())
   ui.onNodeWithContentDescription("关闭").performClick()
   ui.onNodeWithText("排行榜").performClick()
   if(searchQuery!=null){ui.onNodeWithContentDescription("搜书").performClick();ui.onNode(hasSetTextAction()).performTextInput(searchQuery);ui.onNode(hasSetTextAction()).performImeAction()}
   ui.waitUntil(20000){ui.onAllNodesWithText(actualTitle,substring=false).fetchSemanticsNodes().isNotEmpty()}
   shot("port-android-$folder-catalog.png")
   // Public demo source and book deliberately share a title; first match is the source picker.
   ui.onAllNodesWithText(actualTitle,substring=false).onLast().performClick()
   ui.waitUntil(20000){runCatching{ui.onNodeWithTag("novel.detail.read").assertIsEnabled();true}.getOrDefault(false)}
   shot("port-android-$folder-detail.png");ui.onNodeWithTag("novel.detail.read").performClick()
   if(media){
    val minimum=if(s.json.optInt("bookSourceType")==4 || (sourceOverride!=null && s.isMedia))2.0 else 0.2
    ui.waitUntil(60000){
     val node=ui.onAllNodesWithTag("novel.media.status").fetchSemanticsNodes().firstOrNull()
     val text=node?.config?.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text){emptyList()}?.firstOrNull()?.text.orEmpty()
     Regex("已播放 ([0-9.]+)").find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.let{it>=minimum && (minimum<2 || text.contains("播放中"))}==true
    }
    ui.onNodeWithTag("novel.media.error").assertDoesNotExist();ui.onNodeWithTag("novel.media.player").assertExists()
   }else{ui.waitUntil(20000){ui.onAllNodesWithTag("novel.reader").fetchSemanticsNodes().isNotEmpty()}}
   if(publicSource&&s.isRSS){assertTrue(LegadoRss.article(s,liveHit!!.url).getBoolean("read"));NovelSources.search(s,"",category=NovelSources.categories(s).first());assertTrue(LegadoRss.article(s,liveHit.url).getBoolean("read"))}
   shot("port-android-$folder-consumed.png")
   saveNovelEvidence("port-android-$folder-result.json",JSONObject().put("sourcePages",if(publicSource){if(s.isRSS)"public RSS, actual HTTP, no fixture"else if(sourceOverride!=null || folder.startsWith("nasa-"))"actual public media library, HTTP, no fixture" else "self-owned data: catalog and public media, no fixture"}else "self-owned controlled fixture").put("nativeImportUI",true).put("nativeConsumption",if(media)if(s.json.optInt("bookSourceType")==4)"public video timeline >=2s and playing" else "public audio timeline >=0.2s"else "reader body visible").toString().toByteArray())
  }finally{
   NovelSources.fixture=null;NovelSources.configureStorage(ctx.filesDir);app.iduanju.ui.UiEvidence.page=oldPage
   val edit=prefs.edit().clear();for((k,v)in old)when(v){is String->edit.putString(k,v);is Int->edit.putInt(k,v);is Boolean->edit.putBoolean(k,v);is Float->edit.putFloat(k,v);is Long->edit.putLong(k,v)};edit.commit()
  }
 }
 @Test fun rssImportUpdateAndNativeReader()=flow("rss-ui","First RSS article",false)
 @Test fun audioImportThroughNativePlayback()=flow("audio-ui","Owned media book 1",true)
 @Test fun videoImportThroughNativePlayback()=flow("video-ui","Owned media book 4",true)
 @Test fun nasaPublicImportUpdateAndNativeReader()=flow("nasa-public",null,false,publicSource=true)
 @Test fun personalYckPublicImportUpdateAndNativeReader(){
  val encoded=InstrumentationRegistry.getArguments().getString("personalRssBase64");Assume.assumeTrue(!encoded.isNullOrBlank())
  val raw=String(android.util.Base64.decode(encoded,android.util.Base64.DEFAULT),Charsets.UTF_8)
  flow("personal-yck-rss",null,false,publicSource=true,sourceOverride=raw)
 }
 @Test fun mediaRedirectGuardChecksOriginLiteralIPAndPortBeforeNetwork(){
  val old=app.iduanju.core.util.NetGuard.resolverOverride;app.iduanju.core.util.NetGuard.resolverOverride={listOf("8.8.8.8")}
  try{
   fun request(url:String)=okhttp3.Request.Builder().url(url).header("Authorization","fixture-not-a-secret").header("Cookie","fixture=true").build()
   val factory=app.iduanju.novel.GuardedMediaSocketFactory()
   for(endpoint in listOf(java.net.InetSocketAddress("127.0.0.1",80),java.net.InetSocketAddress("::1",443),java.net.InetSocketAddress("8.8.8.8",444))){
    val failure=runCatching{factory.createSocket().use{it.connect(endpoint,50)}}.exceptionOrNull()
    assertTrue(failure?.message.orEmpty().contains("媒体连接拒绝"))
   }
   val origin="https://media.example.org/start"
   assertEquals("fixture-not-a-secret",guardedMediaRequest(request("https://media.example.org/file"),origin).header("Authorization"))
   assertNull(guardedMediaRequest(request("http://media.example.org/file"),origin).header("Authorization"))
   for(url in listOf("http://127.0.0.1/x","http://[::1]/x","http://192.168.1.1/x","https://media.example.org:444/x"))assertTrue(url,runCatching{guardedMediaRequest(request(url),origin)}.isFailure)
  }finally{app.iduanju.core.util.NetGuard.resolverOverride=old}
 }

 @Test fun standalonePublicAudioConfigNativePlayback()=flow("audio-static-public",null,true,publicSource=true)
 @Test fun standalonePublicVideoConfigNativePlayback()=flow("video-static-public",null,true,publicSource=true)

 @Test fun personalYckFreeAudioSearchDetailTocNativePlayback(){
  val encoded=InstrumentationRegistry.getArguments().getString("personalAudioBase64");Assume.assumeTrue(!encoded.isNullOrBlank())
  val raw=String(android.util.Base64.decode(encoded,android.util.Base64.DEFAULT),Charsets.UTF_8)
  flow("personal-yck-audio","《论语》朗读与讲解",true,publicSource=true,sourceOverride=raw,searchQuery="论语")
 }
 @Test fun nasaRealAnimationLibraryNativePlayback()=flow("nasa-animation-public",null,true,publicSource=true)
 @Test fun nasaWebJsRealPageNativePlayback()=flow("nasa-webview-public",null,true,publicSource=true)
 @Test fun nasaSourceRegexSniffRealPageNativePlayback()=flow("nasa-webview-sniff-public",null,true,publicSource=true)
 @Test fun backstageWebViewTimeoutCancelAndPrivateGuards(){
  val ctx=InstrumentationRegistry.getInstrumentation().targetContext;val web=app.iduanju.novel.AndroidBackstageWebView(ctx)
  val page="https://images.nasa.gov/"
  val started=System.currentTimeMillis()
  val timeout=runCatching{web.load(BackstageRequest(page,emptyMap(),"null",null,0,4000))}.exceptionOrNull()
  assertTrue(timeout?.message.orEmpty(),timeout?.message.orEmpty().let{it.contains("超时")});assertTrue(System.currentTimeMillis()-started<15000)
  assertTrue(runCatching{web.load(BackstageRequest("http://127.0.0.1/",emptyMap(),null,null,0,4000))}.isFailure)
  var failure:Throwable?=null
  val t=Thread{failure=runCatching{web.load(BackstageRequest(page,emptyMap(),"null",null,0,60000))}.exceptionOrNull()};t.start();Thread.sleep(1500);t.interrupt();t.join(5000)
  assertFalse(t.isAlive);assertTrue(failure is InterruptedException)
  val ok=web.load(BackstageRequest(page,emptyMap(),"document.title",null,0,30000));assertTrue(ok.text,ok.text.isNotBlank())
 }
}
