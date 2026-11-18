import SwiftUI
import AVKit

/// Native AVPlayer consumption for Legado audio/video, isolated from text layout/TTS/cache.
struct NovelMediaPlayer:View {
    let source:LegadoSource
    let chapters:[LegadoChapter]
    let book:[String:String]
    @State private var selected=0
    @State private var player:AVPlayer?
    @State private var proxyToken:String?
    @State private var failure:String?
    @State private var progress="正在缓冲"
    @Environment(\.dismiss) private var dismiss
    var body:some View {
        NavigationStack {
            VStack {
                if source.bookSourceType==1 {Text("有声书 · 原生音频播放").font(.headline).padding()}
                if let player {VideoPlayer(player:player).frame(minHeight:240).accessibilityIdentifier("novel.media.player")}else{ProgressView("正在解析媒体…")}
                Text(progress).font(.caption).accessibilityIdentifier("novel.media.status")
                if let failure{Text(failure).foregroundStyle(.red).padding()}
                List(chapters.indices,id:\.self){i in Button{selected=i}label:{HStack{Text(chapters[i].title);Spacer();if selected==i{Image(systemName:"speaker.wave.2.fill")}}}}
            }
            .navigationTitle(book["name"] ?? source.bookSourceName)
            .toolbar{ToolbarItem(placement:.cancellationAction){Button("关闭播放"){dismiss()}}}
            .task(id:selected){await play()}
            .onReceive(Timer.publish(every:0.2,on:.main,in:.common).autoconnect()){_ in
                if let player {let seconds=player.currentTime().seconds;progress=String(format:"已播放 %.2f 秒",seconds.isFinite ? seconds : 0);if let e=player.currentItem?.error{failure=e.localizedDescription}}
            }
            .onDisappear{stop()}
        }
    }
    @MainActor private func stop(){
        player?.pause();player=nil
        if let token=proxyToken{LocalHLSProxy.shared.release(token)};proxyToken=nil
    }
    @MainActor private func play()async {
        stop();failure=nil;progress="正在缓冲"
        guard chapters.indices.contains(selected)else{return}
        var chapter=chapters[selected];chapter.index=selected
        let nextChapterURL=chapters.indices.contains(selected+1) ? chapters[selected+1].url : nil
        do {
            let cancel=LegadoCancelToken()
            let media=try await withTaskCancellationHandler(operation:{try await LegadoClient.run{try cancel.bind{try LegadoMedia.resolve(source,chapter:chapter,book:book,nextChapterURL:nextChapterURL)}}},onCancel:{cancel.cancel()})
            guard !Task.isCancelled else{return}
            let local=try await LegadoClient.run{try LocalHLSProxy.shared.legadoURL(media)}
            let token=LocalHLSProxy.token(of:local)
            guard !Task.isCancelled else{if let token{LocalHLSProxy.shared.release(token)};return}
            proxyToken=token
            guard let localURL=URL(string:local)else{throw LegadoError.rule("安全播放地址无效")}
            let asset=AVURLAsset(url:localURL)
            let next=AVPlayer(playerItem:AVPlayerItem(asset:asset));player=next;next.play()
        }catch{if !Task.isCancelled{failure=error.localizedDescription}}
    }
}
