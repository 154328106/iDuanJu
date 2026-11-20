import XCTest
@testable import STV

/// Native M3U import must accept, name and reject exactly like scripts/source-compat/m3u_to_idj.py (shared cases).
final class M3UImportTests: XCTestCase {
    private func cases() throws -> [String: Any] {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        return try JSONSerialization.jsonObject(with: Data(contentsOf: root.appendingPathComponent("shared/legado-port/m3u-cases.json"))) as! [String: Any]
    }

    func testChannelsDomainsAndMainJSMatchTheReviewedScript() throws {
        let accept = try cases()["accept"] as! [[String: Any]]
        XCTAssertGreaterThanOrEqual(accept.count, 4)
        for c in accept {
            let id = c["id"] as! String
            let channels = try M3UImport.parse(c["m3u"] as! String, base: c["base"] as? String)
            let expected = c["channels"] as! [[String: Any]]
            XCTAssertEqual(channels.count, expected.count, id)
            for (ch, e) in zip(channels, expected) {
                XCTAssertEqual(ch.title, e["title"] as? String, id)
                XCTAssertEqual(ch.group, e["group"] as? String, id)
                XCTAssertEqual(ch.cover, e["cover"] as? String, id)
                XCTAssertEqual(ch.id, e["id"] as? String, id)
                XCTAssertEqual(ch.url, e["url"] as? String, id)
                let headers = e["headers"] as! [String: String]
                XCTAssertEqual(Dictionary(uniqueKeysWithValues: ch.headers.map { ($0.name, $0.value) }), headers, id)
            }
            let data = try M3UImport.bundle(channels, name: c["name"] as! String, id: id)
            let bundle = try JSONSerialization.jsonObject(with: data) as! [String: Any]
            let manifest = bundle["manifest"] as! [String: Any]
            XCTAssertEqual(((manifest["integrity"] as! [String: Any])["files"] as! [String: String])["main.js"], c["integrity"] as? String, id)
            XCTAssertEqual((manifest["permissions"] as! [String: [String]])["http"], c["domains"] as? [String], id)
            XCTAssertEqual((manifest["permissions"] as! [String: [String]])["media"], c["domains"] as? [String], id)
            XCTAssertNoThrow(try PluginInstaller.prepare(bundle: data, origin: "test"), id)
        }
    }

    func testRejectsExactlyWhatTheReviewedScriptRejects() throws {
        let reject = try cases()["reject"] as! [[String: Any]]
        XCTAssertGreaterThanOrEqual(reject.count, 30)
        for c in reject {
            let text = c["m3u"] as! String
            do {
                let channels = try M3UImport.parse(text)
                if let id = c["id"] as? String { _ = try M3UImport.bundle(channels, name: "X", id: id) }
                XCTFail("\(c["code"] as! String) accepted: \(text)")
            } catch let e as M3UImport.Rejected {
                XCTAssertEqual(e.code, c["code"] as? String, text)
            }
        }
    }

    func testImportIDsAndNamesAreStable() {
        XCTAssertEqual(M3UImport.importID("https://a.org/x.m3u"), M3UImport.importID("https://a.org/x.m3u"))
        XCTAssertNotNil(M3UImport.importID("x").range(of: "^com\\.idj\\.m3u\\.[0-9a-f]{12}$", options: .regularExpression))
        XCTAssertEqual(M3UImport.name(fromURL: "https://a.org/lists/%E5%85%AC%E5%BC%80%20%E5%8A%A8%E7%94%BB.m3u?x=1"), "公开 动画")
        XCTAssertNil(M3UImport.name(fromURL: "https://a.org/"))
        XCTAssertTrue(M3UImport.isChannelList("\u{FEFF}#EXTM3U\n"))
        XCTAssertFalse(M3UImport.isChannelList("{\"manifest\":{}}"))
    }
}
