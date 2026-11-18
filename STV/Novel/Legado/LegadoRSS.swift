/* Semantic port of legado-E RssParserDefault.kt / RssParserByRule.kt / Rss.kt /
 * RssSourceExtensions.kt at 8b87c5aba4df91c39a3a0939a68a1180b9f2ee1c, GPL-3.0.
 * Modified 2026-11-18: Foundation XML delegate; existing sandbox/HTTP/persistence;
 * source dispatch through existing detail/reader UI. See UPSTREAM-PORT.md. */
import Foundation
#if canImport(FoundationXML)
import FoundationXML
#endif

enum LegadoRSS {
    struct Article: Codable {
        var title = ""; var link = ""; var pubDate = ""; var image = ""
        var description: String?; var content: String?; var read = false
    }
    private struct Cache: Codable { var articles:[String:Article] = [:]; var next:[String:String] = [:] }
    private static func cache(_ s:LegadoSource)->Cache {
        (LegadoVars.get("rss|"+s.id,"articles")).flatMap{try? JSONDecoder().decode(Cache.self,from:Data($0.utf8))} ?? Cache()
    }
    private static func save(_ s:LegadoSource,_ c:Cache) throws {
        let encoded=String(decoding:try JSONEncoder().encode(c),as:UTF8.self)
        LegadoVars.update("rss|"+s.id,"articles"){raw in
            let old=raw.flatMap{try? JSONDecoder().decode(Cache.self,from:Data($0.utf8))} ?? Cache()
            var latest=c
            for key in latest.articles.keys where old.articles[key]?.read == true{latest.articles[key]?.read=true}
            return (try? JSONEncoder().encode(latest)).map{String(decoding:$0,as:UTF8.self)} ?? encoded
        }
    }
    static func categories(_ s:LegadoSource)throws->[LegadoClient.ExploreCategory] {
        var raw=s.rssRules?["sortUrl"] ?? ""
        if raw.hasPrefix("@js:") || raw.hasPrefix("<js>") {
            raw=LegadoAnalyzer.textOf(try LegadoContext(source:s).evalJS(raw.replacingOccurrences(of:"^(@js:|<js>)|</js>$",with:"",options:.regularExpression),result:nil,baseURL:s.id))
        }
        let entries=raw.components(separatedBy:"&&").flatMap{$0.components(separatedBy:.newlines)}.compactMap { item -> LegadoClient.ExploreCategory? in
            guard let r=item.range(of:"::"), !item[r.upperBound...].isEmpty else{return nil}
            let title=String(item[..<r.lowerBound]);return .init(title:title.isEmpty ? s.bookSourceName : title,url:String(item[r.upperBound...]),group:"订阅")
        }
        return entries.isEmpty ? [.init(title:s.bookSourceName,url:s.id,group:"订阅")] : entries
    }
    static func parse(_ s:LegadoSource,body:String,sortURL:String,redirectURL:String)throws->([Article],String?) {
        let rules=s.rssRules ?? [:];let rule=rules["ruleArticles"] ?? ""
        guard !body.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty else{throw LegadoError.rule("订阅响应为空")}
        if rule.isEmpty {
            guard body.range(of:"<!DOCTYPE|<!ENTITY",options:[.regularExpression,.caseInsensitive]) == nil else {throw LegadoError.rule("RSS 不允许外部实体/DTD")}
            let delegate=FeedParser();let parser=XMLParser(data:Data(body.utf8));parser.shouldResolveExternalEntities=false;parser.delegate=delegate
            guard parser.parse() else{throw parser.parserError ?? LegadoError.rule("RSS XML解析失败")}
            return (delegate.articles.compactMap { item in
                var a=item;a.link=LegadoURL.absolute(a.link,base:redirectURL);a.image=LegadoURL.absolute(HTMLText.decodeEntities(a.image),base:redirectURL)
                return a.title.isEmpty || a.link.isEmpty ? nil : a
            },nil)
        }
        let ctx=LegadoContext(source:s);let page=LegadoAnalyzer(body,baseURL:redirectURL,ctx:ctx)
        let nextRule=rules["ruleNextPage"] ?? ""
        let next:String?
        if nextRule.isEmpty {next=nil}
        else if nextRule.uppercased()=="PAGE" {next=sortURL}
        else {let value=try page.string(nextRule);next=value.isEmpty ? nil : LegadoURL.absolute(value,base:redirectURL)}
        // Same as upstream: '-' reverses elements; absent description triggers content-page parsing.
        var result:[Article]=[]
        for el in try page.elements(rule) {
            let a=LegadoAnalyzer(el,baseURL:redirectURL,ctx:ctx)
            func field(_ key:String)throws->String{try a.string(rules[key] ?? "")}
            let title=try field("ruleTitle");if title.isEmpty{continue}
            let link=LegadoURL.absolute(LegadoClient.firstRequestURL(try field("ruleLink")),base:redirectURL);if link.isEmpty{continue}
            result.append(Article(title:title,link:link,pubDate:try field("rulePubDate"),image:LegadoURL.absolute(try field("ruleImage"),base:s.id),description:(rules["ruleDescription"] ?? "").isEmpty ? nil : try field("ruleDescription")))
        }
        return(result,next)
    }
    static func list(_ s:LegadoSource,url:String?,key:String,page:Int)throws->[LegadoSearchItem] {
        if !key.isEmpty && (s.searchUrl ?? "").isEmpty {throw LegadoError.rule("此订阅源没有搜索规则")}
        let raw=try url ?? categories(s).first!.url
        var c=cache(s)
        guard page==1 || !(c.next[raw] ?? "").isEmpty else{return []}
        let address=page==1 ? raw : c.next[raw]!
        let ctx=LegadoContext(source:s);ctx.key=key;ctx.page=page
        let response=try LegadoClient.loginChecked(s,try LegadoURL.request(address,ctx:ctx,base:s.id).fetch(),ctx:ctx)
        let (rows,next)=try parse(s,body:response.text,sortURL:raw,redirectURL:response.url.absoluteString)
        for var a in rows {a.read=c.articles[a.link]?.read ?? false;c.articles[a.link]=a}
        if c.articles.count>200 {let keep=Set(rows.map(\.link));for k in c.articles.keys.sorted() where !keep.contains(k) {if c.articles.count<=200{break};c.articles.removeValue(forKey:k)}}
        c.next[raw]=next ?? "";try save(s,c)
        var seen=Set<String>()
        return rows.filter{seen.insert($0.link).inserted}.map{LegadoSearchItem(name:$0.title,author:$0.pubDate,kind:"RSS",intro:$0.description ?? "",cover:$0.image,latest:"",bookURL:$0.link,sourceURL:s.id,sourceName:s.bookSourceName)}
    }
    private static func article(_ s:LegadoSource,_ url:String)throws->Article {
        guard let a=cache(s).articles[url] else{throw LegadoError.rule("文章缓存不存在，请先刷新该订阅分类")};return a
    }
    static func info(_ s:LegadoSource,url:String)throws->LegadoBookInfo {
        let a=try article(s,url);return .init(name:a.title,author:a.pubDate,intro:LegadoContent.clean(a.description ?? ""),cover:a.image,kind:"RSS",latest:"",tocURL:url)
    }
    static func content(_ s:LegadoSource,url:String)throws->String {
        let a=try article(s,url);let rule=s.rssRules?["ruleContent"] ?? "";let inline=a.content ?? a.description ?? ""
        let raw:String
        if rule.isEmpty && !inline.isEmpty{raw=inline}else{
            let ctx=LegadoContext(source:s);ctx.book=["bookUrl":url]
            ctx.rssArticle=["origin":s.rssRules?["sourceUrl"] ?? s.bookSourceUrl,"link":a.link,"title":a.title,"pubDate":a.pubDate,"image":a.image,"description":a.description.map{$0 as Any} ?? NSNull(),"content":a.content.map{$0 as Any} ?? NSNull()]
            let response=try LegadoClient.loginChecked(s,try LegadoURL.request(url,ctx:ctx,base:s.id).fetch(),ctx:ctx)
            if rule.isEmpty && response.text.range(of:"<(?:!doctype|html|body)\\b",options:[.regularExpression,.caseInsensitive]) != nil {
                let html=try LegadoAnalyzer(response.text,baseURL:response.url.absoluteString,ctx:ctx).string("tag.body@html")
                guard !LegadoContent.clean(html).isEmpty else{throw LegadoError.rule("订阅页面仅 JavaScript 外壳，需适配 ruleContent；未提取到文章正文")}
            }
            raw=try rule.isEmpty ? response.text : LegadoAnalyzer(response.text,baseURL:response.url.absoluteString,ctx:ctx).string(rule)
        }
        let text=LegadoContent.clean(raw);guard !text.isEmpty else{throw LegadoError.rule("订阅正文为空")}
        var c=cache(s);c.articles[url]?.read=true;try save(s,c);return text
    }
    /// Swift equivalent of the copied Kotlin RssParserDefault: CDATA, enclosure image,
    /// media:thumbnail, content:encoded, pubDate/time and item boundaries.
    private final class FeedParser:NSObject,XMLParserDelegate {
        var articles:[Article]=[];var current:Article?;var stack:[String]=[];var buffers:[String]=[]
        func parser(_ parser:XMLParser,didStartElement name:String,namespaceURI:String?,qualifiedName:String?,attributes:[String:String]) {
            stack.append(name);buffers.append("")
            if name.lowercased()=="item"{current=Article()}
            if name.lowercased()=="media:thumbnail"{current?.image=attributes["url"] ?? ""}
            if name.lowercased()=="enclosure",attributes["type"]?.contains("image/")==true{current?.image=attributes["url"] ?? ""}
        }
        func parser(_ parser:XMLParser,foundCharacters value:String){if !buffers.isEmpty{buffers[buffers.count-1]+=value}}
        func parser(_ parser:XMLParser,foundCDATA data:Data){self.parser(parser,foundCharacters:String(decoding:data,as:UTF8.self))}
        func parser(_ parser:XMLParser,didEndElement name:String,namespaceURI:String?,qualifiedName:String?) {
            let text=(buffers.popLast() ?? "").trimmingCharacters(in:.whitespacesAndNewlines);_ = stack.popLast()
            guard current != nil else{return}
            switch name.lowercased(){
            case "title":current?.title=text
            case "link":current?.link=text
            case "pubdate","time":current?.pubDate=text
            case "description":current?.description=text;image(text)
            case "content:encoded":current?.content=text;image(text)
            case "item":articles.append(current!);current=nil
            default:break
            }
        }
        private func image(_ value:String){
            if current?.image.isEmpty==true,let regex=try? NSRegularExpression(pattern:"<img [^>]*src\\s*=\\s*\"([^\"]+)\""),let m=regex.firstMatch(in:value,range:NSRange(value.startIndex...,in:value)),let r=Range(m.range(at:1),in:value){current?.image=String(value[r])}
        }
    }
}
