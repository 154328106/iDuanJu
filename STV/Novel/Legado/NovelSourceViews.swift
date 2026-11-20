import SwiftUI
import UniformTypeIdentifiers

/// 书源管理: import 「阅读」(Legado) JSON sources from a URL, the clipboard or a file; toggle / delete.
struct NovelSourcesView: View {
    @Environment(\.dismiss) private var dismiss
    private let store = LegadoSourceStore.shared
    @State private var importing = false
    @State private var login: LegadoSource?
    @State private var filter: Filter = .all

    enum Filter: String, CaseIterable { case all = "全部", on = "已启用", login = "需登录", unsupported = "不支持" }

    private func matches(_ s: LegadoSource, _ f: Filter) -> Bool {
        switch f {
        case .all: true
        case .on: s.isOn && s.supported
        case .login: s.hasLogin && !LegadoSourceState.isLoggedIn(s.bookSourceUrl)
        case .unsupported: !s.supported
        }
    }
    private var visible: [LegadoSource] { store.sources.filter { matches($0, filter) } }

    /// ui-redesign 08: count line, status chips with counts, one card of rows with status labels, 登录 and a switch.
    var body: some View {
        VStack(spacing: 0) {
            DSNavBar(title: "书源") {
                DSBackButton { dismiss() }
            } trailing: {
                RoundIconButton(systemName: "plus", tone: .accent) { importing = true }.accessibilityIdentifier("sources.add")
            }
            List {
                Group {
                    Text("共 \(store.sources.count) 个书源 · 已启用 \(store.enabled.count) 个")
                        .font(.system(size: 13)).foregroundStyle(STVTheme.secondary)
                        .padding(.horizontal, 20).padding(.top, 12)
                    filterChips.padding(.top, 12).padding(.bottom, 16)
                }
                .listRowInsets(EdgeInsets()).listRowBackground(Color.clear).listRowSeparator(.hidden)
                if store.sources.isEmpty {
                    VStack(spacing: 8) {
                        Image(systemName: "books.vertical").font(.system(size: 30)).foregroundStyle(STVTheme.tertiary)
                        Text("还没有书源").font(.system(size: 16, weight: .semibold)).foregroundStyle(STVTheme.text)
                        Text("点右上角 ＋ 导入「阅读」(Legado) 的书源 JSON。App 不内置任何书源。")
                            .font(.system(size: 13)).foregroundStyle(STVTheme.secondary).multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity).padding(.vertical, 40).padding(.horizontal, 24)
                    .listRowInsets(EdgeInsets()).listRowBackground(Color.clear).listRowSeparator(.hidden)
                }
                let list = visible
                ForEach(Array(list.enumerated()), id: \.element.id) { i, s in
                    row(s)
                        .overlay(alignment: .top) { if i > 0 { Hairline() } }
                        .padding(.horizontal, 14)
                        .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
                        .listRowSeparator(.hidden)
                        .listRowBackground(CardSlice(first: i == 0, last: i == list.count - 1).padding(.horizontal, 16))
                }
                .onDelete { idx in store.remove(Set(idx.map { list[$0].id })) }
                if !store.sources.isEmpty {
                    Text("左滑可删除书源。").font(.system(size: 12)).foregroundStyle(STVTheme.tertiary)
                        .padding(.horizontal, 30).padding(.top, 14).padding(.bottom, 40)
                        .listRowInsets(EdgeInsets()).listRowBackground(Color.clear).listRowSeparator(.hidden)
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .animation(DS.panel, value: filter)
        }
        .dsAmbient(cool: true)
        .tint(STVTheme.accent)
        .toolbar(.hidden, for: .navigationBar)
        .sheet(item: $login) { s in NovelSourceLoginView(source: s) }
        .sheet(isPresented: $importing) { NovelImportSheet(showBooks: false) }
    }

    private var filterChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(Filter.allCases, id: \.self) { f in
                    Button { withAnimation(DS.select) { filter = f } } label: {
                        CountLabel(title: f.rawValue, count: store.sources.filter { matches($0, f) }.count)
                    }
                    .buttonStyle(ToneChipStyle(on: filter == f, neutral: f == .all))
                    .accessibilityIdentifier("sources.filter.\(f.rawValue)")
                }
            }
            .padding(.horizontal, 16)
        }
    }

    private func row(_ s: LegadoSource) -> some View {
        let loggedIn = LegadoSourceState.isLoggedIn(s.bookSourceUrl)
        let dim = !s.supported
        return HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 7) {
                    Text(s.bookSourceName).font(.system(size: 16, weight: .medium)).foregroundStyle(STVTheme.text).lineLimit(1)
                        .opacity(dim ? 0.5 : 1)
                    if !s.supported { DSStatus(text: "暂不支持", tone: .muted) }
                    else if s.hasLogin { DSStatus(text: loggedIn ? "已登录" : "需登录", tone: loggedIn ? .ok : .warn) }
                }
                Text(subtitle(s)).font(.system(size: 12)).foregroundStyle(STVTheme.secondary).lineLimit(1).opacity(dim ? 0.5 : 1)
            }
            Spacer(minLength: 0)
            if s.supported, s.hasLogin, !loggedIn {
                Button { login = s } label: { MiniButtonLabel(title: "登录") }.buttonStyle(PressScale())
            }
            Toggle("", isOn: Binding(get: { s.isOn && s.supported }, set: { store.setEnabled(s, $0) }))
                .labelsHidden().tint(STVTheme.accent).disabled(!s.supported).opacity(s.supported ? 1 : 0.4)
        }
        .frame(minHeight: 66)
        .contentShape(Rectangle())
        .contextMenu { if s.hasLogin { Button("登录", systemImage: "person.crop.circle") { login = s } } }
        .swipeActions(edge: .leading) { if s.hasLogin { Button("登录") { login = s }.tint(STVTheme.accent) } }
    }

    private func subtitle(_ s: LegadoSource) -> String {
        let group = (s.bookSourceGroup ?? "").isEmpty ? "综合" : s.bookSourceGroup!
        if !s.supported { return group + " · " + ([2,3].contains(s.bookSourceType ?? 0) ? "暂未实现的源类型" : "没有搜索 / 发现地址") }
        return [group, URL(string: s.bookSourceUrl)?.host].compactMap { $0 }.joined(separator: " · ")
    }
}

/// A list-row background that draws one slice of a rounded card (first / middle / last row).
struct CardSlice: View {
    var first: Bool
    var last: Bool
    var radius: CGFloat = 18
    var body: some View {
        UnevenRoundedRectangle(topLeadingRadius: first ? radius : 0, bottomLeadingRadius: last ? radius : 0,
                               bottomTrailingRadius: last ? radius : 0, topTrailingRadius: first ? radius : 0, style: .continuous)
            .fill(STVTheme.card)
    }
}

// MARK: - 搜书

/// Searches every enabled 书源 at once; results are merged by 书名 + 作者 as sources answer.
struct NovelSearchView: View {
    @Environment(\.dismiss) private var dismiss
    private let sources = LegadoSourceStore.shared
    @State private var query = ""
    @State private var groups: [Group] = []
    @State private var done = 0
    @State private var total = 0
    @State private var failures = 0
    @State private var task: Task<Void, Never>?
    @State private var manage = false

    struct Group: Identifiable, Hashable {
        var name: String
        var author: String
        var items: [LegadoSearchItem]
        var id: String { name + "|" + author }
    }

    @State private var scrolled = false
    @State private var submitted = ""
    @FocusState private var focused: Bool

    /// ui-redesign 05: custom nav, 46 pt capsule field, a progress bar instead of the list header, `.srow` results.
    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                DSNavBar(title: "搜书") {
                    DSBackButton { task?.cancel(); dismiss() }
                } trailing: {
                    Button { manage = true } label: { CapsuleLabel(title: "书源") }
                        .buttonStyle(PressScale()).accessibilityIdentifier("search.sources")
                }
                .dsPinned(scrolled)
                field.padding(.top, 10)
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        if sources.enabled.isEmpty {
                            empty("还没有可用的书源", "先在「书源」里导入「阅读」(Legado) 书源", action: ("管理书源", { manage = true }))
                        } else if total > 0 {
                            progress.padding(.horizontal, 20).padding(.top, 14)
                            LazyVStack(spacing: 0) {
                                ForEach(Array(groups.enumerated()), id: \.element.id) { i, g in
                                    NavigationLink(value: g) { resultRow(g) }
                                        .buttonStyle(PressScale(scale: 0.98))
                                        .overlay(alignment: .top) { if i > 0 { Hairline() } }
                                        .accessibilityIdentifier("search.result.\(g.name)")
                                        .transition(.opacity)
                                }
                            }
                            .padding(.horizontal, 16).padding(.top, 6)
                            .animation(DS.panel, value: groups.map(\.id))
                            if done >= total && groups.isEmpty {
                                empty("没有找到书籍", "换个关键词，或在书源配置中启用其他来源。")
                            }
                        } else {
                            empty("搜索你的下一本书", "输入书名或作者，同时搜索已启用的书源。相同书籍会合并显示。")
                        }
                    }
                    .padding(.bottom, 40)
                }
                .onScrollGeometryChange(for: Bool.self) { $0.contentOffset.y + $0.contentInsets.top > 4 } action: { _, value in scrolled = value }
                .scrollDismissesKeyboard(.immediately)
            }
            .dsAmbient(cool: true)
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: Group.self) { g in NovelWebDetailView(items: g.items) }
            .fullScreenCover(isPresented: $manage) { NovelSourcesView() }
            .onAppear {
                #if DEBUG
                if let q = ProcessInfo.processInfo.environment["STV_NOVEL_QUERY"], !q.isEmpty, submitted.isEmpty {
                    query = q; focused = false; run(); return
                }
                #endif
                if query.isEmpty { focused = true }
            }
        }
    }

    private var field: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass").font(.system(size: 17, weight: .medium)).foregroundStyle(STVTheme.secondary)
            TextField("", text: $query, prompt: Text("书名或作者").foregroundStyle(STVTheme.secondary))
                .focused($focused).submitLabel(.search).onSubmit(run)
                .font(.system(size: 16)).foregroundStyle(STVTheme.text).tint(STVTheme.accent)
                .autocorrectionDisabled().textInputAutocapitalization(.never)
                .accessibilityIdentifier("search.field")
            if !query.isEmpty {
                Button { query = ""; focused = true } label: {
                    Image(systemName: "xmark").font(.system(size: 9, weight: .bold)).foregroundStyle(STVTheme.bg)
                        .frame(width: 20, height: 20).background(Circle().fill(STVTheme.tertiary))
                }
                .buttonStyle(PressScale()).accessibilityLabel("清除").transition(.scale.combined(with: .opacity))
                Button("搜索", action: run).font(.subheadline.weight(.semibold)).foregroundStyle(STVTheme.accent)
            }
        }
        .padding(.horizontal, 16).frame(height: 46)
        .background(Capsule().fill(STVTheme.glass))
        .overlay(Capsule().strokeBorder(STVTheme.glassBorder, lineWidth: 1))
        .padding(.horizontal, 16)
        .animation(DS.press, value: query.isEmpty)
    }

    private var progress: some View {
        VStack(spacing: 8) {
            HStack(spacing: 0) {
                if done < total {
                    Text("正在搜索 ") + Text("\(done)").foregroundStyle(STVTheme.text).bold() + Text(" / \(total) 个书源")
                } else {
                    Text("共 \(groups.count) 本 · \(total) 个书源")
                }
                Spacer(minLength: 8)
                if failures > 0 { Text("\(failures) 个失败").foregroundStyle(STVTheme.accentWarm) }
            }
            .font(.system(size: 13)).foregroundStyle(STVTheme.secondary)
            .accessibilityElement(children: .combine).accessibilityIdentifier("search.progress")
            DSProgressBar(value: total > 0 ? Double(done) / Double(total) : 0)
                .opacity(done < total ? 1 : 0).animation(.easeOut(duration: 0.3), value: done < total)
        }
    }

    private func empty(_ title: String, _ text: String, action: (String, () -> Void)? = nil) -> some View {
        VStack(spacing: 10) {
            Image(systemName: "magnifyingglass").font(.system(size: 30)).foregroundStyle(STVTheme.tertiary)
            Text(title).font(.system(size: 16, weight: .semibold)).foregroundStyle(STVTheme.text)
            Text(text).font(.system(size: 13)).foregroundStyle(STVTheme.secondary).multilineTextAlignment(.center)
            if let action {
                Button(action: action.1) { PillButtonLabel(title: action.0, small: true) }.buttonStyle(PressScale()).padding(.top, 6)
            }
        }
        .frame(maxWidth: .infinity).padding(.horizontal, 32).padding(.top, 80)
    }

    private func highlighted(_ name: String) -> AttributedString {
        var a = AttributedString(name)
        let q = submitted
        if !q.isEmpty, let r = a.range(of: q) { a[r].foregroundColor = STVTheme.accent }
        return a
    }

    private func resultRow(_ g: Group) -> some View {
        let first = g.items[0]
        let meta = [g.author.isEmpty ? nil : g.author, first.kind.isEmpty ? nil : first.kind].compactMap { $0 }.joined(separator: " · ")
        return HStack(spacing: 12) {
            WebCover(url: g.items.first { !$0.cover.isEmpty }?.cover, title: g.name, corner: 6)
                .frame(width: 58, height: 80.5)
                .shadow(color: .black.opacity(0.22), radius: 5, y: 4)
            VStack(alignment: .leading, spacing: 3) {
                Text(highlighted(g.name)).font(.system(size: 16, weight: .semibold)).foregroundStyle(STVTheme.text).lineLimit(1)
                if !meta.isEmpty { Text(meta).font(.system(size: 12)).foregroundStyle(STVTheme.secondary).lineLimit(1) }
                if !first.latest.isEmpty { Text("最新：" + first.latest).font(.system(size: 12)).foregroundStyle(STVTheme.tertiary).lineLimit(1) }
                HStack(spacing: 5) {
                    DSStatus(text: "\(g.items.count) 个书源", tone: .accent)
                    DSTag(text: (g.items.count > 1 ? "首选 " : "") + first.sourceName)
                }
                .padding(.top, 3)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 13)
        .contentShape(Rectangle())
    }

    private func run() {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return }
        focused = false
        submitted = q
        task?.cancel()
        groups = []; done = 0; failures = 0
        let list = sources.enabled
        total = list.count
        task = Task {
            await withTaskGroup(of: (Bool, [LegadoSearchItem]).self) { tg in
                var it = list.makeIterator()
                func add() -> Bool {
                    guard let s = it.next() else { return false }
                    tg.addTask {
                        do { return (true, try await LegadoClient.run { try LegadoClient.search(s, key: q) }) } catch { return (false, []) }
                    }
                    return true
                }
                for _ in 0..<8 { if !add() { break } }
                for await (ok, items) in tg {
                    if Task.isCancelled { tg.cancelAll(); return }
                    done += 1
                    if !ok { failures += 1 }
                    merge(items, query: q)
                    _ = add()
                }
            }
        }
    }

    private func merge(_ items: [LegadoSearchItem], query q: String) {
        for it in items {
            let author = it.author.trimmingCharacters(in: .whitespaces)
            if let i = groups.firstIndex(where: { $0.name == it.name && ($0.author == author || $0.author.isEmpty || author.isEmpty) }) {
                if !groups[i].items.contains(where: { $0.sourceURL == it.sourceURL }) { groups[i].items.append(it) }
            } else {
                groups.append(Group(name: it.name, author: author, items: [it]))
            }
        }
        // Exact title first, then titles containing the query, then by number of sources.
        groups.sort { a, b in
            func rank(_ g: Group) -> Int { g.name == q ? 0 : g.name.contains(q) || g.author == q ? 1 : 2 }
            return (rank(a), -a.items.count) < (rank(b), -b.items.count)
        }
    }
}

/// Cover from a URL with a generated fallback.
struct WebCover: View {
    var url: String?
    var title: String
    var corner: CGFloat = 5
    var body: some View {
        AsyncImage(url: url.flatMap(URL.init(string:))) { phase in
            if let img = phase.image { img.resizable().scaledToFill() }
            else { GeneratedBookCover(title: title) }
        }
        .clipShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
    }
}

// MARK: - 详情

struct NovelWebDetailView: View {
    let items: [LegadoSearchItem]
    var onRead: ((NovelBook) -> Void)? = nil
    @State private var reading: NovelBook?
    var resumeFrom: NovelBook? = nil
    @State private var pick = 0
    @State private var info: LegadoBookInfo?
    @State private var toc: [LegadoChapter]?
    @State private var error: String?
    @State private var loading = false
    @State private var alternatives: [LegadoSearchItem] = []
    @State private var findingSources = false
    @State private var downloadBook: NovelBook?
    @State private var showingMedia=false
    @State private var mediaChapter=0
    @State private var sourceTask: Task<Void, Never>?
    @State private var loadGeneration = UUID()
    private var allItems: [LegadoSearchItem] { items + alternatives.filter { candidate in !items.contains { $0.id == candidate.id } } }
    private let store = NovelStore.shared

    private var item: LegadoSearchItem { allItems[min(pick, allItems.count - 1)] }
    private var source: LegadoSource? { LegadoSourceStore.shared.source(item.sourceURL) }
    private var onShelf: NovelBook? { store.webBook(source: item.sourceURL, url: item.bookURL) }

    @Environment(\.dismiss) private var dismiss
    @State private var scrolled = false
    private var title: String { info?.name.novelNonEmpty ?? item.name }
    private var author: String { info?.author.novelNonEmpty ?? item.author }
    private var intro: String { (info?.intro.novelNonEmpty ?? item.intro).trimmingCharacters(in: .whitespacesAndNewlines) }
    private var latest: String { info?.latest.novelNonEmpty ?? item.latest }
    private var chapterCount: Int { toc?.count ?? onShelf?.chapters.count ?? 0 }
    private var canOpen: Bool { chapterCount > 0 }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                HStack(alignment: .center, spacing: 20) {
                    WebCover(url: info?.cover.novelNonEmpty ?? item.cover, title: title, corner: 8)
                        .frame(width: 100, height: 142).shadow(color: .black.opacity(0.18), radius: 8, y: 5)
                    VStack(alignment: .leading, spacing: 8) {
                        Text(title).font(.system(size: 26, weight: .bold, design: .serif)).fixedSize(horizontal: false, vertical: true)
                        if !author.isEmpty { Text(author).font(.subheadline).foregroundStyle(STVTheme.secondary) }
                        let kind = info?.kind.novelNonEmpty ?? item.kind
                        if !kind.isEmpty { Text(kind).font(.caption).foregroundStyle(STVTheme.secondary).lineLimit(2) }
                        if chapterCount > 0 { Text("共 \(chapterCount) 章").font(.caption.weight(.semibold)).foregroundStyle(STVTheme.accent) }
                    }
                }.padding(.vertical, 12)
                VStack(alignment: .leading, spacing: 14) {
                    if !latest.isEmpty { Label("最新：" + latest, systemImage: "clock").font(.subheadline).foregroundStyle(STVTheme.secondary); Hairline() }
                    Menu {
                        ForEach(allItems.indices, id: \.self) { i in
                            Button { pick = i } label: { Label(allItems[i].sourceName, systemImage: i == pick ? "checkmark" : "book") }
                        }
                        Button("搜索其他书源") { sourceTask?.cancel(); sourceTask = Task { await findSources() } }
                    } label: {
                        HStack { Text("书源：" + item.sourceName); Spacer(); Text("换源").foregroundStyle(STVTheme.accent); Image(systemName: "chevron.down").font(.caption) }
                            .font(.subheadline).contentShape(Rectangle())
                    }.accessibilityIdentifier("detail.source")
                }.novelPanel()
                if !intro.isEmpty {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("内容简介").font(.headline)
                        Text(intro).font(.body).lineSpacing(5).foregroundStyle(STVTheme.secondary).textSelection(.enabled)
                    }
                }
                if source?.isMedia != true, chapterCount > 0 {
                    VStack(alignment: .leading, spacing: 0) {
                        Text("试读章节").font(.headline).padding(.bottom, 8)
                        ForEach(0..<min(3, chapterCount), id: \.self) { index in
                            Button { read(open: true, chapter: index) } label: {
                                HStack { Text(toc?[index].title ?? onShelf?.chapters[index].title ?? ""); Spacer(); Image(systemName: "chevron.right").font(.caption).foregroundStyle(STVTheme.secondary) }
                                    .font(.subheadline).frame(minHeight: 44).contentShape(Rectangle())
                            }.buttonStyle(.plain)
                            Hairline()
                        }
                    }
                }
                if findingSources { HStack { ProgressView("正在查找其他书源…"); Button("停止") { sourceTask?.cancel() } } }
                if loading { ProgressView("正在读取目录…") }
                if let error { VStack(alignment: .leading, spacing: 8) { Text(error).font(.callout); Button("重试") { Task { await load() } } } }
            }.frame(maxWidth: .infinity, alignment: .leading).padding(20)
        }
        .onScrollGeometryChange(for: Bool.self) { $0.contentOffset.y + $0.contentInsets.top > 16 } action: { _, value in scrolled = value }
        .safeAreaInset(edge: .top, spacing: 0) {
            DSNavBar(title: scrolled ? title : "书籍详情") { DSBackButton { dismiss() } }.dsPinned(scrolled)
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            HStack(spacing: 14) {
                Button { read(open: false) } label: { VStack(spacing: 3) { Image(systemName: onShelf == nil ? "books.vertical" : "checkmark"); Text(onShelf == nil ? "加入书架" : "已在书架").font(.caption) }.frame(minWidth: 52, minHeight: 48) }
                    .disabled(!canOpen || onShelf != nil || source?.isMedia == true).accessibilityIdentifier("detail.add")
                Button { downloadBook = onShelf ?? store.addWeb(item, info: info, toc: toc ?? []) } label: { VStack(spacing: 3) { Image(systemName: "arrow.down.circle"); Text("下载").font(.caption) }.frame(minWidth: 44, minHeight: 48) }
                    .disabled(!canOpen || source?.isMedia == true).accessibilityIdentifier("detail.download")
                Button { read(open: true) } label: { Text(source?.bookSourceType == 1 ? "开始收听" : source?.bookSourceType == 4 ? "开始观看" : "开始阅读").font(.headline).foregroundStyle(STVTheme.onAccent).frame(maxWidth: .infinity, minHeight: 48).background(STVTheme.accent, in: Capsule()) }
                    .disabled(!canOpen).opacity(canOpen ? 1 : 0.45).accessibilityIdentifier("detail.read")
            }.buttonStyle(.plain).padding(.horizontal, 20).padding(.vertical, 8).background(.regularMaterial)
        }
        .foregroundStyle(STVTheme.text).dsAmbient(cool: true)
        .toolbar(.hidden, for: .navigationBar)
        .task(id: pick) { await load() }
        .onDisappear { sourceTask?.cancel() }
        .fullScreenCover(item: $reading) { NovelReaderView(book: $0) }
        .sheet(item: $downloadBook) { book in NovelDownloadView(book: book) }
        .fullScreenCover(isPresented:$showingMedia){if let source{NovelMediaPlayer(source:source,chapters:toc ?? [],startIndex:mediaChapter,book:["cover":info?.cover.novelNonEmpty ?? item.cover,"name":info?.name.isEmpty == false ? info!.name : item.name,"author":info?.author.isEmpty == false ? info!.author : item.author,"bookUrl":item.bookURL])}}
    }

    private func findSources() async {
        guard !findingSources else { return }
        findingSources = true
        defer { findingSources = false }
        let target = item
        let list = LegadoSourceStore.shared.enabled.filter { $0.id != target.sourceURL && !($0.searchUrl ?? "").isEmpty }
        await withTaskGroup(of: [LegadoSearchItem].self) { group in
            var iterator = list.makeIterator()
            func enqueue() {
                guard let source = iterator.next() else { return }
                group.addTask { (try? await LegadoClient.run { try LegadoClient.search(source, key: target.name) }) ?? [] }
            }
            for _ in 0..<4 { enqueue() }
            for await results in group {
                if Task.isCancelled { group.cancelAll(); return }
                let matched = results.filter { $0.name == target.name && (target.author.isEmpty || $0.author.isEmpty || $0.author == target.author) }
                for match in matched where !allItems.contains(where: { $0.id == match.id }) { alternatives.append(match) }
                enqueue()
            }
        }
        if !Task.isCancelled, alternatives.isEmpty { error = "暂未找到可用的同名书籍来源，可在书源管理中启用更多源后重试" }
    }

    private func load() async {
        guard let source else { error = "书源已删除"; return }
        loading = true; error = nil; info = nil; toc = nil
        let it = item
        let token = UUID(); loadGeneration = token
        do {
            let i = try await LegadoClient.run { try LegadoClient.info(source, bookURL: it.bookURL) }
            guard token == loadGeneration, !Task.isCancelled else { return }
            info = i
            let book: [String: String] = ["name": i.name, "author": i.author, "bookUrl": it.bookURL]
            let t = try await LegadoClient.run { try LegadoClient.toc(source, tocURL: i.tocURL, book: book) }
            guard token == loadGeneration, !Task.isCancelled else { return }
            if t.isEmpty { error = "这本书没有可读目录，可能已下架、受限或书源失效。可换一本书或切换书源后重试。" } else { toc = t }
        } catch {
            if token == loadGeneration, !Task.isCancelled { self.error = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription }
        }
        if token == loadGeneration { loading = false }
    }

    private func read(open: Bool, chapter: Int? = nil) {
        if source?.isMedia == true {if open{mediaChapter=chapter ?? 0;showingMedia=true};return}
        if let saved = onShelf, let toc, !toc.isEmpty, saved.chapterURLs != toc.map(\.url) { store.updateToc(saved, toc) }
        let b = onShelf ?? store.addWeb(item, info: info, toc: toc ?? [])
        if open {
            if let old = resumeFrom, old.id != b.id { store.transferProgress(from: old, to: b) }
            if let chapter, b.chapters.indices.contains(chapter) { store.record(b, chapter: chapter, offset: 0) }
            if let onRead { onRead(b) } else { reading = b }
        }
    }
}

private extension String { var novelNonEmpty: String? { isEmpty ? nil : self } }
