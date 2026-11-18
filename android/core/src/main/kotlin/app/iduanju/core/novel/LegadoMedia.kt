/* Adapts legado-E model/webBook/BookContent.kt lines226-235 (same fixed SHA in
 * UPSTREAM-PORT.md): audio/video results are URLs, NOT HTML-cleaned chapter text.
 * Adds host URL/header projection for the existing restricted runtime and native player. GPL-3.0. */
package app.iduanju.core.novel

import app.iduanju.core.util.NetGuard
import org.json.JSONObject
import java.net.URI

data class NovelMedia(val url:String,val headers:Map<String,String>,val sourceType:Int)
object LegadoMedia {
    fun sameOrigin(first:String,second:String):Boolean=runCatching{
        val a=URI(first);val b=URI(second)
        fun port(u:URI)=if(u.port!=-1)u.port else if(u.scheme.equals("https",true))443 else 80
        a.scheme.equals(b.scheme,true)&&a.host.equals(b.host,true)&&port(a)==port(b)
    }.getOrDefault(false)
    fun checkedURL(raw:String):String {
        val u=URI(raw);require(u.scheme in listOf("https","http")&&!u.host.isNullOrBlank()&&u.userInfo==null&&(u.port == -1 || u.port == (if(u.scheme=="https")443 else 80))){"媒体地址/端口或凭证不允许"}
        require(!u.host.equals("localhost",true)&&!(if(NetGuard.isIPLiteral(u.host))NetGuard.isBlockedIP(u.host)else NetGuard.resolvesToBlocked(u.host))){"媒体地址指向内网"}
        return u.toASCIIString()
    }
    fun resolve(source:NovelSource,chapter:NovelChapter,bookURL:String?=null,nextChapterURL:String?=null):NovelMedia=NovelScript.withBook(bookURL){ NovelScript.withChapter(chapter,nextChapterURL){
        require(source.isMedia){"不是有声/视频书源"}
        val rule=source.rules("ruleContent")
        require(rule.optString("webJs").isBlank()){ "此媒体源依赖WebView webJs，尚未实现；不能假装已解析" }
        val contentRule=rule.optString("content")
        // AnalyzeRule binds baseUrl to this request. An exact identity script with no
        // login-check side effects needs no response body; never download a whole audiobook to return its URL.
        val identity=source.json.optString("loginCheckJs").isBlank() && Regex("^(?:@js:\\s*baseUrl\\s*;?\\s*|<js>\\s*baseUrl\\s*;?\\s*</js>)$").matches(contentRule.trim())
        val raw=if(contentRule.isBlank() || identity)chapter.url else {
            val response=NovelSources.page(chapter.url,source)
            // Upstream intentionally does not run HTML text cleanup for audio/video.
            NovelSources.ruleValue(NovelSources.document(response.text),contentRule,source,response.url)
                .let{NovelSources.absolute(NovelSources.firstRequestURL(it),response.url)}
        }
        val pieces=NovelSources.firstRequestURL(raw).split(Regex(",\\s*(?=\\{)"),limit=2)
        val options=if(pieces.size>1)JSONObject(pieces[1])else JSONObject()
        require(options.optString("method","GET").equals("GET",true)){"媒体播放暂不支持POST，不会忽略请求方法"}
        val url=checkedURL(NovelSources.absolute(pieces[0],source.id))
        val headers=NovelSources.headers(source)
        options.optJSONObject("headers")?.let{extra->for(k in extra.keys())headers.put(k,extra.get(k))}
        val same=sameOrigin(url,source.id.substringBefore(','))
        val forbidden=setOf("host","connection","content-length","transfer-encoding","proxy-authorization")
        val projected=headers.keys().asSequence().associateWith{headers.get(it).toString()}.filter { (k,v)->
            require(k.none{it<=' '||it==':'}&&v.none{it=='\r'||it=='\n'||it=='\u0000'}){"媒体header含控制字符"}
            k.lowercase() !in forbidden && (same || k.lowercase() !in setOf("authorization","cookie"))
        }
        NovelMedia(url,projected,source.json.optInt("bookSourceType"))
    } }
}
