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
 func testMediaWebJsImportsAndUsesUpstreamWebViewContract()throws{
  NetGuard.resolverOverride={_ in ["8.8.8.8"]}
  defer{LegadoBackstage.loader=nil}
  let imported=try LegadoSourceStore.parse(Data(#"{"bookSourceUrl":"https://example.org","bookSourceType":4,"exploreUrl":"/catalog","ruleContent":{"webJs":"document.title"}}"#.utf8)).first!
  XCTAssertTrue(imported.supported)
  let root=URL(fileURLWithPath:#filePath).deletingLastPathComponent().deletingLastPathComponent()
  let cases=try JSONSerialization.jsonObject(with:Data(contentsOf:root.appendingPathComponent("shared/legado-port/webview-media-cases.json"))) as! [[String:Any]]
  for c in cases {
   let name=c["name"] as! String;let e=c["expect"] as! [String:Any]
   var src=c["source"] as! [String:Any];if src["searchUrl"]==nil{src["searchUrl"]="/s"}
   let s=try LegadoSourceStore.parse(JSONSerialization.data(withJSONObject:src)).first!
   var seen:LegadoBackstageRequest?
   LegadoBackstage.loader={r in seen=r;let b=c["backstage"] as! [String:String];return LegadoResponse(url:URL(string:b["url"]!)!,status:200,headers:[:],body:Data(b["text"]!.utf8),text:b["text"]!)}
   LegadoHTTP.transport={req in guard let body=c["http"] as? String else{throw LegadoError.rule("webView chapter must not use plain HTTP")};return(Data(body.utf8),HTTPURLResponse(url:req.url!,statusCode:200,httpVersion:nil,headerFields:nil)!)}
   let media=try LegadoMedia.resolve(s,chapter:.init(title:"ep",url:c["chapterURL"] as! String))
   XCTAssertEqual(media.url.absoluteString,e["mediaURL"] as? String,name)
   if e["backstageCalled"] != nil {XCTAssertNil(seen,name);continue}
   let r=try XCTUnwrap(seen,name)
   XCTAssertEqual(r.url.absoluteString,e["pageURL"] as? String,name);XCTAssertEqual(r.javaScript,e["js"] as? String,name);XCTAssertEqual(r.sourceRegex,e["sourceRegex"] as? String,name)
   XCTAssertEqual(r.delayMs,(e["delayMs"] as! NSNumber).intValue,name);XCTAssertEqual(r.timeout,60)
   XCTAssertEqual(r.headers.keys.contains{$0.lowercased()=="cookie"},e["pageCookie"] as! Bool,name)
   XCTAssertEqual(media.headers.keys.contains{$0.lowercased()=="cookie"},e["mediaCookie"] as! Bool,name)
   XCTAssertEqual(media.headers.first{$0.key.lowercased()=="referer"}?.value,e["mediaReferer"] as? String,name)
  }
 }
 func testWebViewSafetyAndResultSemantics()throws{
  NetGuard.resolverOverride={_ in ["8.8.8.8"]}
  defer{LegadoBackstage.loader=nil}
  for v:Any? in [nil,NSNull(),"",false,"false"]{XCTAssertFalse(LegadoBackstage.useWebView(v))}
  for v:Any in [true,"true","“true”","1"]{XCTAssertTrue(LegadoBackstage.useWebView(v))}
  XCTAssertNil(LegadoBackstage.evalResult(NSNull()));XCTAssertNil(LegadoBackstage.evalResult(""));XCTAssertEqual(LegadoBackstage.evalResult("x"),"x")
  XCTAssertEqual(LegadoBackstage.firstSniffed(#".*\.mp3.*"#,#"["https://a.example/page.js","https://cdn.example.net/x.mp3"]"#),"https://cdn.example.net/x.mp3")
  XCTAssertFalse(LegadoBackstage.matches("([","x"))
  let s=try LegadoSourceStore.parse(Data(#"{"bookSourceUrl":"https://page.example.org","bookSourceType":4,"searchUrl":"/s","ruleContent":{"content":"<js>result</js>"}}"#.utf8)).first!
  LegadoBackstage.loader={_ in XCTFail("private page must be rejected before WebView");throw LegadoError.rule("x")}
  XCTAssertThrowsError(try LegadoMedia.resolve(s,chapter:.init(title:"x",url:#"http://127.0.0.1/p,{"webView":true}"#)))
  LegadoBackstage.loader={r in LegadoResponse(url:r.url,status:200,headers:[:],body:Data(),text:"http://10.0.0.2/x.mp4")}
  XCTAssertThrowsError(try LegadoMedia.resolve(s,chapter:.init(title:"x",url:#"https://page.example.org/p,{"webView":true}"#)))
 }
 func testRealWKWebViewTimeoutCancelPrivateAndResult()throws{
  guard ProcessInfo.processInfo.environment["IDJ_REAL_WEBVIEW"]=="1" else{throw XCTSkip("public network opt-in")}
  let page=URL(string:"https://images.nasa.gov/")!
  func load(_ r:LegadoBackstageRequest,token:LegadoCancelToken?=nil)->Result<LegadoResponse,Error>{
   var out:Result<LegadoResponse,Error>!;let done=expectation(description:"load")
   let t=Thread{out=Result{try (token ?? LegadoCancelToken()).bind{try LegadoBackstage.load(r)}};done.fulfill()};t.start()
   wait(for:[done],timeout:r.timeout+20);return out
  }
  let start=Date()
  if case .failure(let e)=load(.init(url:page,headers:[:],javaScript:"null",sourceRegex:nil,delayMs:0,timeout:4)){XCTAssertTrue("\(e)".contains("超时"),"\(e)")}else{XCTFail("must time out")}
  XCTAssertLessThan(Date().timeIntervalSince(start),15)
  let token=LegadoCancelToken();DispatchQueue.global().asyncAfter(deadline:.now()+1.5){token.cancel()}
  if case .failure(let e)=load(.init(url:page,headers:[:],javaScript:"null",sourceRegex:nil,delayMs:0,timeout:60),token:token){XCTAssertTrue(e is CancellationError,"\(e)")}else{XCTFail("must cancel")}
  if case .success=load(.init(url:URL(string:"http://127.0.0.1/")!,headers:[:],javaScript:"document.title",sourceRegex:nil,delayMs:0,timeout:6)){XCTFail("private page must not load")}
  if case .success(let r)=load(.init(url:page,headers:[:],javaScript:"document.title",sourceRegex:nil,delayMs:0,timeout:30)){XCTAssertFalse(r.text.isEmpty)}else{XCTFail("real page title")}
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
