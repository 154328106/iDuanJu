package app.iduanju.core

import app.iduanju.core.novel.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.nio.file.Files

class LegadoRssPortTest {
 @After fun cleanup(){NovelSources.fixture=null;NovelSources.fixturePage=null}
 private fun cases()=JSONArray(javaClass.getResource("/rss-cases.json")!!.readText())
 @Test fun sharedUpstreamRssContractsConsumeContentAndPersist(){
  val cases=cases()
  for(i in 0 until cases.length()){
   val c=cases.getJSONObject(i);val root=Files.createTempDirectory("rss-port").toFile();NovelSources.configureStorage(root)
   val source=NovelSources.import(c.getJSONObject("source").toString()).single();assertTrue(source.isRSS)
   val requests=mutableListOf<String>();NovelSources.fixture={url->requests+=url;if(url.contains("/feed"))c.getString("body")else c.getString("detailBody")}
   val cat=NovelSources.categories(source).single();val hits=NovelSources.search(source,"",category=cat)
   assertEquals(c.getJSONArray("expectedTitles").toList(),hits.map{it.name});assertEquals(c.getJSONArray("expectedLinks").toList(),hits.map{it.url})
   if(c.has("expectedImages"))assertEquals(c.getJSONArray("expectedImages").toList(),hits.map{it.cover})
   val info=NovelSources.details(source,hits.first().url);assertEquals(hits.first().name,info.name);assertEquals(1,info.chapters.size)
   assertEquals(c.getString("expectedText"),NovelSources.content(source,info.chapters.single(),bookURL=hits.first().url))
   assertTrue(LegadoRss.article(source,hits.first().url).getBoolean("read"))
   NovelSources.configureStorage(Files.createTempDirectory("rss-other").toFile());NovelSources.configureStorage(root)
   assertTrue(LegadoRss.article(source,hits.first().url).getBoolean("read"))
   NovelSources.search(source,"",category=cat);assertTrue(LegadoRss.article(source,hits.first().url).getBoolean("read"))
   val more=NovelSources.search(source,"",page=2,category=cat)
   if(i==0)assertTrue(more.isEmpty())else{assertEquals(2,more.size);assertTrue(requests.last().endsWith("page=2"))}
  }
 }
 @Test fun dtdAndExternalEntitiesAreRejected(){
  val source=NovelSources.import(cases().getJSONObject(0).getJSONObject("source").toString()).single()
  assertTrue(runCatching{LegadoRss.parse(source,"<!DOCTYPE rss [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]><rss/>",source.id,source.id)}.isFailure)
 }
 @Test fun liveNasaPublicRssReader(){
  Assume.assumeTrue(System.getProperty("idj.live")=="rss")
  val source=NovelSources.import("""{"sourceUrl":"https://www.nasa.gov/feed/","sourceName":"NASA official RSS","sortUrl":"","ruleArticles":""}""").single()
  val hits=NovelSources.search(source,"",category=NovelSources.categories(source).single());assertTrue(hits.isNotEmpty())
  val info=NovelSources.details(source,hits.first().url);val text=NovelSources.content(source,info.chapters.first());assertTrue(text.length>80)
  println("RSS_LIVE source=${source.id} count=${hits.size} first=${hits.first().name} chars=${text.length} readerConsumed=true")
 }
}
