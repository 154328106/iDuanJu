import XCTest
final class LegadoPortUITests:XCTestCase {
 var app:XCUIApplication!
 func el(_ name:String)->XCUIElement{app.descendants(matching:.any)[name].firstMatch}
 func shot(_ name:String){if let p=ProcessInfo.processInfo.environment["STV_SHOT_DIR"]{try? app.screenshot().pngRepresentation.write(to:URL(fileURLWithPath:p).appendingPathComponent(name+".png"))}}
 func flow(_ folder:String,media:Bool,publicSource:Bool=false,sourceFile:URL?=nil,searchQuery:String?=nil,expectedTitle:String?=nil)throws {
  continueAfterFailure=false
  let evidenceName=sourceFile == nil ? folder : (media ? "personal-yck-audio" : "personal-yck-rss")
  let root=URL(fileURLWithPath:#filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("shared/legado-port/"+folder)
  app=XCUIApplication();app.launchEnvironment=["STV_HOME_TAB":"novel","STV_NOVEL_PAGE":"import","STV_LEGADO_FIXTURE":root.path,"STV_LEGADO_FIXTURE_IMPORT":"0","STV_LEGADO_IMPORT":root.appendingPathComponent("empty-sources.json").path]
  if publicSource{app.launchEnvironment.removeValue(forKey:"STV_LEGADO_FIXTURE")}
  app.launch()
  let field=el("import.url");XCTAssertTrue(field.waitForExistence(timeout:15));field.tap()
  let bytes=try Data(contentsOf:sourceFile ?? root.appendingPathComponent("sources.json"));let raw=String(decoding:try JSONSerialization.data(withJSONObject:JSONSerialization.jsonObject(with:bytes)),as:UTF8.self)
  field.typeText(raw);field.typeText("\n")
  XCTAssertTrue(app.descendants(matching:.any).matching(NSPredicate(format:"label CONTAINS %@","更新")).firstMatch.waitForExistence(timeout:20));shot("port-ios-"+evidenceName+"-import")
  el("import.close").tap();app.terminate()
  app.launchEnvironment.removeValue(forKey:"STV_LEGADO_IMPORT");app.launchEnvironment["STV_NOVEL_PAGE"]="rank";app.launch()
  XCTAssertTrue(el("novel.rank.source").waitForExistence(timeout:20))
  if let query=searchQuery{el("novel.rank.search").tap();let input=el("search.field");XCTAssertTrue(input.waitForExistence(timeout:15));input.tap();input.typeText(query+"\n")}
  let first=el(expectedTitle.map{"search.result."+$0} ?? "novel.rank.book.0");XCTAssertTrue(first.waitForExistence(timeout:30));shot("port-ios-"+evidenceName+"-catalog");first.tap()
  let read=el("detail.read");XCTAssertTrue(read.waitForExistence(timeout:20));let ready=expectation(for:NSPredicate(format:"enabled == true"),evaluatedWith:read);wait(for:[ready],timeout:30)
  shot("port-ios-"+evidenceName+"-detail");read.tap()
  if media{
   let status=el("novel.media.status");XCTAssertTrue(status.waitForExistence(timeout:30))
   let advancing=expectation(for:NSPredicate {object,_ in
    guard let e=object as? XCUIElement else{return false}
    let value=e.label.components(separatedBy:" ").compactMap(Double.init).first ?? 0
    return value >= (sourceFile != nil ? 2.0 : 0.2)
   },evaluatedWith:status);wait(for:[advancing],timeout:60);XCTAssertTrue(el("novel.media.player").exists)
   if let expectedTitle{XCTAssertTrue(app.navigationBars[expectedTitle].waitForExistence(timeout:10))}
  }else{
   let body=el("novel.page.body");XCTAssertTrue(body.waitForExistence(timeout:30))
   let ready=expectation(for:NSPredicate{object,_ in
    guard let e=object as? XCUIElement else{return false};let text=(e.value as? String) ?? e.label
    return text.count>50 && !text.contains("正在加载") && !text.contains("加载失败")
   },evaluatedWith:body);wait(for:[ready],timeout:60)
  }
  shot("port-ios-"+evidenceName+"-consumed");app.terminate()
 }
 func testRSSImportAndNativeReader()throws{try flow("rss-ui",media:false)}
 func testAudioImportAndNativePlayback()throws{try flow("audio-ui",media:true)}
 func testVideoImportAndNativePlayback()throws{try flow("video-ui",media:true)}
 func testNasaActualPublicImportAndNativeReader()throws{try flow("nasa-public",media:false,publicSource:true)}
 func testPersonalYckActualPublicImportAndNativeReader()throws{
  guard let path=ProcessInfo.processInfo.environment["IDJ_PERSONAL_RSS_JSON"]else{throw XCTSkip("personal source not distributed")}
  try flow("nasa-public",media:false,publicSource:true,sourceFile:URL(fileURLWithPath:path))
 }
 func testStandalonePublicAudioConfigNativePlayback()throws{try flow("audio-static-public",media:true,publicSource:true)}
 func testStandalonePublicVideoConfigNativePlayback()throws{try flow("video-static-public",media:true,publicSource:true)}

 func testPersonalYckFreeAudioSearchDetailTocPlayback()throws{
  guard let p=ProcessInfo.processInfo.environment["IDJ_PERSONAL_AUDIO_JSON"]else{throw XCTSkip("private definition opt-in")}
  try flow("nasa-public",media:true,publicSource:true,sourceFile:URL(fileURLWithPath:p),searchQuery:"论语",expectedTitle:"《论语》朗读与讲解")
 }
 func testNasaRealAnimationLibraryNativePlayback()throws{try flow("nasa-animation-public",media:true,publicSource:true)}
}
