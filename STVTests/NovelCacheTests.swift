import XCTest
@testable import STV

@MainActor final class NovelCacheTests: XCTestCase {
    func testDownloadAndResumeReadsCachedChaptersWithoutNetwork() async throws {
        let store = NovelStore.shared
        let source = LegadoSource(bookSourceUrl: "https://download.test/" + UUID().uuidString, bookSourceName: "下载测试", searchUrl: "/s", ruleContent: ["content": "id.content@text"])
        LegadoSourceStore.shared.add([source])
        let item = LegadoSearchItem(name: "下载验收", author: "", kind: "", intro: "", cover: "", latest: "", bookURL: source.id + "/book", sourceURL: source.id, sourceName: source.bookSourceName)
        let toc = (0..<3).map { LegadoChapter(title: "第\($0 + 1)章", url: source.id + "/\($0)") }
        let book = store.addWeb(item, info: nil, toc: toc)
        defer { LegadoHTTP.transport = nil; store.remove(book); LegadoSourceStore.shared.remove([source.id]) }
        LegadoHTTP.transport = { request in
            (Data("<div id='content'>离线测试正文</div>".utf8), HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!)
        }
        let loader = NovelWebLoader.shared
        loader.download(book, from: 0, count: nil)
        for _ in 0..<100 where loader.downloadStates[book.id]?.active == true { try await Task.sleep(for: .milliseconds(50)) }
        XCTAssertFalse(loader.downloadStates[book.id]?.active ?? true)
        XCTAssertEqual(store.cachedCount(book), 3)
        LegadoHTTP.transport = { _ in throw URLError(.notConnectedToInternet) }
        try await loader.load(book, 1)
        XCTAssertTrue(store.chapterText(book, 1).contains("离线测试正文"))
        loader.download(book, from: 0, count: nil)
        for _ in 0..<100 where loader.downloadStates[book.id]?.active == true { try await Task.sleep(for: .milliseconds(50)) }
        XCTAssertEqual(loader.downloadStates[book.id]?.failures, 0)
        XCTAssertEqual(loader.downloadStates[book.id]?.completed, 3)
    }

    func testDiskCacheSurvivesTOCReorderAndKeepsSourcesSeparate() throws {
        let store = NovelStore.shared
        let item = LegadoSearchItem(name: "缓存测试", author: "作者", kind: "", intro: "", cover: "", latest: "", bookURL: "https://test.invalid/" + UUID().uuidString, sourceURL: "https://source.invalid", sourceName: "test")
        let toc = [LegadoChapter(title: "第一章", url: "https://test.invalid/one"), LegadoChapter(title: "第二章", url: "https://test.invalid/two")]
        let book = store.addWeb(item, info: nil, toc: toc)
        defer { store.remove(book) }
        try store.cache(book, 1, "第二章\n离线正文")
        XCTAssertEqual(store.chapterText(book, 1), "第二章\n离线正文")
        XCTAssertEqual(store.cachedCount(book), 1)
        store.updateToc(book, Array(toc.reversed()))
        let updated = try XCTUnwrap(store.book(book.id))
        XCTAssertEqual(store.chapterText(updated, 0), "第二章\n离线正文")
        XCTAssertFalse(store.isCached(updated, 1))
        var other = item; other.sourceURL = "https://another.invalid"
        let alternate = store.addWeb(other, info: nil, toc: toc)
        defer { store.remove(alternate) }
        XCTAssertFalse(store.isCached(alternate, 1))
        store.record(book, chapter: 1, offset: 40)
        store.transferProgress(from: book, to: alternate)
        XCTAssertEqual(store.progress[alternate.id]?.chapter, 1)
        XCTAssertEqual(store.progress[alternate.id]?.offset, 0)
        XCTAssertThrowsError(try store.cache(book, 0, ""))
    }
}
