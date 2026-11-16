import XCTest
@testable import STV

final class NovelSourceBundleTests: XCTestCase {
    private func sources() throws -> [LegadoSource] {
        guard let path = ProcessInfo.processInfo.environment["STV_NOVEL_COLLECTION"] else { throw XCTSkip("STV_NOVEL_COLLECTION points at user-supplied 1335.json") }
        return try LegadoSourceStore.parse(Data(contentsOf: URL(fileURLWithPath: path)))
    }

    func testBundleHasUsableTextSourcesAndRankGroups() throws {
        let all = try sources()
        XCTAssertGreaterThanOrEqual(all.count, 150)
        let text = all.filter { ($0.bookSourceType ?? 0) == 0 }
        XCTAssertGreaterThanOrEqual(text.count, 140)
        let cats = try LegadoClient.exploreCategories(try XCTUnwrap(text.first(where: { $0.bookSourceName == "猫扑中文" })))
        XCTAssertTrue(cats.contains { $0.title == "排行榜" && !$0.url.isEmpty })
        XCTAssertTrue(cats.contains { $0.group == "推荐" && $0.title == "玄幻奇幻" })
        XCTAssertFalse(cats.contains { $0.url.isEmpty && $0.action == nil })
    }

    func testExploreNeverFallsBackToDetailPage() throws {
        var source = LegadoSource(bookSourceUrl: "https://book.test", bookSourceName: "test", searchUrl: "/search")
        source.exploreUrl = "排行榜::data:text/html,<html><body>无列表</body></html>"
        source.ruleExplore = ["bookList": "class.none", "name": "tag.a@text", "bookUrl": "tag.a@href"]
        let cats = try LegadoClient.exploreCategories(source)
        XCTAssertTrue(try LegadoClient.explore(source, category: cats[0]).isEmpty)
    }
}
