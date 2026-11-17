import AVFoundation

/// AVKit's native PiP buttons set AVPlayer.rate directly. Internal load/pause/play commands
/// must not become a second user command when their notifications arrive later.
@MainActor final class PlayerTransportObserver {
    var onRequest: ((Bool) -> Void)?
    var acceptingRequests = false
    private var expectedPaused = true
    private weak var player: AVPlayer?
    private var observation: NSObjectProtocol?

    func expectPaused(_ paused: Bool) { expectedPaused = paused }

    func bind(_ player: AVPlayer?) {
        if self.player === player { return }
        if let observation { NotificationCenter.default.removeObserver(observation) }
        observation = nil
        self.player = player
        guard let player else { return }
        observation = NotificationCenter.default.addObserver(forName: AVPlayer.rateDidChangeNotification, object: player, queue: .main) { [weak self, weak player] note in
            guard note.userInfo?[AVPlayer.rateDidChangeReasonKey] as? String == AVPlayer.RateDidChangeReason.setRateCalled.rawValue else { return }
            MainActor.assumeIsolated {
                guard let self, let player, self.player === player, self.acceptingRequests else { return }
                let paused = player.rate == 0
                guard paused != self.expectedPaused else { return }
                self.expectedPaused = paused
                self.onRequest?(!paused)
            }
        }
    }

    deinit { if let observation { NotificationCenter.default.removeObserver(observation) } }
}
