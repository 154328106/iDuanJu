import SwiftUI

/// Source-defined sections, sidebar categories, and ranked cover rows.
struct NovelExploreView: View {
    var onRead: (NovelBook) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var sourceID = ""
    @State private var categories: [LegadoClient.ExploreCategory] = []
    @State private var group = ""
    @State private var categoryID = ""
    @State private var items: [LegadoSearchItem] = []
    @State private var page = 1
    @State private var hasMore = true
    @State private var busy = false
    @State private var error: String?
    @State private var managing = false
    @State private var choosingSource = false
    @State private var generation = UUID()
    private let accent = Color(red: 0.85, green: 0.20, blue: 0.25)
    private var sources: [LegadoSource] { LegadoSourceStore.shared.sources.filter { $0.isOn && ($0.bookSourceType ?? 0) == 0 && !($0.exploreUrl ?? "").isEmpty } }
    private var groups: [String] { categories.reduce(into: [String]()) { if !$0.contains($1.group) { $0.append($1.group) } } }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if sources.isEmpty {
                    ContentUnavailableView("先添加书源", systemImage: "books.vertical", description: Text("发现页显示书源实际提供的分类与榜单。"))
                    Button("导入 / 管理书源") { managing = true }.padding()
                } else {
                    sourceBar
                    if groups.count > 1 { groupBar }
                    Divider()
                    HStack(spacing: 0) { categoryRail; results.frame(maxWidth: .infinity, maxHeight: .infinity) }
                }
            }
            .background(Color(.systemBackground))
            .navigationTitle("小说榜单").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button { dismiss() } label: { Image(systemName: "chevron.left") }.accessibilityLabel("返回") }
                ToolbarItem(placement: .topBarTrailing) { Button("书源") { managing = true } }
            }
            .sheet(isPresented: $managing) { NovelSourcesView() }
            .sheet(isPresented: $choosingSource) { sourceSheet }
            .task(id: sources.map(\.id)) { if !sources.contains(where: { $0.id == sourceID }) { sourceID = sources.first?.id ?? "" } }
            .task(id: sourceID) { await refreshCategories() }
            .task(id: categoryID) { await resetAndLoad() }
            .tint(accent)
        }
    }
    private var sourceBar: some View {
        HStack {
            Button { choosingSource = true } label: {
                HStack(spacing: 6) {
                    Text(sources.first(where: { $0.id == sourceID })?.bookSourceName ?? "选择书源").lineLimit(1)
                    Image(systemName: "chevron.down").font(.caption.weight(.semibold))
                }.font(.subheadline.weight(.semibold))
            }.buttonStyle(.plain)
            Spacer()
            Text("实时书源榜单").font(.caption).foregroundStyle(.secondary)
        }.padding(.horizontal, 18).frame(height: 44)
    }
    private var groupBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 24) {
                ForEach(groups, id: \.self) { title in
                    Button { group = title; categoryID = categories.first { $0.group == title && !$0.url.isEmpty }?.id ?? "" } label: {
                        VStack(spacing: 8) {
                            Text(title).font(.system(size: 17, weight: group == title ? .bold : .regular)).foregroundStyle(group == title ? Color.primary : Color.secondary)
                            Capsule().fill(group == title ? accent : .clear).frame(height: 3)
                        }
                    }.buttonStyle(.plain)
                }
            }.padding(.horizontal, 18).padding(.top, 8)
        }
    }
    private var categoryRail: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(categories.filter { $0.group == group }) { category in
                    Button { select(category) } label: {
                        Text(category.title).font(.system(size: 14, weight: category.id == categoryID ? .semibold : .regular))
                            .foregroundStyle(category.id == categoryID ? accent : Color.primary)
                            .multilineTextAlignment(.center).frame(maxWidth: .infinity, minHeight: 52).padding(.vertical, 3)
                            .background(category.id == categoryID ? Color(.systemBackground) : .clear)
                            .overlay(alignment: .leading) { if category.id == categoryID { Rectangle().fill(accent).frame(width: 3, height: 22) } }
                    }.buttonStyle(.plain)
                }
            }
        }.frame(width: 92).background(Color(.secondarySystemBackground))
    }
    private var results: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                    NavigationLink { NovelWebDetailView(items: [item], onRead: onRead) } label: { bookRow(item, rank: index + 1) }.buttonStyle(.plain)
                    Divider().padding(.leading, 74)
                }
                if busy { ProgressView("加载中").frame(maxWidth: .infinity).padding(26) }
                else if let error {
                    VStack(alignment: .leading, spacing: 10) {
                        Label("这个栏目暂时不可用", systemImage: "wifi.exclamationmark").font(.subheadline.weight(.semibold))
                        Text(error).font(.caption).foregroundStyle(.secondary).textSelection(.enabled)
                        Button("重试") { Task { await load(generation) } }
                        Button("换个书源") { choosingSource = true }
                    }.padding(18)
                } else if items.isEmpty {
                    Text(categoryID.isEmpty ? "选择左侧栏目，或切换其他书源" : "书源没有返回书籍，请尝试其他栏目").font(.subheadline).foregroundStyle(.secondary).padding(24)
                } else if hasMore {
                    Button("加载更多") { Task { await load(generation) } }.frame(maxWidth: .infinity).padding(20)
                } else { Text("已显示全部").font(.caption).foregroundStyle(.secondary).frame(maxWidth: .infinity).padding(20) }
            }.padding(.horizontal, 14)
        }.id(categoryID)
    }
    private func bookRow(_ item: LegadoSearchItem, rank: Int) -> some View {
        HStack(alignment: .top, spacing: 12) {
            WebCover(url: item.cover.isEmpty ? nil : item.cover, title: item.name).frame(width: 58, height: 80)
            VStack(alignment: .leading, spacing: 7) {
                Text("\(rank). \(item.name)").font(.system(size: 17, weight: .semibold)).foregroundStyle(.primary).lineLimit(2)
                Text([item.author, item.kind].filter { !$0.isEmpty }.joined(separator: " · ")).font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(2)
                if !item.latest.isEmpty { Text(item.latest).font(.system(size: 11)).foregroundStyle(.tertiary).lineLimit(1) }
            }.frame(maxWidth: .infinity, alignment: .leading)
        }.padding(.vertical, 16)
    }
    private var sourceSheet: some View {
        NavigationStack {
            List(sources) { source in
                Button { sourceID = source.id; choosingSource = false } label: {
                    HStack { Text(source.bookSourceName); Spacer(); if source.id == sourceID { Image(systemName: "checkmark") } }
                }
            }.navigationTitle("切换榜单来源").toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { choosingSource = false } } }
        }
    }
    private func select(_ category: LegadoClient.ExploreCategory) {
        if category.action != nil {
            Task {
                guard let source = sources.first(where: { $0.id == sourceID }) else { return }
                do { try await LegadoClient.run { try LegadoClient.exploreAction(source, category: category) }; await refreshCategories() }
                catch { self.error = error.localizedDescription }
            }
        } else { categoryID = category.id }
    }
    private func refreshCategories() async {
        generation = UUID(); categories = []; categoryID = ""; items = []; error = nil
        guard let source = sources.first(where: { $0.id == sourceID }) else { return }
        let token = generation; busy = true
        do {
            let result = try await LegadoClient.run { try LegadoClient.exploreCategories(source) }
            guard token == generation, !Task.isCancelled else { return }
            categories = result
            if !result.contains(where: { $0.group == group }) { group = result.first?.group ?? "" }
            busy = false; categoryID = result.first { $0.group == group && !$0.url.isEmpty }?.id ?? ""
            if result.isEmpty { error = "书源没有可用的发现配置" }
        } catch { if token == generation { self.error = error.localizedDescription; busy = false } }
    }
    private func resetAndLoad() async {
        guard !categoryID.isEmpty else { return }
        generation = UUID(); items = []; page = 1; hasMore = true; busy = false; error = nil
        await load(generation)
    }
    private func load(_ token: UUID) async {
        guard token == generation, !busy, hasMore, let source = sources.first(where: { $0.id == sourceID }), let category = categories.first(where: { $0.id == categoryID }), !category.url.isEmpty else { return }
        let requestedPage = page; busy = true; error = nil
        do {
            let result = try await LegadoClient.run { try LegadoClient.explore(source, category: category, page: requestedPage) }
            guard token == generation, !Task.isCancelled else { return }
            var seen = Set(items.map(\.id)); let fresh = result.filter { seen.insert($0.id).inserted }
            items += fresh; hasMore = !fresh.isEmpty; page += 1
        } catch { if token == generation { self.error = error.localizedDescription } }
        if token == generation { busy = false }
    }
}
