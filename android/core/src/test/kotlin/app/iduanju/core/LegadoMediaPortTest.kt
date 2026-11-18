package app.iduanju.core
import app.iduanju.core.novel.*
import app.iduanju.core.util.NetGuard
import org.json.*
import org.junit.*
import org.junit.Assert.*
class LegadoMediaPortTest {
 @After fun cleanup(){NovelSources.fixture=null;NetGuard.resolverOverride=null}
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
 @Test fun directMediaAndSafetyContract(){
  NetGuard.resolverOverride={listOf("8.8.8.8")}
  val s=NovelSources.import("""{"bookSourceUrl":"https://direct.example.org","bookSourceType":1}""").single()
  assertEquals("https://direct.example.org/one.mp3",LegadoMedia.resolve(s,NovelChapter("one","https://direct.example.org/one.mp3")).url)
  for(url in listOf("file:///etc/passwd","http://127.0.0.1/x","http://[::1]/x","https://u:p@example.org/x","https://example.org:80/x"))assertTrue(url,runCatching{LegadoMedia.checkedURL(url)}.isFailure)
  assertTrue(runCatching{LegadoMedia.resolve(s,NovelChapter("post","https://direct.example.org/x,{\"method\":\"POST\"}"))}.isFailure)
 }
}
