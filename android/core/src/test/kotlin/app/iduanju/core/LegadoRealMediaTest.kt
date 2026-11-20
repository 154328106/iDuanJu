package app.iduanju.core
import app.iduanju.core.novel.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
/** Private source definitions are explicitly opt-in and never distributed. No WAF/auth/DRM helpers. */
class LegadoRealMediaTest {
 @After fun cleanup(){NovelSources.requestObserver=null;NovelSources.fixture=null}
 @Test fun personalPublicFreeAudioSearchDetailTocResolve(){
  val path=System.getenv("IDJ_PERSONAL_AUDIO_JSON");Assume.assumeTrue(!path.isNullOrBlank())
  val source=NovelSources.import(File(path!!).readText()).single();assertTrue(source.isMedia)
  NovelSources.requestObserver={method,url,_->println("ACTUAL_REQUEST $method ${url.substringBefore('?')}")}
  val catalog=NovelSources.search(source,"",category=NovelSources.categories(source).first());assertTrue(catalog.isNotEmpty());println("REAL_AUDIO_DISCOVERY count=${catalog.size}")
  val hits=NovelSources.search(source,"论语");assertTrue(hits.isNotEmpty())
  val hit=hits.first{it.name=="《论语》朗读与讲解"};val detail=NovelSources.details(source,hit.url);assertTrue(detail.chapters.isNotEmpty())
  val media=LegadoMedia.resolve(source,detail.chapters.first(),hit.url);assertTrue(media.url.startsWith("http"));println("REAL_FREE_AUDIO title=${hit.name} chapters=${detail.chapters.size} mediaHost=${java.net.URI(media.url).host}")
 }
 @Test fun nasaRealAnimationCatalogDetailTocResolve(){
  Assume.assumeTrue(System.getenv("IDJ_REAL_NASA_VIDEO")=="1")
  val source=NovelSources.import(javaClass.getResource("/nasa-animation-public/sources.json")!!.readText()).single()
  val hits=NovelSources.search(source,"",category=NovelSources.categories(source).first());assertTrue(hits.isNotEmpty())
  for(hit in hits.take(3)){
   val detail=NovelSources.details(source,hit.url);assertTrue(detail.chapters.isNotEmpty())
   val media=LegadoMedia.resolve(source,detail.chapters.first(),hit.url);assertEquals("images-assets.nasa.gov",java.net.URI(media.url).host);assertEquals("https",java.net.URI(media.url).scheme);println("REAL_NASA_ANIMATION title=${hit.name} chapters=${detail.chapters.size}")
  }
 }
 @Test fun nasaRealWebViewConfigsReachBackstageWithActualPage(){
  Assume.assumeTrue(System.getenv("IDJ_REAL_NASA_VIDEO")=="1")
  try{
   for(folder in listOf("nasa-webview-public","nasa-webview-sniff-public")){
    val source=NovelSources.import(javaClass.getResource("/$folder/sources.json")!!.readText()).single()
    val hit=NovelSources.search(source,"",category=NovelSources.categories(source).first()).first()
    val detail=NovelSources.details(source,hit.url);assertEquals(1,detail.chapters.size)
    var seen:BackstageRequest?=null
    LegadoWebView.backstage=LegadoBackstage{seen=it;error("JVM has no WebView; native tests consume the page")}
    assertTrue(runCatching{LegadoMedia.resolve(source,detail.chapters.first(),hit.url)}.isFailure)
    val r=seen!!;assertTrue(r.url,r.url.startsWith("https://images.nasa.gov/details/"));assertTrue((r.javaScript!=null)xor(r.sourceRegex!=null))
    println("REAL_NASA_WEBVIEW folder=$folder title=${hit.name} page=${r.url}")
   }
  }finally{LegadoWebView.backstage=null}
 }
}
