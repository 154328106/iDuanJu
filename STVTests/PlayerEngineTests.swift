import XCTest
@testable import STV

final class PlayerEngineTests: XCTestCase {
    func testMPVInputRouting() throws {
        let file = URL(fileURLWithPath: "/tmp/a.mp4")
        guard case .url(let u, _, false)? = MPVPlayerEngine.input(for: .file(file)) else { return XCTFail() }
        XCTAssertEqual(u, file)
        let remote = URL(string: "https://cdn.example.com/v.mp4")!
        guard case .url(_, let h, false)? = MPVPlayerEngine.input(for: .remote(remote, ["Referer": "https://x/"])) else { return XCTFail() }
        XCTAssertEqual(h["Referer"], "https://x/")
        // Loopback HLS (ad-stripped playlist) → HLS path.
        let pl = URL(string: "http://127.0.0.1:5000/abcd/p.m3u8")!
        guard case .url(_, _, true)? = MPVPlayerEngine.input(for: .direct(pl)) else { return XCTFail() }
        // TDLib route → loopback proxy HTTP URL directly consumed by libmpv.
        let tg = URL(string: "http://127.0.0.1:5000/tok1/tg/v.mp4")!
        guard case .url(let tgURL, _, false)? = MPVPlayerEngine.input(for: .direct(tg)) else { return XCTFail() }
        XCTAssertEqual(tgURL, tg)
        // Resource-loader AES / custom playlists stay on AVPlayer.
        XCTAssertNil(MPVPlayerEngine.input(for: .stream(HLSPlayback(headers: [:], keyHex: "00ff"), "https://x/v.mp4")))
        XCTAssertNil(MPVPlayerEngine.input(for: .stream(HLSPlayback(headers: [:], keyHex: ""), "https://x/index.m3u8")))
        guard case .url? = MPVPlayerEngine.input(for: .stream(HLSPlayback(headers: ["Referer": "r"], keyHex: ""), "https://x/v.mp4")) else { return XCTFail() }
    }

    func testHeadersAddUserAgentNotForLoopback() {
        let h = MPVPlayerEngine.headers(["Referer": "r"], for: URL(string: "https://cdn.example.com/a.mp4")!)
        XCTAssertEqual(h["Referer"], "r")
        XCTAssertNotNil(h["User-Agent"])
        let l = MPVPlayerEngine.headers([:], for: URL(string: "http://127.0.0.1:1/a.mp4")!)
        XCTAssertNil(l["Cookie"])
    }

    func testLabels() {
        XCTAssertEqual(MPVPlayerEngine.codecLabel("hevc"), "HEVC")
        XCTAssertEqual(MPVPlayerEngine.codecLabel("h264"), "H.264")
        XCTAssertEqual(MPVPlayerEngine.codecLabel("av1"), "AV1")
        XCTAssertEqual(MPVPlayerEngine.codecLabel(nil), "")
        XCTAssertEqual(PlayerTech.audioName(codec: "eac3", channels: 6), "E-AC3 5.1")
        XCTAssertEqual(PlayerTech.channelLabel(2), "2.0")
        XCTAssertEqual(MediaTrack.title(name: "UND (aac)", language: "und", codec: "aac", channels: 2, index: 0), "轨道 1 · AAC 2.0")
        XCTAssertEqual(MediaTrack.title(name: "Director Commentary", language: "und", codec: "", channels: 0, index: 1), "Director Commentary")
        XCTAssertTrue(MediaTrack.title(name: "", language: "jpn", codec: "aac", channels: 6, index: 1).hasSuffix("AAC 5.1"))
    }

    func testEngineSettingDefaultsToMPV() {
        let d = UserDefaults.standard
        let old = d.object(forKey: PlayerEngineSettings.key)
        defer { d.set(old, forKey: PlayerEngineSettings.key) }
        d.removeObject(forKey: PlayerEngineSettings.key)
        XCTAssertEqual(PlayerEngineSettings.preferred, ProcessInfo.processInfo.environment["STV_ENGINE"] == "avplayer" ? .avplayer : .mpv)
    }
}
