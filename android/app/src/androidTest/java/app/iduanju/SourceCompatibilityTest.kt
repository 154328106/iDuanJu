package app.iduanju

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.iduanju.core.novel.*
import app.iduanju.novel.NovelScreen
import app.iduanju.ui.theme.IdjTheme
import org.json.*
import org.junit.*
import org.junit.Assert.*

/** Real public-domain text through native Rhino/network/cache/UI; no invented success rows. */
class SourceCompatibilityTest {
 @get:Rule val ui=createAndroidComposeRule<androidx.activity.ComponentActivity>()
 private fun sourceFixture(name:String):String {
  val raw=runCatching{InstrumentationRegistry.getInstrumentation().context.assets.open("source-compat/"+name).bufferedReader().use{it.readText()}}.getOrNull()
  org.junit.Assume.assumeTrue("Prepare private source fixtures with scripts/source-compat/prepare_rank_fixture.py",raw!=null)
  return raw!!
 }
 private fun requireLive(){org.junit.Assume.assumeTrue("Pass -e sourceCompatLive 1 for bounded live source tests",InstrumentationRegistry.getArguments().getString("sourceCompatLive")=="1")}
 @After fun cleanup(){NovelSources.fixture=null;NovelSources.requestObserver=null}
 private fun shot(name:String){ui.waitForIdle();val b=ui.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap();try{val o=java.io.ByteArrayOutputStream();b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,o);saveNovelEvidence(name,o.toByteArray())}finally{b.recycle()}}
 @Test fun unsupportedImportsShowHonestNativeFeedback(){
  val old=app.iduanju.ui.UiEvidence.page
  app.iduanju.ui.UiEvidence.page="import"
  try{
   for(url in listOf("http://example.org/x","https://u:p@example.org/x","https://127.0.0.1/x","https://example.org:8443/x"))assertTrue(runCatching{app.iduanju.plugin.PluginStore.checkedDownloadURL(url)}.isFailure)
   ui.setContent{IdjTheme(dark=false){NovelScreen({})}}
   val input=ui.onNode(hasSetTextAction())
   input.performTextInput("""{"bookSourceUrl":"https://compat-audio.invalid","bookSourceType":1}""")
   ui.onNode(hasText("导入书源") and hasClickAction()).performClick()
   ui.waitUntil(10000){ui.onAllNodesWithText("暂不支持有声",substring=true).fetchSemanticsNodes().isNotEmpty()}
   androidx.test.espresso.Espresso.closeSoftKeyboard()
   ui.onAllNodesWithText("暂不支持有声",substring=true).onLast().assertIsDisplayed()
   shot("source-android-unsupported-audio.png")
   input.performTextClearance();input.performTextInput("""[{"sourceUrl":"https://compat-rss.invalid","ruleArticles":"a"}]""")
   ui.onNode(hasText("导入书源") and hasClickAction()).performClick()
   ui.waitUntil(10000){ui.onAllNodesWithText("暂不支持 Legado RSS",substring=true).fetchSemanticsNodes().isNotEmpty()}
   androidx.test.espresso.Espresso.closeSoftKeyboard()
   ui.onAllNodesWithText("暂不支持 Legado RSS",substring=true).onLast().assertIsDisplayed()
   shot("source-android-unsupported-rss.png")
  }finally{app.iduanju.ui.UiEvidence.page=old}
 }

 @Test fun aiMirrorLandingPageCannotBecomeCatalogOrigin(){
  requireLive()
  val prefs=java.util.concurrent.ConcurrentHashMap<String,String>()
  prefs["stv.huangguo.aiBase"]="https://huangguoai.ai"
  val client=app.iduanju.core.huangguo.HuangguoClient(prefs=prefs)
  val base=client.ensureAIBase()
  val list=client.list(app.iduanju.core.huangguo.HuangguoChannel(app.iduanju.core.huangguo.HuangguoSub.ai,"ai-duanju","AI短剧"),1)
  assertTrue(list.items.isNotEmpty())
  saveNovelEvidence("source-android-ai-mirror.json",JSONObject().put("seededOrigin","https://huangguoai.ai").put("validatedOrigin",base).put("catalog",list.items.size).put("apiCapabilityValidated",true).toString(2).toByteArray())
 }

 @Test fun subscriptionUpdatePersistsAndRejectsForeignUpdate(){
  val target=InstrumentationRegistry.getInstrumentation().targetContext
  val suffix=java.util.UUID.randomUUID().toString()
  val ctx=object:android.content.ContextWrapper(target){
   override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("compat-$suffix-$name",mode)
   override fun getFilesDir()=java.io.File(cacheDir,"compat-$suffix").also{it.mkdirs()}
  }
  val url="https://compat.example.org/sub/list.json"
  var body="""{"name":"v1","plugins":[{"id":"com.example.catalog","manifest":"plugins/manifest.json","version":"1.0.0"}]}"""
  val store=app.iduanju.plugin.PluginStore(ctx){body}
  try{
   assertEquals("https://compat.example.org/sub/plugins/manifest.json",store.addSubscription(url).plugins.first().manifest)
   body="""{"name":"v2","plugins":[{"id":"com.example.catalog","manifest":"plugins/manifest.json","version":"1.1.0"}]}"""
   store.refreshSubscriptions();assertEquals("1.1.0",store.subscriptions.first().plugins.first().version)
   body="""{"sites":[],"spider":"https://example.org/spider.jar"}"""
   store.refreshSubscriptions();assertEquals("v2",store.subscriptions.first().name)
   assertTrue(store.subscriptions.first().error.orEmpty().contains("TVBox"))
   val reloaded=app.iduanju.plugin.PluginStore(ctx)
   assertEquals("1.1.0",reloaded.subscriptions.first().plugins.first().version)
   assertTrue(reloaded.plugins.isEmpty())
   saveNovelEvidence("source-android-subscription.json","""{"controlled_transport":true,"v1_to_v2":true,"foreign_update_preserves_cache":true,"native_preferences_reload":true,"auto_installed_plugins":0}""".toByteArray())
  }finally{ctx.getSharedPreferences("idj",0).edit().clear().commit();ctx.filesDir.deleteRecursively()}
 }

 @Test fun classicCatalogDetailAndActualReading(){
  requireLive()
  val ins=InstrumentationRegistry.getInstrumentation();val ctx=ins.targetContext
  val raw=ins.context.assets.open("source-compat/classic-source.json").bufferedReader().use{it.readText()}
  val source=NovelSources.import(raw).single()
  val cats=NovelSources.categories(source);assertTrue(cats.none{it.isControl})
  val list=NovelSources.search(source,"",category=cats.first{it.url.isNotBlank()})
  val first=list.first();assertEquals("春秋公羊传",first.name)
  val toc=NovelSources.chapters(source,first.url);assertTrue(toc.isNotEmpty())
  val text=NovelSources.content(source,toc.first(),toc.getOrNull(1)?.url,first.url);assertTrue(text.length>30)
  val prefs=ctx.getSharedPreferences("novel",0);val old=prefs.getString("sources",null);val oldRank=prefs.getString("rankSource",null)
  try{
   prefs.edit().putString("sources",raw).putString("rankSource",source.id).commit()
   ui.setContent{IdjTheme(dark=false){NovelScreen({})}}
   ui.onNodeWithText("排行榜").performClick()
   ui.waitUntil(120000){ui.onAllNodesWithText(first.name,substring=false).fetchSemanticsNodes().isNotEmpty()}
   shot("source-android-classic-catalog.png")
   ui.onAllNodesWithText(first.name,substring=false).onFirst().performClick()
   ui.waitUntil(120000){ui.onAllNodesWithTag("novel.detail.read").fetchSemanticsNodes().isNotEmpty()}
   ui.waitUntil(120000){runCatching{ui.onNodeWithTag("novel.detail.read").assertIsEnabled();true}.getOrDefault(false)}
   shot("source-android-classic-detail.png")
   ui.onNodeWithTag("novel.detail.read").performClick()
   ui.waitUntil(120000){ui.onAllNodesWithTag("novel.reader").fetchSemanticsNodes().isNotEmpty()}
   shot("source-android-classic-reading.png")
   saveNovelEvidence("source-classic-engine.json",JSONObject().put("source",source.id).put("book",first.name).put("catalog",list.size).put("chapters",toc.size).put("textLength",text.length).toString(2).toByteArray())
  }finally{prefs.edit().putString("sources",old).putString("rankSource",oldRank).commit()}
 }

 @Test fun rankControlsHaveSourceWidthsAndMiduSelection(){
  requireLive()
  val ins=InstrumentationRegistry.getInstrumentation();val ctx=ins.targetContext
  val raw=sourceFixture("filter-sources.json")
  val source=NovelSources.import(raw).first{it.name=="七猫小说"}
  val uiRequests=java.util.concurrent.CopyOnWriteArrayList<Triple<String,String,String?>>()
  NovelSources.requestObserver={method,url,body->if(url.contains("/api/v4/tag/index")||url.contains("/search/channel"))uiRequests.add(Triple(method,url,body))}
  fun requestParams(path:String):JSONObject{
   val r=uiRequests.last{java.net.URI(it.second).path.endsWith(path)};val o=JSONObject()
   (java.net.URI(r.second).rawQuery.orEmpty()+"&"+r.third.orEmpty()).split('&').forEach{bit->val kv=bit.split('=',limit=2);val k=java.net.URLDecoder.decode(kv[0],"UTF-8");if(k in setOf("gender","over","words","sort","page","second_channel"))o.put(k,java.net.URLDecoder.decode(kv.getOrElse(1){""},"UTF-8"))};return o
  }
  fun awaitResults(control:String){ui.waitUntil(120000){runCatching{ui.onNodeWithText(control,substring=false).assertIsEnabled();ui.onNodeWithText("书源推荐与榜单",substring=false).assertDoesNotExist();true}.getOrDefault(false)};ui.waitForIdle()}
  val prefs=ctx.getSharedPreferences("novel",0);val old=prefs.getString("sources",null);val oldRank=prefs.getString("rankSource",null)
  NovelSources.configureStorage(java.io.File(ctx.cacheDir,"source-controls-"+java.util.UUID.randomUUID()))
  try{
   prefs.edit().putString("sources",raw).putString("rankSource",source.id).commit()
   val cats=NovelSources.categories(source);val bar=cats.first{it.title.startsWith("⚙ 筛选")}
   ui.setContent{IdjTheme(dark=false){NovelScreen({})}}
   ui.onNodeWithText("排行榜").performClick()
   ui.waitUntil(120000){runCatching{ui.onNode(hasText("⚙ 筛选",substring=true)).assertIsEnabled();true}.getOrDefault(false)}
   ui.onNode(hasText("⚙ 筛选",substring=true)).performScrollTo().performClick()
   ui.waitUntil(120000){ui.onAllNodesWithText("完结",substring=false).fetchSemanticsNodes().isNotEmpty()}
   val chip=ui.onNodeWithTag("novel.filter.完结").fetchSemanticsNode().boundsInRoot.width
   val screen=ui.onRoot().fetchSemanticsNode().boundsInRoot.width
   assertTrue("source flex-grow should fill the 3-column row: chip=$chip screen=$screen",chip>screen*.26f)
   for(value in listOf("完结","50-100万","按评分")){
    ui.waitUntil(120000){runCatching{ui.onNodeWithText(value,substring=false).assertIsEnabled();true}.getOrDefault(false)}
    ui.onNodeWithText(value,substring=false).performScrollTo().performClick()
    ui.waitUntil(120000){ui.onAllNodesWithText("● "+value,substring=false).fetchSemanticsNodes().isNotEmpty()}
   }
   awaitResults("● 按评分")
   val qParams=requestParams("/api/v4/tag/index")
   assertEquals("1",qParams.getString("over"));assertEquals("2",qParams.getString("words"));assertEquals("2",qParams.getString("sort"));assertEquals("1",qParams.getString("page"))
   shot("source-android-qimao-filter-combination.png")
   ui.onAllNodesWithText("七猫小说",substring=false).onFirst().performClick()
   ui.onNodeWithText("米读小说",substring=false).performClick()
   ui.waitUntil(120000){ui.onAllNodesWithText("⚧ 频道：男频 ▾",substring=false).fetchSemanticsNodes().isNotEmpty()}
   ui.waitUntil(120000){runCatching{ui.onNodeWithText("⚧ 频道：男频 ▾").assertIsEnabled();true}.getOrDefault(false)}
   ui.onNodeWithText("⚧ 频道：男频 ▾").performClick();ui.onNodeWithText("女频",substring=false).performClick()
   ui.waitUntil(120000){ui.onAllNodesWithText("⚧ 频道：女频 ▾",substring=false).fetchSemanticsNodes().isNotEmpty()}
   awaitResults("⚧ 频道：女频 ▾")
   val mParams=requestParams("/search/channel");assertEquals("17",mParams.getString("second_channel"));assertEquals("0",mParams.getString("page"))
   shot("source-android-midu-female-filter.png")
   saveNovelEvidence("source-android-ui-filter-requests.json",JSONObject().put("qimao",qParams).put("midu",mParams).put("nativeResultsVisible",true).toString(2).toByteArray())
  }catch(e:Throwable){shot("source-android-filter-failure.png");saveNovelEvidence("source-android-filter-failure.txt",ui.onRoot().printToString().toByteArray());throw e}
  finally{prefs.edit().putString("sources",old).putString("rankSource",oldRank).commit();NovelSources.configureStorage(ctx.filesDir)}
 }

 /** Synthetic transport race, not counted as a live-source pass. */
 @Test fun sourceSwitchBackCannotRestoreStaleRankResponse(){
  val ins=InstrumentationRegistry.getInstrumentation();val ctx=ins.targetContext;val prefs=ctx.getSharedPreferences("novel",0)
  val old=prefs.getString("sources",null);val oldRank=prefs.getString("rankSource",null)
  val firstStarted=java.util.concurrent.CountDownLatch(1);val releaseFirst=java.util.concurrent.CountDownLatch(1);val firstReturned=java.util.concurrent.CountDownLatch(1)
  val calls=java.util.concurrent.atomic.AtomicInteger()
  val raw=JSONArray(listOf("a","b").map{key->JSONObject().put("bookSourceUrl","https://race-$key.invalid").put("bookSourceName","Fixture "+key.uppercase()).put("exploreUrl","Catalog::/rank?page={{page}}")
   .put("ruleExplore",JSONObject().put("bookList","a").put("name","@text").put("bookUrl","@href"))}).toString()
  NovelSources.fixture={url->
   val title=if(url.contains("race-a.invalid")){
    if(calls.incrementAndGet()==1){firstStarted.countDown();releaseFirst.await(30,java.util.concurrent.TimeUnit.SECONDS);firstReturned.countDown();"Stale A"}else "Newest A"
   }else "Source B"
   "<a href='/book/"+title.replace(" ","-")+"'>"+title+"</a>"
  }
  try{
   prefs.edit().putString("sources",raw).putString("rankSource","https://race-a.invalid").commit()
   ui.setContent{IdjTheme(dark=false){NovelScreen({})}};ui.onNodeWithText("排行榜").performClick()
   ui.waitUntil(10000){firstStarted.count==0L}
   ui.onNodeWithText("Fixture A",substring=false).performClick();ui.onNodeWithText("Fixture B",substring=false).performClick()
   ui.waitUntil(10000){ui.onAllNodesWithText("Source B",substring=false).fetchSemanticsNodes().isNotEmpty()}
   ui.onNodeWithText("Fixture B",substring=false).performClick();ui.onNodeWithText("Fixture A",substring=false).performClick()
   ui.waitUntil(10000){ui.onAllNodesWithText("Newest A",substring=false).fetchSemanticsNodes().isNotEmpty()}
   releaseFirst.countDown();ui.waitUntil(10000){firstReturned.count==0L};ui.waitForIdle()
   ui.onNodeWithText("Newest A",substring=false).assertExists();ui.onNodeWithText("Stale A",substring=false).assertDoesNotExist()
   saveNovelEvidence("source-android-race.json",JSONObject().put("syntheticTransport",true).put("sourceSwitch","A-B-A").put("aRequests",calls.get()).put("staleResultRejected",true).toString(2).toByteArray())
  }finally{releaseFirst.countDown();NovelSources.fixture=null;prefs.edit().putString("sources",old).putString("rankSource",oldRank).commit()}
 }
 @Test fun twoRealSourcesFilterRequests(){
  requireLive()
  val ins=InstrumentationRegistry.getInstrumentation();val ctx=ins.targetContext
  val raw=sourceFixture("filter-sources.json")
  val requests=java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
  val outcomes=JSONArray();val allow=setOf("gender","book_preference","over","words","sort","page","tag_id","second_channel","status","rank","app","id","keyword")
  fun params(text:String):JSONObject{val o=JSONObject();text.split('&').forEach { bit->val p=bit.split('=',limit=2);val k=java.net.URLDecoder.decode(p[0],"UTF-8");if(k in allow)o.put(k,java.net.URLDecoder.decode(p.getOrElse(1){""},"UTF-8")) };return o}
  NovelSources.requestObserver={method,url,body->val u=java.net.URI(url);val p=params(u.rawQuery.orEmpty());val b=params(body.orEmpty());for(k in b.keys())p.put(k,b.get(k));requests.add(JSONObject().put("method",method).put("origin",u.host).put("path",u.path).put("params",p))}
  NovelSources.configureStorage(java.io.File(ctx.cacheDir,"source-filter-"+java.util.UUID.randomUUID()))
  try{
   for(source in NovelSources.import(raw)){
    var cats=NovelSources.categories(source)
    fun apply(label:String){val c=cats.first{it.title.removePrefix("● ").trim()==label};NovelSources.categoryAction(source,c);cats=NovelSources.categories(source)}
    fun lastTag()=requests.last{it.optString("path")=="/api/v4/tag/index"}.getJSONObject("params")
    if(source.name=="七猫小说"){
     var chosen=cats.first{!it.isControl&&it.url.isNotBlank()}
     apply(cats.first{it.title.startsWith("⚙ 筛选")}.title)
     for(value in listOf("完结","50-100万","按评分"))apply(value)
     chosen=NovelSources.refreshedCategory(chosen,cats,chosen.group)!!
     val male=NovelSources.search(source,"",1,chosen);assertTrue(male.isNotEmpty())
     val mp=lastTag();assertEquals("1",mp.getString("gender"));assertEquals("1",mp.getString("over"));assertEquals("2",mp.getString("words"));assertEquals("2",mp.getString("sort"));assertEquals("1",mp.getString("page"))
     apply("♀ 女频");chosen=NovelSources.refreshedCategory(chosen,cats,chosen.group)!!
     val female=NovelSources.search(source,"",1,chosen);assertTrue(female.isNotEmpty());assertEquals("2",lastTag().getString("gender"))
     for(value in listOf("全部状态","全部字数","按热度"))apply(value)
     chosen=NovelSources.refreshedCategory(chosen,cats,chosen.group)!!
     val reset=NovelSources.search(source,"",1,chosen);assertTrue(reset.isNotEmpty());val rp=lastTag();assertEquals("-99",rp.getString("over"));assertEquals("-99",rp.getString("words"));assertEquals("0",rp.getString("sort"));assertEquals("1",rp.getString("page"))
     outcomes.put(JSONObject().put("source",source.name).put("male",male.size).put("female",female.size).put("reset",reset.size).put("passed",true))
    }else{
     val control=cats.first{it.type=="select"};val first=cats.first{!it.isControl&&it.url.isNotBlank()}
     val male=NovelSources.search(source,"",1,first);assertTrue(male.isNotEmpty())
     NovelSources.categoryAction(source,control,"女频");cats=NovelSources.categories(source)
     assertEquals("女频",cats.first{it.title==control.title}.value)
     val female=NovelSources.search(source,"",1,cats.first{!it.isControl&&it.url.isNotBlank()});assertTrue(female.isNotEmpty())
     val scenes=requests.filter{it.optString("path")=="/fiction/scene/getCategoryV4"}.map{it.getJSONObject("params").optString("id")}
     assertTrue("281" in scenes);assertTrue("282" in scenes)
     assertTrue(male.map{it.url}.toSet().size>1);assertTrue(female.map{it.url}.toSet().size>1)
     outcomes.put(JSONObject().put("source",source.name).put("male",male.size).put("female",female.size).put("unique",female.map{it.url}.toSet().size).put("passed",true))
    }
   }
  }finally{
   saveNovelEvidence("source-android-real-filter-requests.json",JSONObject().put("requests",JSONArray(requests.toList())).put("outcomes",outcomes).toString(2).toByteArray())
   NovelSources.requestObserver=null;NovelSources.configureStorage(ctx.filesDir)
  }
 }

}
