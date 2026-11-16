import SwiftUI

struct NovelDownloadView: View {
    let book: NovelBook
    var chapter: Int = 0
    @Environment(\.dismiss) private var dismiss
    @AppStorage("novel.prefetch") private var prefetch = 3
    private let loader = NovelWebLoader.shared
    private let store = NovelStore.shared

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 14) {
                        NovelCover(book: book).frame(width: 48, height: 66)
                        VStack(alignment: .leading, spacing: 5) {
                            Text(book.title).font(.headline)
                            Text("已缓存 \(store.cachedCount(book)) / \(book.chapters.count) 章").font(.subheadline).foregroundStyle(.secondary)
                        }
                    }.padding(.vertical, 4)
                    if let state = loader.downloadStates[book.id] {
                        ProgressView(value: Double(state.completed), total: Double(max(1, state.total)))
                        Text("本次 \(state.completed)/\(state.total) · 失败 \(state.failures)").font(.caption).foregroundStyle(.secondary)
                        if !state.message.isEmpty { Text(state.message).font(.caption).foregroundStyle(state.failures > 0 ? .red : .secondary) }
                        if state.active { Button("暂停下载", role: .destructive) { loader.stopDownload(book) } }
                    }
                }
                Section("离线下载") {
                    Button("缓存后 50 章") { loader.download(book, from: chapter, count: 50) }
                    Button("缓存后 200 章") { loader.download(book, from: chapter, count: 200) }
                    Button("下载全本 / 继续未完成下载") { loader.download(book, from: 0, count: nil) }
                }.disabled(loader.downloadStates[book.id]?.active == true)
                Section {
                    Picker("提前缓存", selection: $prefetch) {
                        Text("关闭").tag(0)
                        Text("后 3 章").tag(3)
                        Text("后 10 章").tag(10)
                        Text("后 20 章").tag(20)
                    }
                } header: { Text("阅读预取") } footer: {
                    Text("已缓存章节可断网阅读。下载时请保持应用在前台；暂停或中断后可继续，已完成章节不会重复请求。仅缓存当前书源允许读取的正文。")
                }
            }
            .navigationTitle("缓存与下载")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
        }
    }
}
