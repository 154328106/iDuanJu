/* Semantic port of legado-E BookSourceType.kt and model/webBook/BookContent.kt
 * at 8b87c5aba4df91c39a3a0939a68a1180b9f2ee1c (GPL-3.0).
 * Audio/video are URL results, never cleaned text. See UPSTREAM-PORT.md. */
import Foundation

struct LegadoMedia:Identifiable {
    let url:URL
    let headers:[String:String]
    let sourceType:Int
    var id:String{url.absoluteString}
    static func sameOrigin(_ a:URL,_ b:URL)->Bool{
        func port(_ u:URL)->Int{u.port ?? (u.scheme?.lowercased()=="https" ? 443 : 80)}
        return a.scheme?.lowercased()==b.scheme?.lowercased() && a.host?.lowercased()==b.host?.lowercased() && port(a)==port(b)
    }
    static func checkedURL(_ raw:String)throws->URL {
        guard let url=URL(string:raw),let rawHost=url.host,["https","http"].contains(url.scheme ?? ""),url.user==nil,url.password==nil,
            url.port==nil || url.port == (url.scheme=="https" ? 443 : 80) else{throw LegadoError.rule("媒体地址、端口或凭证不允许")}
        let host=rawHost.trimmingCharacters(in:CharacterSet(charactersIn:"[]"))
        guard !NetGuard.isBlockedIP(host),!NetGuard.resolvesToBlocked(host) else {throw LegadoError.rule("媒体地址指向内网")}
        return url
    }
    static func resolve(_ source:LegadoSource,chapter:LegadoChapter,book:[String:Any]=[:],nextChapterURL:String?=nil)throws->LegadoMedia {
        guard source.isMedia else{throw LegadoError.rule("不是有声/视频书源")}
        let rules=source.ruleContent ?? [:]
        let ctx=LegadoContext(source:source);ctx.book=book;ctx.chapter=["title":chapter.title,"url":chapter.url,"index":chapter.index ?? 0];ctx.nextChapterURL=nextChapterURL
        let chapterRule=LegadoClient.firstRequestURL(chapter.url)
        let chapterOptions:[String:Any]=LegadoURL.optionsStart(chapterRule).flatMap{LegadoURL.lenientObject(String(chapterRule[chapterRule.index(after:$0.lowerBound)...]),ctx:ctx)} ?? [:]
        // Upstream AnalyzeUrl: webJs/sourceRegex only apply when the URL option enables webView.
        let web=LegadoBackstage.useWebView(chapterOptions["webView"])
        let raw:String
        // Exact AnalyzeRule baseUrl identity has no body dependency; retain requests if loginCheckJs has effects.
        let contentRule=rules["content"] ?? ""
        let identity = !web && (source.loginCheckJs ?? "").trimmingCharacters(in:.whitespacesAndNewlines).isEmpty && contentRule.trimmingCharacters(in:.whitespacesAndNewlines).range(of:#"^(?:@js:\s*baseUrl\s*;?\s*|<js>\s*baseUrl\s*;?\s*</js>)$"#,options:.regularExpression) != nil
        if !contentRule.isEmpty && !identity {
            let rule=contentRule
            let response=web ? try backstage(source,rules:rules,chapterURL:chapter.url,options:chapterOptions,ctx:ctx) : try LegadoClient.loginChecked(source,try LegadoURL.request(chapter.url,ctx:ctx,base:source.id).fetch(),ctx:ctx)
            // Same fork contract as upstream: preserve ampersands and URL option JSON, no HTML formatter.
            let value=try LegadoAnalyzer(response.text,baseURL:response.url.absoluteString,ctx:ctx).string(rule)
            raw=LegadoURL.absolute(LegadoClient.firstRequestURL(value),base:response.url.absoluteString)
        }else{raw=chapter.url}
        let request=try LegadoURL.request(raw,ctx:ctx,base:source.id)
        guard request.request.httpMethod==nil || request.request.httpMethod=="GET" else{throw LegadoError.rule("媒体暂不支持POST，不会忽略请求方法")}
        guard let requestURL=request.request.url else{throw LegadoError.rule("媒体地址为空")}
        let url=try checkedURL(requestURL.absoluteString)
        let same=URL(string:source.id).map{sameOrigin(url,$0)} ?? false
        let headers=(request.request.allHTTPHeaderFields ?? [:]).filter{ key,value in
            !["host","connection","content-length","transfer-encoding","proxy-authorization"].contains(key.lowercased()) && (same || !["authorization","cookie"].contains(key.lowercased()))
        }
        guard headers.allSatisfy({key,value in !key.contains(where:{$0.isWhitespace || $0==":"}) && !value.contains(where:{$0=="\r" || $0=="\n" || $0=="\0"})}) else {throw LegadoError.rule("媒体header含控制字符")}
        return LegadoMedia(url:url,headers:headers,sourceType:source.bookSourceType ?? 0)
    }
    private static func backstage(_ source:LegadoSource,rules:[String:String],chapterURL:String,options:[String:Any],ctx:LegadoContext)throws->LegadoResponse {
        let request=try LegadoURL.request(chapterURL,ctx:ctx,base:source.id)
        guard request.request.httpMethod==nil || request.request.httpMethod=="GET" else{throw LegadoError.rule("WebView媒体解析暂不支持POST，不会忽略请求方法")}
        guard let raw=request.request.url else{throw LegadoError.rule("WebView 页面地址为空")}
        let page=try checkedURL(raw.absoluteString)
        let same=URL(string:source.id).map{sameOrigin(page,$0)} ?? false
        let headers=(request.request.allHTTPHeaderFields ?? [:]).filter{key,_ in
            !["host","connection","content-length","transfer-encoding","proxy-authorization"].contains(key.lowercased()) && (same || !["authorization","cookie"].contains(key.lowercased()))
        }
        guard headers.allSatisfy({key,value in !key.contains(where:{$0.isWhitespace || $0==":"}) && !value.contains(where:{$0=="\r" || $0=="\n" || $0=="\0"})}) else {throw LegadoError.rule("WebView header含控制字符")}
        func text(_ v:Any?)->String?{guard let v,!(v is NSNull) else{return nil};let s="\(v)".trimmingCharacters(in:.whitespacesAndNewlines);return s.isEmpty || s=="null" ? nil : s}
        let js=text(options["webJs"]) ?? text(rules["webJs"])
        let regex=text(rules["sourceRegex"])
        let delay=min(max((options["webViewDelayTime"] as? NSNumber)?.intValue ?? Int(text(options["webViewDelayTime"]) ?? "") ?? 0,0),30000)
        return try LegadoBackstage.load(LegadoBackstageRequest(url:page,headers:headers,javaScript:js,sourceRegex:regex,delayMs:delay))
    }
}
