/* Semantic port of legado-E BookSourceType.kt and model/webBook/BookContent.kt
 * at 8b87c5aba4df91c39a3a0939a68a1180b9f2ee1c (GPL-3.0).
 * Audio/video are URL results, never cleaned text. See UPSTREAM-PORT.md. */
import Foundation

struct LegadoMedia:Identifiable {
    let url:URL
    let headers:[String:String]
    let sourceType:Int
    var id:String{url.absoluteString}
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
        guard (rules["webJs"] ?? "").isEmpty else{throw LegadoError.rule("媒体源依赖WebView webJs，尚未实现")}
        let ctx=LegadoContext(source:source);ctx.book=book;ctx.chapter=["title":chapter.title,"url":chapter.url,"index":chapter.index ?? 0];ctx.nextChapterURL=nextChapterURL
        let raw:String
        if let rule=rules["content"],!rule.isEmpty {
            let response=try LegadoClient.loginChecked(source,try LegadoURL.request(chapter.url,ctx:ctx,base:source.id).fetch(),ctx:ctx)
            // Same fork contract as upstream: preserve ampersands and URL option JSON, no HTML formatter.
            let value=try LegadoAnalyzer(response.text,baseURL:response.url.absoluteString,ctx:ctx).string(rule)
            raw=LegadoURL.absolute(LegadoClient.firstRequestURL(value),base:response.url.absoluteString)
        }else{raw=chapter.url}
        let request=try LegadoURL.request(raw,ctx:ctx,base:source.id)
        guard request.request.httpMethod==nil || request.request.httpMethod=="GET" else{throw LegadoError.rule("媒体暂不支持POST，不会忽略请求方法")}
        guard let requestURL=request.request.url else{throw LegadoError.rule("媒体地址为空")}
        let url=try checkedURL(requestURL.absoluteString)
        let same=url.host==URL(string:source.id)?.host
        let headers=(request.request.allHTTPHeaderFields ?? [:]).filter{ key,value in
            !["host","connection","content-length","transfer-encoding","proxy-authorization"].contains(key.lowercased()) && (same || !["authorization","cookie"].contains(key.lowercased()))
        }
        guard headers.allSatisfy({key,value in !key.contains(where:{$0.isWhitespace || $0==":"}) && !value.contains(where:{$0=="\r" || $0=="\n" || $0=="\0"})}) else {throw LegadoError.rule("媒体header含控制字符")}
        return LegadoMedia(url:url,headers:headers,sourceType:source.bookSourceType ?? 0)
    }
}
