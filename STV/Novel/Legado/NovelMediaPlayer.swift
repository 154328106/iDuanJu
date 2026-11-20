import SwiftUI
import AVKit

struct NovelMediaPlayer: View {
    let source: LegadoSource
    let chapters: [LegadoChapter]
    let startIndex: Int
    let book: [String: String]
    @Environment(\.dismiss) private var dismiss
    @State private var selected: Int?
    @State private var player: AVPlayer?
    @State private var token: String?
    @State private var elapsed = 0.0
    @State private var duration = 0.0
    @State private var drag: Double?
    @State private var playing = false
    @State private var showQueue = false
    @State private var failure: String?
    @State private var retry = 0
    private var index: Int { selected ?? startIndex }
    private var title: String { book["name"] ?? source.bookSourceName }
    private var chapterName: String { chapters.indices.contains(index) ? chapters[index].title : title }
    private func clock(_ seconds: Double) -> String { String(format: "%d:%02d", Int(seconds) / 60, Int(seconds) % 60) }

    var body: some View {
        Group {
            if source.bookSourceType == 4 {
                let series = HGSeries(source: .legado, seriesId: source.id + "|" + (book["bookUrl"] ?? title), title: title, cover: book["cover"] ?? "", orientation: "landscape")
                let episodes = chapters.enumerated().map { HGEpisode(ep: $0.offset + 1, vid: String($0.offset), duration: 0, title: $0.element.title) }
                let detail = HGSeriesDetail(source: series.source, seriesId: series.seriesId, title: title, cover: series.cover, author: book["author"], actors: [], onlineTime: 0, totalEps: episodes.count, episodes: episodes)
                PlayerScreen(series: series, startIndex: startIndex, detail: detail, resolve: { i in .direct(try await mediaURL(i)) })
            } else { audio }
        }.accessibilityElement(children: .contain).accessibilityIdentifier("novel.media.player")
    }

    private var audio: some View {
        VStack(spacing: 16) {
            HStack {
                Button { dismiss() } label: { Image(systemName: "chevron.down").frame(width: 44, height: 44) }.accessibilityLabel("关闭播放")
                Spacer(); Text("有声书").font(.headline); Spacer()
                Button { showQueue = true } label: { Image(systemName: "list.bullet").frame(width: 44, height: 44) }.accessibilityLabel("章节列表")
            }
            GeometryReader { geo in
                if geo.size.width > geo.size.height {
                    HStack(spacing: 26) {
                        cover(side: min(geo.size.width * 0.40, geo.size.height * 0.90))
                        VStack(spacing: 16) { transport }.frame(maxWidth: .infinity)
                    }.frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    VStack(spacing: 16) {
                        GeometryReader { area in cover(side: min(area.size.width * 0.86, area.size.height * 0.9, 320)).frame(maxWidth: .infinity, maxHeight: .infinity) }
                        transport
                    }
                }
            }
        }.padding(.horizontal, 26).foregroundStyle(.primary).tint(.primary).dsAmbient(cool: true)
        .sheet(isPresented: $showQueue) {
            NavigationStack { List(chapters.indices, id: \.self) { i in Button { selected = i; showQueue = false } label: {
                HStack { Text(chapters[i].title); Spacer(); if i == index { Image(systemName: "waveform") } }
            } }.navigationTitle("章节列表") }
        }
        .task(id: "\(index)-\(retry)") { await playAudio() }
        .onReceive(Timer.publish(every: 0.25, on: .main, in: .common).autoconnect()) { _ in
            guard let player else { return }
            playing = player.timeControlStatus == .playing
            let t = player.currentTime().seconds, d = player.currentItem?.duration.seconds ?? 0
            elapsed = t.isFinite ? max(t, 0) : 0; duration = d.isFinite ? max(d, 0) : 0
            if player.currentItem?.status == .failed { failure = "本章暂时无法播放" }
        }
        .onReceive(NotificationCenter.default.publisher(for: .AVPlayerItemDidPlayToEndTime)) { notification in
            if let item = notification.object as? AVPlayerItem, item === player?.currentItem, index + 1 < chapters.count { selected = index + 1 }
        }
        .onDisappear { stop() }
    }

    private func cover(side: CGFloat) -> some View {
        ZStack {
            Color.indigo.opacity(0.13)
            Circle().fill(Color(red: 0.18, green: 0.17, blue: 0.24)).frame(width: side * 0.87)
            ForEach(4..<13) { ring in
                Circle().strokeBorder(.white.opacity(0.13), lineWidth: 1).frame(width: side * CGFloat(ring) / 15)
            }
            Circle().fill(.indigo.opacity(0.9)).frame(width: side * 0.31)
            Image(systemName: "headphones").font(.system(size: side * 0.14)).foregroundStyle(.white)
            if let cover = book["cover"], let url = URL(string: cover), !cover.isEmpty {
                AsyncImage(url: url) { phase in if let image = phase.image { image.resizable().scaledToFill() } }
            }
        }.frame(width: side, height: side).clipShape(RoundedRectangle(cornerRadius: 18))
    }

    @ViewBuilder private var transport: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(chapterName).font(.title2.bold()).lineLimit(2)
            Text(title).foregroundStyle(.secondary).lineLimit(1)
            if let failure { Button(failure + " · 重试") { retry += 1 } }
            Slider(value: Binding(get: { drag ?? min(elapsed, duration) }, set: { drag = $0 }), in: 0...max(duration, 1), onEditingChanged: { editing in
                if !editing, let target = drag { player?.seek(to: CMTime(seconds: target, preferredTimescale: 600)); drag = nil }
            }).disabled(duration <= 0).accessibilityLabel("播放进度")
            HStack { Text(clock(drag ?? elapsed)).accessibilityValue(String(format: "已播放 %.2f 秒", elapsed)).accessibilityIdentifier("novel.media.status"); Spacer(); Text(clock(duration)) }.font(.caption.monospacedDigit())
        }
        HStack(spacing: 36) {
            Button { selected = index - 1 } label: { Image(systemName: "backward.end.fill").frame(width: 44, height: 44) }.disabled(index <= 0).accessibilityLabel("上一章")
            Button { if playing { player?.pause() } else { if duration > 0 && elapsed >= duration { player?.seek(to: .zero) }; player?.play() } } label: {
                if player == nil && failure == nil { ProgressView().frame(width: 64, height: 64) } else { Image(systemName: playing ? "pause.circle.fill" : "play.circle.fill").font(.system(size: 60)) }
            }.disabled(player == nil || failure != nil).accessibilityLabel(playing ? "暂停" : "播放")
            Button { selected = index + 1 } label: { Image(systemName: "forward.end.fill").frame(width: 44, height: 44) }.disabled(index + 1 >= chapters.count).accessibilityLabel("下一章")
        }.font(.title2).padding(.vertical, 12)
    }

    private func mediaURL(_ i: Int) async throws -> URL {
        guard chapters.indices.contains(i) else { throw LegadoError.rule("章节不存在") }
        let chapter: LegadoChapter = { var item = chapters[i]; item.index = i; return item }()
        let next = chapters.indices.contains(i + 1) ? chapters[i + 1].url : nil
        let cancel = LegadoCancelToken()
        let media = try await withTaskCancellationHandler(operation: { try await LegadoClient.run { try cancel.bind { try LegadoMedia.resolve(source, chapter: chapter, book: book, nextChapterURL: next) } } }, onCancel: { cancel.cancel() })
        try Task.checkCancellation()
        let local = try await LegadoClient.run { try LocalHLSProxy.shared.legadoURL(media) }
        guard !Task.isCancelled, let url = URL(string: local) else { if let token = LocalHLSProxy.token(of: local) { LocalHLSProxy.shared.release(token) }; throw CancellationError() }
        return url
    }
    @MainActor private func playAudio() async {
        stop(); failure = nil
        do {
            let url = try await mediaURL(index)
            token = LocalHLSProxy.token(of: url.absoluteString)
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
            let next = AVPlayer(url: url); player = next; next.play()
        } catch { if !Task.isCancelled { failure = error.localizedDescription } }
    }
    @MainActor private func stop() {
        player?.pause(); player = nil; playing = false; elapsed = 0; duration = 0; drag = nil
        if let token { LocalHLSProxy.shared.release(token) }; token = nil
    }
}
