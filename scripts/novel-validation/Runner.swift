import Foundation

@main struct NovelValidationRunner {
    static func main() throws {
        let args = CommandLine.arguments
        let sources = try JSONDecoder().decode([LegadoSource].self, from: Data(contentsOf: URL(fileURLWithPath: args[1])))
        let source = sources[Int(args[2])!]
        var report: [String: Any] = ["name": source.bookSourceName, "host": URL(string: source.bookSourceUrl)?.host ?? "", "index": Int(args[2])!]
        func stage(_ name: String, _ work: () throws -> Int) {
            do { report[name] = ["status": "ok", "count": try work()] }
            catch { report[name] = ["status": "failed", "error": String(error.localizedDescription.prefix(350))] }
        }
        var categories: [LegadoClient.ExploreCategory] = []
        stage("categories") { categories = try LegadoClient.exploreCategories(source); return categories.count }
        if args.count > 3 && args[3] == "live" {
            var hits: [LegadoSearchItem] = []
            if let category = categories.first(where: { $0.action == nil && !$0.url.isEmpty }) {
                stage("explore") { hits = try LegadoClient.explore(source, category: category); guard !hits.isEmpty else { throw LegadoError.empty("empty book list") }; return hits.count }
            }
            var searchHits: [LegadoSearchItem] = []
            stage("search") { searchHits = try LegadoClient.search(source, key: "西游记"); return searchHits.count }
            if let hit = searchHits.first ?? hits.first {
                var info: LegadoBookInfo?
                var toc: [LegadoChapter] = []
                stage("detail") { info = try LegadoClient.info(source, bookURL: hit.bookURL); return info!.name.count }
                stage("toc") { toc = try LegadoClient.toc(source, tocURL: info?.tocURL ?? hit.bookURL, book: ["name": hit.name, "bookUrl": hit.bookURL]); guard !toc.isEmpty else { throw LegadoError.empty("empty chapter list") }; return toc.count }
                if let first = toc.first {
                    stage("content") { try LegadoClient.content(source, chapter: first, nextChapterURL: toc.count > 1 ? toc[1].url : nil, book: ["name": hit.name, "bookUrl": hit.bookURL]).count }
                }
            }
        }
        let data = try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys])
        print(String(decoding: data, as: UTF8.self))
    }
}
