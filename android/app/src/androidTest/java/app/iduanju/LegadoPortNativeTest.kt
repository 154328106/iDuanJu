package app.iduanju
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.iduanju.core.novel.*
import app.iduanju.novel.NovelScreen
import app.iduanju.ui.theme.IdjTheme
import org.json.*
import org.junit.*
import org.junit.Assert.*

/** Controlled source pages + actual native reader/player. Public media playback is NOT a public-source catalog test. */
class LegadoPortNativeTest {
 @get:Rule val ui=createAndroidComposeRule<androidx.activity.ComponentActivity>()
 private fun asset(path:String)=InstrumentationRegistry.getInstrumentation().context.assets.open(path).bufferedReader().use{it.readText()}
 private fun shot(name:String){ui.waitForIdle();val b=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot();try{val o=java.io.ByteArrayOutputStream();b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,o);saveNovelEvidence(name,o.toByteArray())}finally{b.recycle()}}
 private fun flow(folder:String,title:String?,media:Boolean,publicSource:Boolean=false,sourceOverride:String?=null){
  val ctx=InstrumentationRegistry.getInstrumentation().targetContext;val prefs=ctx.getSharedPreferences("novel",0);val old=prefs.all;val oldPage=app.iduanju.ui.UiEvidence.page
  val raw=sourceOverride?:asset("$folder/sources.json")
  if(publicSource){NovelSources.fixture=null}else{val routes=JSONObject(asset("$folder/routes.json"));NovelSources.fixture={url->asset(folder+"/"+routes.getString(url))}}
  NovelSources.configureStorage(java.io.File(ctx.cacheDir,"legado-port-$folder"));app.iduanju.ui.UiEvidence.page="import"
  try{
   val s=NovelSources.import(raw).single()
   val liveHit=if(publicSource)NovelSources.search(s,"",category=NovelSources.categories(s).first()).first()else null
   val actualTitle=title?:liveHit!!.name
   prefs.edit().putString("sources","[]").remove("rankSource").commit()
   ui.setContent{IdjTheme(dark=false){NovelScreen({})}}
   ui.onNode(hasSetTextAction()).performTextInput(raw)
   ui.onNode(hasText("导入书源") and hasClickAction()).performClick()
   ui.waitUntil(10000){ui.onAllNodesWithText("已导入",substring=true).fetchSemanticsNodes().isNotEmpty()}
   assertEquals(1,JSONArray(prefs.getString("sources","[]")).length())
   ui.onNodeWithContentDescription("关闭").performClick()
   ui.onNodeWithText("排行榜").performClick()
   ui.waitUntil(20000){ui.onAllNodesWithText(actualTitle,substring=false).fetchSemanticsNodes().isNotEmpty()}
   shot("port-android-$folder-catalog.png")
   ui.onAllNodesWithText(actualTitle,substring=false).onFirst().performClick()
   ui.waitUntil(20000){runCatching{ui.onNodeWithTag("novel.detail.read").assertIsEnabled();true}.getOrDefault(false)}
   shot("port-android-$folder-detail.png");ui.onNodeWithTag("novel.detail.read").performClick()
   if(media){
    ui.waitUntil(60000){
     val node=ui.onAllNodesWithTag("novel.media.status").fetchSemanticsNodes().firstOrNull()
     val text=node?.config?.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text){emptyList()}?.firstOrNull()?.text.orEmpty()
     Regex("已播放 ([0-9.]+)").find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.let{it>=0.2}==true
    }
    ui.onNodeWithTag("novel.media.error").assertDoesNotExist();ui.onNodeWithTag("novel.media.player").assertExists()
   }else{ui.waitUntil(20000){ui.onAllNodesWithTag("novel.reader").fetchSemanticsNodes().isNotEmpty()}}
   if(publicSource){assertTrue(LegadoRss.article(s,liveHit!!.url).getBoolean("read"));NovelSources.search(s,"",category=NovelSources.categories(s).first());assertTrue(LegadoRss.article(s,liveHit.url).getBoolean("read"))}
   shot("port-android-$folder-consumed.png")
   saveNovelEvidence("port-android-$folder-result.json",JSONObject().put("sourcePages",if(publicSource)"public source, actual HTTP, no fixture"else "self-owned controlled fixture").put("nativeImportUI",true).put("nativeConsumption",if(media)"public media timeline >=0.2s"else "reader body visible").toString().toByteArray())
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
}
