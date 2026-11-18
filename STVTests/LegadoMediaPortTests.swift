import XCTest
@testable import STV
final class LegadoMediaPortTests:XCTestCase {
 override func tearDown(){PluginFixtures.responder=nil;LegadoHTTP.transport=nil;NetGuard.resolverOverride=nil;super.tearDown()}
 func testSharedAudioVideoCatalogDetailTocAndRawMediaContract()throws{
  NetGuard.resolverOverride={_ in ["8.8.8.8"]}
  let root=URL(fileURLWithPath:#filePath).deletingLastPathComponent().deletingLastPathComponent()
  let cases=try JSONSerialization.jsonObject(with:Data(contentsOf:root.appendingPathComponent("shared/legado-port/media-cases.json"))) as! [[String:Any]]
  for c in cases {
   let s=try LegadoSourceStore.parse(JSONSerialization.data(withJSONObject:c["source"]!)).first!;XCTAssertTrue(s.isMedia);XCTAssertTrue(s.supported)
   LegadoHTTP.transport={req in let path=req.url!.path;let name=path=="/catalog" ? "catalog" : path=="/book" ? "detail" : path=="/toc" ? "toc" : "chapter";return(Data((c[name] as! String).utf8),HTTPURLResponse(url:req.url!,statusCode:200,httpVersion:nil,headerFields:nil)!)}
   let hit=try LegadoClient.explore(s,category:LegadoClient.exploreCategories(s)[0])[0]
   let info=try LegadoClient.info(s,bookURL:hit.bookURL);XCTAssertEqual(info.name,"Owned media book")
   let toc=try LegadoClient.toc(s,tocURL:info.tocURL);XCTAssertEqual(toc.count,1)
   let media=try LegadoMedia.resolve(s,chapter:toc[0],book:["bookUrl":hit.bookURL]);XCTAssertEqual(media.url.absoluteString,c["expectedURL"] as? String)
   XCTAssertEqual(media.headers.first{$0.key.lowercased()=="referer"}?.value,"https://example.org/")
   XCTAssertFalse(media.headers.keys.contains{["authorization","cookie"].contains($0.lowercased())})
   XCTAssertThrowsError(try LegadoClient.content(s,chapter:toc[0],nextChapterURL:nil))
   var raw=c["source"] as! [String:Any];raw["ruleContent"]=["content":c["contextRule"]!]
   let contextualSource=try LegadoSourceStore.parse(JSONSerialization.data(withJSONObject:raw)).first!
   let contextual=try LegadoMedia.resolve(contextualSource,chapter:.init(title:"episode",url:toc[0].url,index:7),nextChapterURL:"https://example.org/next")
   XCTAssertEqual(contextual.url.absoluteString,c["contextExpectedURL"] as? String)
  }
 }
 func testMediaWebJsIsNotPretendedUsableAtImport()throws{
  let s=try LegadoSourceStore.parse(Data(#"{"bookSourceUrl":"https://example.org","bookSourceType":4,"exploreUrl":"/catalog","ruleContent":{"webJs":"document.title"}}"#.utf8)).first!
  XCTAssertTrue(s.unsupportedMediaWebJS);XCTAssertFalse(s.supported)
 }
 func testDirectMediaAndSafetyContract()throws{
  NetGuard.resolverOverride={_ in ["8.8.8.8"]}
  XCTAssertTrue(LegadoMedia.sameOrigin(URL(string:"https://media.example.org/file")!,URL(string:"https://media.example.org:443/root")!))
  XCTAssertFalse(LegadoMedia.sameOrigin(URL(string:"https://media.example.org/file")!,URL(string:"http://media.example.org/root")!))
  let s=try LegadoSourceStore.parse(Data(#"{"bookSourceUrl":"https://direct.example.org","bookSourceType":1}"#.utf8)).first!
  XCTAssertEqual(try LegadoMedia.resolve(s,chapter:.init(title:"one",url:"https://direct.example.org/one.mp3")).url.absoluteString,"https://direct.example.org/one.mp3")
  for raw in ["file:///etc/passwd","http://127.0.0.1/x","http://[::1]/x","https://u:p@example.org/x","https://example.org:80/x"]{XCTAssertThrowsError(try LegadoMedia.checkedURL(raw))}
 }
 func testGuardedProxyRewritesAndBlocksPrivateOrUnlistedHLSResources()async throws{
  NetGuard.resolverOverride={_ in ["8.8.8.8"]}
  PluginFixtures.responder={req in
   if req.url!.path=="/master.m3u8" {return .init(headers:["content-type":"application/vnd.apple.mpegurl"],body:Data("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nhttp://127.0.0.1/private.ts\n#EXT-X-ENDLIST\n".utf8))}
   XCTFail("private/unlisted request must be blocked before transport");return nil
  }
  let local=try LocalHLSProxy.shared.legadoURL(.init(url:URL(string:"https://media.example.org/master.m3u8")!,headers:[:],sourceType:4))
  defer{if let token=LocalHLSProxy.token(of:local){LocalHLSProxy.shared.release(token)}}
  let (manifest,response)=try await URLSession.shared.data(from:URL(string:local)!)
  XCTAssertEqual((response as? HTTPURLResponse)?.statusCode,200)
  let segment=try XCTUnwrap(String(decoding:manifest,as:UTF8.self).split(separator:"\n").first{!$0.hasPrefix("#")})
  XCTAssertTrue(segment.hasPrefix("http://127.0.0.1:"));XCTAssertFalse(segment.contains("private.ts"))
  let (_,blocked)=try await URLSession.shared.data(from:URL(string:String(segment))!)
  XCTAssertEqual((blocked as? HTTPURLResponse)?.statusCode,502)
  XCTAssertThrowsError(try LocalHLSProxy.shared.legadoURL(.init(url:URL(string:"https://media.example.org/file.mp3")!,headers:["Cookie":"fake-test"],sourceType:1)))
 }

 func testStandalonePublicDemoConfigsNeedNoFixtureServer()throws{
  NetGuard.resolverOverride={_ in ["8.8.8.8"]}
  LegadoHTTP.transport={_ in XCTFail("Static catalog must not contact a fixture server");throw URLError(.badURL)}
  let root=URL(fileURLWithPath:#filePath).deletingLastPathComponent().deletingLastPathComponent()
  for folder in ["audio-static-public","video-static-public"]{
   let s=try LegadoSourceStore.parse(Data(contentsOf:root.appendingPathComponent("shared/legado-port/"+folder+"/sources.json"))).first!
   let hit=try LegadoClient.explore(s,category:LegadoClient.exploreCategories(s)[0])[0]
   let info=try LegadoClient.info(s,bookURL:hit.bookURL);let toc=try LegadoClient.toc(s,tocURL:info.tocURL)
   let media=try LegadoMedia.resolve(s,chapter:toc[0],book:["bookUrl":hit.bookURL]);XCTAssertEqual(media.url.scheme,"https");XCTAssertEqual(media.sourceType,s.bookSourceType)
  }
 }

 func testIdentityMediaRulesNeverDownloadTheWholeRecording()throws{
  let old=NetGuard.resolverOverride;NetGuard.resolverOverride={_ in ["8.8.8.8"]};defer{NetGuard.resolverOverride=old;LegadoHTTP.transport=nil}
  LegadoHTTP.transport={_ in XCTFail("Identity must not fetch media bytes");throw LegadoError.rule("unexpected fetch")}
  let url=URL(fileURLWithPath:#filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("shared/legado-port/media-identity-cases.json")
  for c in try JSONSerialization.jsonObject(with:Data(contentsOf:url)) as! [[String:String]]{
   let raw:[String:Any] = ["bookSourceUrl":"https://media.example.org","bookSourceType":1,"ruleContent":["content":c["rule"]!]]
   let source=try LegadoSourceStore.parse(JSONSerialization.data(withJSONObject:raw)).first!
   XCTAssertEqual(try LegadoMedia.resolve(source,chapter:LegadoChapter(title:"episode",url:c["url"]!)).url.absoluteString,c["url"])
  }
 }
 func testJSONResultKeepsPropertiesAndUpstreamStringCoercion()throws{
  let source=try LegadoSourceStore.parse(Data(#"{"bookSourceUrl":"https://example.org"}"#.utf8)).first!
  let url=URL(fileURLWithPath:#filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("shared/legado-port/json-result-coercion-cases.json")
  for c in try JSONSerialization.jsonObject(with:Data(contentsOf:url)) as! [[String:Any]]{XCTAssertEqual(try LegadoContext(source:source).evalJS(c["script"] as! String,result:c["input"],baseURL:source.id) as? String,c["expected"] as? String)}
 }
 func testMissingNonVolumeChapterURLUsesTocBaseLikeUpstream()throws{
  LegadoHTTP.transport={r in (Data("<h2>Original public podcast</h2>".utf8),HTTPURLResponse(url:r.url!,statusCode:200,httpVersion:nil,headerFields:nil)!)}
  let source=try LegadoSourceStore.parse(Data(#"{"bookSourceUrl":"https://example.org","bookSourceType":1,"ruleToc":{"chapterList":"tag.h2","chapterName":"text"}}"#.utf8)).first!
  XCTAssertEqual(try LegadoClient.toc(source,tocURL:"https://example.org/episode").first?.url,"https://example.org/episode")
 }
}
