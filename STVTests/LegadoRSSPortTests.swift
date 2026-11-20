import XCTest
@testable import STV

final class LegadoRSSPortTests:XCTestCase {
    override func tearDown(){LegadoHTTP.transport=nil;super.tearDown()}
    private func cases()throws->[[String:Any]] {
        let root=URL(fileURLWithPath:#filePath).deletingLastPathComponent().deletingLastPathComponent()
        return try JSONSerialization.jsonObject(with:Data(contentsOf:root.appendingPathComponent("shared/legado-port/rss-cases.json"))) as! [[String:Any]]
    }
    func testSharedRssContractsConsumeBodyAndPersistReadState()throws {
        for c in try cases(){
            let source=try LegadoSourceStore.parse(JSONSerialization.data(withJSONObject:c["source"]!)).first!
            XCTAssertTrue(source.isRSS);XCTAssertTrue(source.supported)
            var requests:[String]=[]
            LegadoHTTP.transport={req in
                let url=req.url!;requests.append(url.absoluteString)
                let body=url.path.hasSuffix("article-api") ? c["apiBody"] as! String : url.path.contains("feed") ? c["body"] as! String : c["detailBody"] as! String
                return(Data(body.utf8),HTTPURLResponse(url:url,statusCode:200,httpVersion:nil,headerFields:nil)!)
            }
            let category=try LegadoClient.exploreCategories(source).first!
            let hits=try LegadoClient.explore(source,category:category)
            XCTAssertEqual(hits.map(\.name),c["expectedTitles"] as! [String]);XCTAssertEqual(hits.map(\.bookURL),c["expectedLinks"] as! [String])
            if let images=c["expectedImages"] as? [String]{XCTAssertEqual(hits.map(\.cover),images)}
            let info=try LegadoClient.info(source,bookURL:hits[0].bookURL);XCTAssertEqual(info.name,hits[0].name)
            let chapters=try LegadoClient.toc(source,tocURL:info.tocURL);XCTAssertEqual(chapters.count,1)
            XCTAssertEqual(try LegadoClient.content(source,chapter:chapters[0],nextChapterURL:nil),c["expectedText"] as! String)
            _ = try LegadoClient.explore(source,category:category)
            let persisted=UserDefaults.standard.dictionary(forKey:"stv.legado.vars") as! [String:[String:String]]
            let raw=persisted["rss|"+source.id]!["articles"]!
            let state=try JSONSerialization.jsonObject(with:Data(raw.utf8)) as! [String:Any]
            let articles=state["articles"] as! [String:[String:Any]]
            XCTAssertEqual(articles[hits[0].bookURL]?["read"] as? Bool,true)
            let next=try LegadoClient.explore(source,category:category,page:2)
            if c["name"] as! String == "rss-inline"{XCTAssertTrue(next.isEmpty)}else{XCTAssertEqual(next.count,2);XCTAssertTrue(requests.last!.hasSuffix("page=2"))}
        }
    }
    func testDTDsAreRejected()throws {
        let source=try LegadoSourceStore.parse(JSONSerialization.data(withJSONObject:cases()[0]["source"]!)).first!
        XCTAssertThrowsError(try LegadoRSS.parse(source,body:"<!DOCTYPE rss [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><rss/>",sortURL:source.id,redirectURL:source.id))
    }
 func testLivePersonalYckRSSUpdateAndNativeReaderPipeline()throws {
  guard let path=ProcessInfo.processInfo.environment["IDJ_PERSONAL_RSS_JSON"] else{throw XCTSkip("personal source stays outside repo; opt-in path required")}
  LegadoHTTP.transport=nil;NetGuard.resolverOverride=nil
  let source=try XCTUnwrap(LegadoSourceStore.parse(Data(contentsOf:URL(fileURLWithPath:path))).first)
  let category=try XCTUnwrap(LegadoClient.exploreCategories(source).first)
  let rows=try LegadoClient.explore(source,category:category);let first=try XCTUnwrap(rows.first)
  let info=try LegadoClient.info(source,bookURL:first.bookURL);let toc=try LegadoClient.toc(source,tocURL:info.tocURL)
  let text=try LegadoClient.content(source,chapter:XCTUnwrap(toc.first),nextChapterURL:nil);guard text.count>80 else{XCTFail("实际文章未消费：url=\(first.bookURL) chars=\(text.count) body=\(text.prefix(120))");return}
  let next=try LegadoClient.explore(source,category:category,page:2);XCTAssertFalse(next.isEmpty);XCTAssertNotEqual(next.first?.bookURL,first.bookURL)
  print("RSS_PERSONAL_LIVE source=\(source.bookSourceUrl) count=\(rows.count) next=\(next.count) title=\(first.name) chars=\(text.count) readerConsumed=true")
 }

 func testJavascriptShellDoesNotMasqueradeAsReadArticle()throws{
  let root=URL(fileURLWithPath:#filePath).deletingLastPathComponent().deletingLastPathComponent()
  let c=try JSONSerialization.jsonObject(with:Data(contentsOf:root.appendingPathComponent("shared/legado-port/rss-shell-case.json"))) as! [String:Any]
  let s=try LegadoSourceStore.parse(JSONSerialization.data(withJSONObject:c["source"]!)).first!
  LegadoHTTP.transport={req in let body=c[req.url!.path.hasSuffix("feed") ? "body" : "detailBody"] as! String;return(Data(body.utf8),HTTPURLResponse(url:req.url!,statusCode:200,httpVersion:nil,headerFields:nil)!)}
  let hit=try LegadoClient.explore(s,category:LegadoClient.exploreCategories(s)[0])[0]
  XCTAssertThrowsError(try LegadoClient.content(s,chapter:.init(title:hit.name,url:hit.bookURL),nextChapterURL:nil)){XCTAssertTrue($0.localizedDescription.contains("JavaScript"))}
 }

}
