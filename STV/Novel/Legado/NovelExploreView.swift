import SwiftUI

struct NovelExploreView: View {
    var onRead: (NovelBook) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var sourceID = ""
    @State private var categories: [LegadoClient.ExploreCategory] = []
    @State private var categoryID = ""
    @State private var items: [LegadoSearchItem] = []
    @State private var page = 1
    @State private var hasMore = true
    @State private var busy = false
    @State private var error: String?
    @State private var managing = false
    private var sources: [LegadoSource] {
        LegadoSourceStore.shared.sources.filter { $0.isOn && ($0.bookSourceType ?? 0) == 0 && !($0.exploreUrl ?? "").isEmpty }
    }

    var body: some View {
        NavigationStack {
            List {
                if sources.isEmpty {
                    ContentUnavailableView("还没有发现书源", systemImage: "books.vertical", description: Text("导入带 exploreUrl / ruleExplore 的阅读书源后，可浏览它提供的推荐、分类和排行榜。"))
                    Button("管理书源") { managing = true }
                } else {
                    Picker("书源", selection: $sourceID) {
                        ForEach(sources) { Text($0.bookSourceName).tag($0.id) }
                    }
                    if !categories.isEmpty {
                        Picker("推荐 / 榜单", selection: $categoryID) {
                            ForEach(categories) { Text($0.title).tag($0.id) }
                        }
                    }
                    ForEach(items) { item in
                        NavigationLink {
                            NovelWebDetailView(items: [item], onRead: onRead)
                        } label: {
                            VStack(alignment: .leading, spacing: 5) {
                                Text(item.name).font(.headline)
                                Text([item.author, item.latest].filter { !$0.isEmpty }.joined(separator: " · "))
                                    .font(.caption).foregroundStyle(.secondary).lineLimit(2)
                            }
                        }
                    }
                    if let error { Text(error).foregroundStyle(.secondary) }
                    if busy { ProgressView() }
                    else if hasMore && !categoryID.isEmpty {
                        Button(error == nil ? "加载更多" : "重试") { Task { await load() } }
                    } else if items.isEmpty && !categoryID.isEmpty { Text("该栏目暂无书籍") }
                }
            }
            .navigationTitle("发现好书")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
            .sheet(isPresented: $managing) { NovelSourcesView() }
            .task(id: sources.map(\.id)) { if !sources.contains(where: { $0.id == sourceID }) { sourceID = sources.first?.id ?? "" } }
            .task(id: sourceID) {
                categories = []; categoryID = ""; items = []; error = nil
                guard let source = sources.first(where: { $0.id == sourceID }) else { return }
                do {
                    let result = try await LegadoClient.run { try LegadoClient.exploreCategories(source) }
                    guard !Task.isCancelled, sourceID == source.id else { return }
                    categories = result; categoryID = result.first?.id ?? ""
                    if result.isEmpty { error = "这个书源没有可用的发现栏目" }
                } catch { if !Task.isCancelled { self.error = error.localizedDescription } }
            }
            .task(id: sourceID + "|" + categoryID) { items = []; page = 1; hasMore = true; busy = false; await load() }
        }
    }

    private func load() async {
        guard !busy, hasMore, let source = sources.first(where: { $0.id == sourceID }),
              let category = categories.first(where: { $0.id == categoryID }) else { return }
        let requestedPage = page
        busy = true; error = nil
        do {
            let result = try await LegadoClient.run { try LegadoClient.explore(source, category: category, page: requestedPage) }
            guard !Task.isCancelled, sourceID == source.id, categoryID == category.id else { return }
            var seen = Set(items.map(\.id))
            let fresh = result.filter { seen.insert($0.id).inserted }
            items += fresh; hasMore = !fresh.isEmpty; page += 1
        } catch { if !Task.isCancelled, sourceID == source.id, categoryID == category.id { self.error = error.localizedDescription } }
        if sourceID == source.id, categoryID == category.id { busy = false }
    }
}
