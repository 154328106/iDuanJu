import XCTest
@testable import STV

@MainActor final class NovelCacheTests: XCTestCase {
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
