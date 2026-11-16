import Foundation
import Observation

/// Fetches 书源 chapters on demand (and the next ones ahead of the reader), caching them on disk.
@MainActor @Observable
final class NovelWebLoader {
    static let shared = NovelWebLoader()
    /// Bumped whenever a chapter arrives or fails, so open readers re-paginate.
    private(set) var revision = 0
    private(set) var failed: [String: String] = [:]
    @ObservationIgnored private var running: [String: Task<Void, Error>] = [:]
    @ObservationIgnored private var downloads: [String: Task<Void, Never>] = [:]
    struct DownloadState {
        var completed = 0
        var total = 0
        var failures = 0
        var active = false
        var message = ""
    }
    private(set) var downloadStates: [String: DownloadState] = [:]
    private let store = NovelStore.shared

    private func key(_ b: NovelBook, _ i: Int) -> String { NovelStore.chapterFile(b, i).path }

    func error(_ b: NovelBook, _ i: Int) -> String? { failed[key(b, i)] }
    func isLoading(_ b: NovelBook, _ i: Int) -> Bool { running[key(b, i)] != nil }

    func ensure(_ b: NovelBook, _ i: Int) {
        guard b.isWeb, b.chapters.indices.contains(i), !store.isCached(b, i) else { return }
        let k = key(b, i)
        guard running[k] == nil, failed[k] == nil else { return }
        Task { try? await load(b, i) }
    }

    func load(_ b: NovelBook, _ i: Int) async throws {
        guard b.isWeb, b.chapters.indices.contains(i), !store.isCached(b, i) else { return }
        let k = key(b, i)
        if let task = running[k] { return try await task.value }
        guard let source = LegadoSourceStore.shared.source(b.sourceURL ?? ""), let urls = b.chapterURLs, urls.indices.contains(i) else {
            failed[k] = "书源已删除，无法加载"
            revision += 1
            throw LegadoError.empty("书源已删除，无法加载")
        }
        let chapter = LegadoChapter(title: b.chapters[i].title, url: urls[i])
        let next = urls.indices.contains(i + 1) ? urls[i + 1] : nil
        let bookInfo: [String: Any] = ["name": b.title, "author": b.author ?? "", "bookUrl": b.bookURL ?? "", "tocUrl": b.tocURL ?? ""]
        nonisolated(unsafe) let info = bookInfo
        let task = Task<Void, Error> {
            do {
                let text = try await LegadoClient.run { try LegadoClient.content(source, chapter: chapter, nextChapterURL: next, book: info) }
                // Chapter heading first, like imported books, so the reader styles it.
                let full = text.hasPrefix(chapter.title) ? text : chapter.title + "\n" + text
                try Task.checkCancellation()
                try store.cache(b, i, full)
                failed[k] = nil
            } catch {
                failed[k] = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
                throw error
            }
        }
        running[k] = task
        defer { running[k] = nil; revision += 1 }
        try await task.value
    }

    func retry(_ b: NovelBook, _ i: Int) {
        failed[key(b, i)] = nil
        ensure(b, i)
        revision += 1
    }

    /// Current chapter plus the configured lookahead, stopping on the first failed request.
    func prefetch(_ b: NovelBook, around i: Int) {
        ensure(b, i)
        let count = min(20, max(0, UserDefaults.standard.object(forKey: "novel.prefetch") as? Int ?? 3))
        guard count > 0, i >= 0, i + 1 < b.chapters.count else { return }
        Task {
            for j in (i + 1)..<min(b.chapters.count, i + count + 1) {
                guard store.book(b.id) != nil else { return }
                do { try await load(b, j) } catch { return }
            }
        }
    }

    func download(_ book: NovelBook, from: Int, count: Int?) {
        guard downloads[book.id] == nil, !book.chapters.isEmpty else { return }
        let start = min(max(0, from), book.chapters.count)
        let end = count.map { min(book.chapters.count, start + max(0, $0)) } ?? book.chapters.count
        downloadStates[book.id] = DownloadState(total: end - start, active: true)
        downloads[book.id] = Task {
            defer { downloads[book.id] = nil; downloadStates[book.id]?.active = false }
            for i in start..<end {
                if Task.isCancelled || store.book(book.id) == nil { downloadStates[book.id]?.message = "已暂停，可继续下载"; return }
                do {
                    try await load(book, i)
                    downloadStates[book.id]?.completed += 1
                } catch {
                    downloadStates[book.id]?.failures += 1
                    downloadStates[book.id]?.message = error.localizedDescription
                    // A blocked/login-required source must not fan out across an entire book.
                    return
                }
            }
            if downloadStates[book.id]?.failures == 0 { downloadStates[book.id]?.message = "下载完成，已可离线阅读" }
        }
    }

    func stopDownload(_ book: NovelBook) { downloads[book.id]?.cancel() }

    /// Re-reads the 目录 (new chapters of an ongoing book). Returns the new chapter count.
    func refreshToc(_ b: NovelBook) async throws -> Int {
        guard let source = LegadoSourceStore.shared.source(b.sourceURL ?? ""), let toc = b.tocURL else { return b.chapters.count }
        let book = ["name": b.title, "author": b.author ?? "", "bookUrl": b.bookURL ?? "", "tocUrl": toc]
        let list = try await LegadoClient.run { try LegadoClient.toc(source, tocURL: toc, book: book) }
        if list.count >= b.chapters.count { store.updateToc(b, list) }
        return list.count
    }
}
