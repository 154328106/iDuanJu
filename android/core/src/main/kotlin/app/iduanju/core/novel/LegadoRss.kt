/* Adapted from legado-E RssParserByRule.kt, Rss.kt and RssSourceExtensions.kt,
 * upstream 8b87c5aba4df91c39a3a0939a68a1180b9f2ee1c, GPL-3.0.
 * Modifications: existing sandbox/analyzer and persistence bridge, no Room/Android logging.
 * See UPSTREAM-PORT.md and third_party/legado-E/LICENSE. */
package app.iduanju.core.novel

import app.iduanju.core.novel.rss.RssArticle
import app.iduanju.core.novel.rss.RssParserDefault
import org.json.JSONArray
import org.json.JSONObject

/** RSS is a separate source contract, consumed by the existing detail/reader UI, never a fake text rule. */
object LegadoRss {
    private const val CACHE="__rssArticles"
    private fun state(source:NovelSource)=runCatching{JSONObject(NovelScript.storage["${source.id}|$CACHE"]?:"{}")}.getOrDefault(JSONObject())
    private fun save(source:NovelSource,value:JSONObject)=NovelScript.updateSourceState(source.id,CACHE){raw->
        val old=runCatching{JSONObject(raw?:"{}").optJSONObject("articles")}.getOrNull()
        value.optJSONObject("articles")?.let{incoming->for(k in incoming.keys())if(old?.optJSONObject(k)?.optBoolean("read")==true)incoming.getJSONObject(k).put("read",true)}
        value.toString()
    }
    fun categories(source:NovelSource):List<NovelCategory> {
        var str=source.json.optString("sortUrl")
        if(str.startsWith("@js:")||str.startsWith("<js>"))str=NovelScript(source,source.id).eval(str.removePrefix("@js:").removePrefix("<js>").removeSuffix("</js>"))?.toString().orEmpty()
        // Directly adapted from RssSourceExtensions.sortUrls, including empty-category fallback.
        val result=str.split(Regex("(&&|\n)+")).mapNotNull { sort ->
            val name=sort.substringBefore("::");val url=sort.substringAfter("::","")
            if(url.isNotEmpty())NovelCategory(name.ifBlank{source.name},url,"订阅") else null
        }
        return result.ifEmpty{listOf(NovelCategory(source.name,source.id,"订阅"))}
    }
    fun parse(source:NovelSource,body:String,sortUrl:String,redirectUrl:String):Pair<List<RssArticle>,String?> {
        require(body.isNotBlank()){ "订阅响应为空" }
        val rule=source.json.optString("ruleArticles")
        if(rule.isBlank())return RssParserDefault.parseXML("",body,source.id).let { (a,next)->
            a.forEach{it.link=NovelSources.absolute(it.link,redirectUrl);it.image=it.image?.let{v->runCatching{NovelSources.absolute(org.jsoup.parser.Parser.unescapeEntities(v,false),redirectUrl)}.getOrDefault("")}}
            a.filter{it.title.isNotBlank()&&it.link.isNotBlank()} to next
        }
        // RssParserByRule: leading '-' reverses; PAGE repeats original recipe with incremented page.
        val doc=NovelSources.document(body)
        val next=source.json.optString("ruleNextPage").let { r ->when {
            r.isBlank()->null
            r.equals("PAGE",true)->sortUrl
            else->NovelSources.ruleValue(doc,r,source,redirectUrl).takeIf{it.isNotBlank()}?.let{NovelSources.absolute(it,redirectUrl)}
        }}
        val articles=NovelSources.nodes(doc,rule,source,redirectUrl).mapNotNull { item ->
            fun get(name:String)=NovelSources.ruleValue(item,source.json.optString(name),source,redirectUrl)
            val title=get("ruleTitle");if(title.isBlank())return@mapNotNull null
            // Description absent != blank description: absent means fetch detail using ruleContent.
            RssArticle(title=title,link=NovelSources.absolute(NovelSources.firstRequestURL(get("ruleLink")),redirectUrl),
                pubDate=get("rulePubDate"),description=source.json.optString("ruleDescription").takeIf{it.isNotBlank()}?.let{get("ruleDescription")},
                image=get("ruleImage").takeIf{it.isNotBlank()}?.let{NovelSources.absolute(it,source.id)},origin=source.id)
        }.filter{it.link.isNotBlank()}
        return articles to next
    }
    fun list(source:NovelSource,key:String,page:Int,category:NovelCategory?):List<NovelHit> {
        val raw=if(key.isNotBlank())source.json.optString("searchUrl").also{require(it.isNotBlank()){ "此订阅源没有搜索规则" }} else category?.url?:categories(source).first().url
        val cache=state(source);val pages=cache.optJSONObject("next")?:JSONObject()
        val address=if(page==1)raw else pages.optString(raw).takeIf{it.isNotBlank()}?:return emptyList()
        val response=NovelSources.page(address,source,key,page)
        val (rows,next)=parse(source,response.text,raw,response.url)
        val entries=cache.optJSONObject("articles")?:JSONObject()
        for(a in rows) {
            val prior=entries.optJSONObject(a.link)
            val row=JSONObject().put("title",a.title).put("link",a.link).put("pubDate",a.pubDate).put("image",a.image.orEmpty()).put("description",a.description?:JSONObject.NULL).put("content",a.content?:JSONObject.NULL).put("read",prior?.optBoolean("read")?:false)
            entries.put(a.link,row)
        }
        // Bound persistent feed cache, without discarding inline article body during a refresh.
        while(entries.length()>200)entries.remove(entries.keys().next())
        pages.put(raw,next.orEmpty());cache.put("articles",entries).put("next",pages);save(source,cache)
        return rows.map{NovelHit(it.title,it.pubDate,it.link,it.image.orEmpty(),"RSS","")}.distinctBy{it.url}
    }
    fun article(source:NovelSource,url:String):JSONObject = state(source).optJSONObject("articles")?.optJSONObject(url)
        ?: error("文章缓存不存在，请先刷新该订阅分类")
    fun details(source:NovelSource,url:String):NovelBookDetails {
        val a=article(source,url)
        return NovelBookDetails(a.getString("title"),a.optString("pubDate"),NovelSources.cleanText(if(a.isNull("description"))"" else a.optString("description")),a.optString("image"),"RSS","",listOf(NovelChapter(a.getString("title"),url)))
    }
    fun content(source:NovelSource,url:String):String =NovelScript.withBook(url){
        val a=JSONObject(article(source,url).toString()).put("origin",source.json.optString("sourceUrl",source.id))
        NovelScript.withRSSArticle(a){
        val rule=source.json.optString("ruleContent")
        val inline=if(!a.isNull("content"))a.optString("content") else if(!a.isNull("description"))a.optString("description") else ""
        val raw=if(rule.isBlank()&&inline.isNotBlank())inline else {
            val response=NovelSources.page(url,source)
            if(rule.isBlank())response.text else NovelSources.ruleValue(NovelSources.document(response.text),rule,source,response.url)
        }
        val text=NovelSources.cleanText(raw);require(text.isNotBlank()){ "订阅正文为空" }
        val cache=state(source);cache.getJSONObject("articles").getJSONObject(url).put("read",true);save(source,cache)
        text
    } }
}
