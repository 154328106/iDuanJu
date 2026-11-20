import AVFoundation
import SwiftUI

/// Lightweight AVPlayerLayer host for TVBox VOD & Live playback.
struct TVBoxVideoSurface: UIViewRepresentable {
    let player: AVPlayer
    var videoGravity: AVLayerVideoGravity = .resizeAspect

    final class LayerView: UIView {
        override static var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }

    func makeUIView(context: Context) -> LayerView {
        let v = LayerView()
        v.backgroundColor = .black
        v.playerLayer.player = player
        v.playerLayer.videoGravity = videoGravity
        return v
    }

    func updateUIView(_ uiView: LayerView, context: Context) {
        if uiView.playerLayer.player !== player { uiView.playerLayer.player = player }
        if uiView.playerLayer.videoGravity != videoGravity { uiView.playerLayer.videoGravity = videoGravity }
    }
}

/// 04 · TVBox 点播播放页（16:9 安静播控 + 直链换线 + 选集网格 + 跨站换源）
struct TVBoxVodPlayerView: View {
    let siteKey: String
    let seriesId: String
    let initialTitle: String
    let initialCover: String
    let startEp: Int

    private let store = TVBoxStore.shared
    @Environment(\.dismiss) private var dismiss

    @State private var currentSiteKey: String = ""
    @State private var currentSeriesId: String = ""
    @State private var currentTitle: String = ""
    @State private var currentCover: String = ""
    @State private var detail: HGSeriesDetail?
    @State private var loading = true
    @State private var errorMsg: String?
    @State private var epIndex = 0
    @State private var lineIndex = 0
    @State private var reverseOrder = false
    @State private var speed: Float = 1.0
    @State private var fullscreen = false
    @State private var controlsVisible = true
    @State private var isPlaying = false
    @State private var positionSec: Double = 0
    @State private var durationSec: Double = 0
    @State private var altMatches: [(site: TVBoxRepoSite, series: HGSeries)] = []
    @State private var player = AVPlayer()

    var body: some View {
        let activeSiteKey = currentSiteKey.isEmpty ? siteKey : currentSiteKey
        let activeSeriesId = currentSeriesId.isEmpty ? seriesId : currentSeriesId
        let site = store.findSite(activeSiteKey)
        let currentEp = detail?.episodes.indices.contains(epIndex) == true ? detail?.episodes[epIndex] : nil
        let streams = currentEp.map { MacCMSProvider.streams(for: $0) } ?? []
        let activeStream = streams.indices.contains(lineIndex) ? streams[lineIndex] : streams.first
        let resolvedTitle = detail?.title.nilIfEmpty ?? (currentTitle.isEmpty ? initialTitle : currentTitle)

        VStack(spacing: 0) {
            // 顶部 16:9 / 全屏播放舞台
            ZStack {
                TVBoxVideoSurface(player: player, videoGravity: .resizeAspect)
                    .contentShape(Rectangle())
                    .onTapGesture { withAnimation(.easeInOut(duration: 0.18)) { controlsVisible.toggle() } }

                if loading {
                    ProgressView().tint(.white)
                }

                if controlsVisible {
                    VStack {
                        HStack(spacing: 8) {
                            Button {
                                if fullscreen {
                                    fullscreen = false
                                    OrientationGate.lockPortrait()
                                } else {
                                    dismiss()
                                }
                            } label: {
                                Image(systemName: "chevron.left")
                                    .foregroundStyle(.white)
                                    .frame(width: 32, height: 32)
                                    .background(Color.black.opacity(0.55), in: Circle())
                            }.buttonStyle(.plain)

                            Text(activeStream?.quality ?? "HLS · 直链")
                                .font(.caption2.weight(.semibold)).foregroundStyle(.white)
                                .padding(.horizontal, 9).padding(.vertical, 4)
                                .background(Color.black.opacity(0.45), in: Capsule())
                            Spacer()
                        }
                        .padding(.horizontal, 12).padding(.top, 10)

                        Spacer()

                        HStack(spacing: 32) {
                            Button { seek(by: -10) } label: {
                                Image(systemName: "gobackward.10").font(.title2).foregroundStyle(.white)
                            }.buttonStyle(.plain)
                            Button {
                                if player.timeControlStatus == .playing { player.pause(); isPlaying = false }
                                else { player.playImmediately(atRate: speed); isPlaying = true }
                            } label: {
                                Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                                    .font(.title3).foregroundStyle(.white)
                                    .frame(width: 48, height: 48)
                                    .background(Color.black.opacity(0.6), in: Circle())
                                    .overlay { Circle().strokeBorder(Color.white.opacity(0.22)) }
                            }.buttonStyle(.plain)
                            Button { seek(by: 10) } label: {
                                Image(systemName: "goforward.10").font(.title2).foregroundStyle(.white)
                            }.buttonStyle(.plain)
                        }

                        Spacer()

                        HStack(spacing: 8) {
                            Text(fmtSec(positionSec)).font(.caption2.monospacedDigit()).foregroundStyle(.white)
                            Slider(
                                value: Binding(
                                    get: { durationSec > 0 ? min(1, max(0, positionSec / durationSec)) : 0 },
                                    set: { frac in
                                        guard durationSec > 0 else { return }
                                        let t = CMTime(seconds: frac * durationSec, preferredTimescale: 600)
                                        player.seek(to: t)
                                    }
                                )
                            ).tint(.white)
                            Text(fmtSec(durationSec)).font(.caption2.monospacedDigit()).foregroundStyle(.white)
                            Button {
                                fullscreen.toggle()
                                if fullscreen { OrientationGate.enterLandscape() } else { OrientationGate.lockPortrait() }
                            } label: {
                                Image(systemName: fullscreen ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right")
                                    .font(.subheadline).foregroundStyle(.white)
                            }.buttonStyle(.plain)
                        }
                        .padding(.horizontal, 12).padding(.bottom, 8)
                        .background(LinearGradient(colors: [.clear, .black.opacity(0.65)], startPoint: .top, endPoint: .bottom))
                    }
                }
            }
            .frame(maxWidth: .infinity)
            .frame(height: fullscreen ? nil : 230)
            .frame(maxHeight: fullscreen ? .infinity : 230)
            .background(Color.black)

            if !fullscreen {
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        HStack(alignment: .top) {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(resolvedTitle).font(.title3.weight(.heavy)).foregroundStyle(STVTheme.text).lineLimit(1)
                                Text("\(currentEp?.title ?? "") · \(site?.name ?? "TVBox")\(detail?.tags.first.map { " · \($0)" } ?? "")")
                                    .font(.caption).foregroundStyle(STVTheme.secondary).lineLimit(1)
                            }
                            Spacer()
                            Button {
                                speed = speed == 1.0 ? 1.25 : (speed == 1.25 ? 1.5 : (speed == 1.5 ? 2.0 : 1.0))
                                if player.timeControlStatus == .playing { player.rate = speed }
                            } label: {
                                Text(String(format: "%.2g×", speed))
                                    .font(.caption.weight(.semibold)).foregroundStyle(STVTheme.text)
                                    .padding(.horizontal, 12).frame(height: 32)
                                    .background(STVTheme.glass, in: Capsule())
                                    .overlay { Capsule().strokeBorder(STVTheme.glassBorder) }
                            }.buttonStyle(.plain)
                        }

                        if let errorMsg {
                            Text(errorMsg).font(.caption).foregroundStyle(STVTheme.accentWarm)
                        }

                        // 播放线路
                        if !streams.isEmpty {
                            HStack {
                                Text("播放线路（直链解析）").font(.subheadline.weight(.bold)).foregroundStyle(STVTheme.text)
                                Spacer()
                                Text("\(streams.count) 条可用").font(.caption).foregroundStyle(STVTheme.secondary)
                            }
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 8) {
                                    ForEach(streams.indices, id: \.self) { idx in
                                        let on = idx == lineIndex
                                        Button { lineIndex = idx } label: {
                                            HStack(spacing: 6) {
                                                Circle().fill(on ? Color(red: 0.37, green: 0.82, blue: 0.54) : STVTheme.secondary.opacity(0.5)).frame(width: 7, height: 7)
                                                Text(streams[idx].quality ?? "线路 \(idx + 1)").font(.caption.weight(.semibold))
                                            }
                                            .foregroundStyle(on ? STVTheme.accent : STVTheme.secondary)
                                            .padding(.horizontal, 12).frame(height: 34)
                                            .background(on ? STVTheme.accent.opacity(0.15) : STVTheme.glass, in: RoundedRectangle(cornerRadius: 10))
                                            .overlay { RoundedRectangle(cornerRadius: 10).strokeBorder(on ? STVTheme.accent.opacity(0.42) : STVTheme.glassBorder) }
                                        }.buttonStyle(.plain)
                                    }
                                }
                            }
                        }

                        // 选集网格
                        let eps = detail?.episodes ?? []
                        if !eps.isEmpty {
                            HStack {
                                Text("选集 · 共 \(eps.count) 集").font(.subheadline.weight(.bold)).foregroundStyle(STVTheme.text)
                                Spacer()
                                Button { reverseOrder.toggle() } label: {
                                    Label(reverseOrder ? "倒序" : "正序", systemImage: "arrow.up.arrow.down")
                                        .font(.caption).foregroundStyle(STVTheme.secondary)
                                }.buttonStyle(.plain)
                            }
                            let indices = reverseOrder ? Array(eps.indices.reversed()) : Array(eps.indices)
                            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 4), spacing: 8) {
                                ForEach(indices, id: \.self) { realIdx in
                                    let on = realIdx == epIndex
                                    Button { epIndex = realIdx; lineIndex = 0 } label: {
                                        Text(eps[realIdx].title)
                                            .font(.caption.weight(.semibold)).lineLimit(1)
                                            .foregroundStyle(on ? STVTheme.accent : STVTheme.text)
                                            .frame(maxWidth: .infinity).frame(height: 38)
                                            .background(on ? STVTheme.accent.opacity(0.16) : STVTheme.glass, in: RoundedRectangle(cornerRadius: 10))
                                            .overlay { RoundedRectangle(cornerRadius: 10).strokeBorder(on ? STVTheme.accent.opacity(0.42) : STVTheme.glassBorder) }
                                    }.buttonStyle(.plain)
                                }
                            }
                        }

                        // 跨站换源
                        if !altMatches.isEmpty {
                            HStack {
                                Text("跨站换源（同片匹配）").font(.subheadline.weight(.bold)).foregroundStyle(STVTheme.text)
                                Spacer()
                                Text("\(altMatches.count) 个可用站点").font(.caption).foregroundStyle(STVTheme.secondary)
                            }.padding(.top, 4)

                            VStack(spacing: 0) {
                                ForEach(Array(altMatches.enumerated()), id: \.offset) { idx, pair in
                                    HStack(spacing: 10) {
                                        Circle().fill(Color(red: 0.37, green: 0.82, blue: 0.54)).frame(width: 7, height: 7)
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(pair.site.name).font(.subheadline.weight(.semibold)).foregroundStyle(STVTheme.text)
                                            let ping = pair.site.latencyMs > 0 && pair.site.latencyMs < 9999 ? " · \(pair.site.latencyMs)ms" : ""
                                            Text("\(pair.series.subtitle ?? "可播放")\(ping)").font(.caption2).foregroundStyle(STVTheme.secondary).lineLimit(1)
                                        }
                                        Spacer()
                                        Button {
                                            currentSiteKey = pair.site.key
                                            currentSeriesId = pair.series.seriesId
                                            currentTitle = pair.series.title
                                            currentCover = pair.series.cover
                                            epIndex = 0; lineIndex = 0
                                        } label: {
                                            Text("切换")
                                                .font(.caption.weight(.semibold)).foregroundStyle(STVTheme.accent)
                                                .padding(.horizontal, 11).frame(height: 28)
                                                .background(STVTheme.accent.opacity(0.15), in: Capsule())
                                        }.buttonStyle(.plain)
                                    }.padding(.vertical, 10)
                                    if idx < altMatches.count - 1 { Divider().overlay(STVTheme.text.opacity(0.07)) }
                                }
                            }
                        }
                    }
                    .padding(16)
                }
                .background(STVTheme.bg)
            }
        }
        .background((fullscreen ? Color.black : STVTheme.bg).ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .task(id: "\(activeSiteKey)|\(activeSeriesId)") {
            await loadDetail(siteKey: activeSiteKey, seriesId: activeSeriesId)
            await loadCrossSiteMatches(title: resolvedTitle, excludeSiteKey: activeSiteKey)
        }
        .onChange(of: activeStream?.url) { _, newURL in
            guard let u = newURL, let url = URL(string: u) else { return }
            let asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": ["User-Agent": PluginHostContext.defaultUA]])
            player.replaceCurrentItem(with: AVPlayerItem(asset: asset))
            player.playImmediately(atRate: speed)
            isPlaying = true
        }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                let cur = player.currentTime().seconds
                let dur = player.currentItem?.duration.seconds ?? 0
                if cur.isFinite { positionSec = max(0, cur) }
                if dur.isFinite && dur > 0 { durationSec = dur }
                if let d = detail, let ep = currentEp, let site, positionSec > 2 {
                    store.recordWatch(TVBoxRecentWatch(
                        siteKey: site.key, siteName: site.name, seriesId: activeSeriesId,
                        title: d.title, cover: d.cover.isEmpty ? initialCover : d.cover,
                        episodeIndex: epIndex, episodeTitle: ep.title,
                        lineName: activeStream?.quality?.components(separatedBy: " · ").first ?? "",
                        positionSec: positionSec, durationSec: durationSec
                    ))
                }
            }
        }
        .onDisappear {
            player.pause()
            OrientationGate.lockPortrait()
        }
    }

    private func loadDetail(siteKey: String, seriesId: String) async {
        guard let site = store.findSite(siteKey) else { loading = false; errorMsg = "未找到对应站点"; return }
        loading = true; errorMsg = nil
        do {
            let p = MacCMSProvider(source: .cms(site.key), site: site.toCMSSite())
            let d = try await p.detail(HGSeries(source: .cms(site.key), seriesId: seriesId, title: initialTitle, cover: initialCover))
            detail = d
            epIndex = min(max(0, startEp), max(0, d.episodes.count - 1))
            if let ep = d.episodes.indices.contains(epIndex) ? d.episodes[epIndex] : nil,
               let firstURL = MacCMSProvider.streams(for: ep).first?.url,
               let url = URL(string: firstURL) {
                let asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": ["User-Agent": PluginHostContext.defaultUA]])
                player.replaceCurrentItem(with: AVPlayerItem(asset: asset))
                player.playImmediately(atRate: speed)
                isPlaying = true
            }
        } catch {
            errorMsg = error.localizedDescription
        }
        loading = false
    }

    private func loadCrossSiteMatches(title: String, excludeSiteKey: String) async {
        let q = title.trimmingCharacters(in: .whitespaces)
        let others = Array((store.activeRepo?.sites ?? []).filter { $0.enabled && $0.key != excludeSiteKey }.prefix(6))
        guard !q.isEmpty, !others.isEmpty else { altMatches = []; return }
        let res: [(TVBoxRepoSite, HGSeries)] = await withTaskGroup(of: (TVBoxRepoSite, HGSeries)?.self) { group in
            for s in others {
                group.addTask {
                    let p = MacCMSProvider(source: .cms(s.key), site: s.toCMSSite())
                    guard let list = try? await p.search(q, page: 1).items else { return nil }
                    let hit = list.first { $0.title.caseInsensitiveCompare(q) == .orderedSame } ?? list.first { $0.title.localizedCaseInsensitiveContains(q) }
                    return hit.map { (s, $0) }
                }
            }
            var out: [(TVBoxRepoSite, HGSeries)] = []
            for await item in group { if let item { out.append(item) } }
            return out
        }
        altMatches = res
    }

    private func seek(by delta: Double) {
        let target = max(0, positionSec + delta)
        player.seek(to: CMTime(seconds: target, preferredTimescale: 600))
    }

    private func fmtSec(_ sec: Double) -> String {
        let t = max(0, Int(sec))
        let h = t / 3600, m = (t % 3600) / 60, s = t % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%02d:%02d", m, s)
    }
}
