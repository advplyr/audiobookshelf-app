import AVFoundation
import Foundation

/**
 * StayAwakeManager — dead-man's-switch sleep detection for iOS.
 *
 * Periodically plays a soft chime and waits for user confirmation.
 * Miss 1 → volume drops to 50%. Miss 2 → playback pauses.
 *
 * Adapts interval: starts at 15min, shortens after misses, extends after confirms.
 */
public class StayAwakeManager {
    private let tag = "StayAwakeManager"

    private var isActive = false
    private var missedChecks = 0
    private var checkTimer: Timer?
    private var responseTimer: Timer?
    private var waitingForResponse = false
    private var chimePlayer: AVAudioPlayer?

    private var checkIntervalSec: TimeInterval = 15 * 60  // 15 min
    private let responseWindowSec: TimeInterval = 60       // 1 min
    private let minIntervalSec: TimeInterval = 5 * 60     // 5 min

    // Callbacks — set by the player controller
    public var onCheck: ((_ missedSoFar: Int, _ responseWindowSec: Int) -> Void)?
    public var onMissed: ((_ missCount: Int, _ isSleepDetected: Bool) -> Void)?
    public var onSleepDetected: ((_ positionMs: Int64) -> Void)?
    public var onConfirmed: (() -> Void)?
    public var getCurrentTimeMs: (() -> Int64)?
    public var setVolume: ((_ volume: Float) -> Void)?
    public var pausePlayback: (() -> Void)?
    public var isPlaying: (() -> Bool)?

    public func start() {
        guard !isActive else { return }
        NSLog("[\(tag)] Starting Stay Awake mode")
        isActive = true
        missedChecks = 0
        waitingForResponse = false
        setVolume?(1.0)
        scheduleNextCheck()
    }

    public func stop() {
        NSLog("[\(tag)] Stopping Stay Awake mode")
        isActive = false
        checkTimer?.invalidate()
        responseTimer?.invalidate()
        checkTimer = nil
        responseTimer = nil
        waitingForResponse = false
        missedChecks = 0
        setVolume?(1.0)
    }

    public func isRunning() -> Bool { return isActive }

    /// Called when user taps "Still listening"
    public func confirmAwake() {
        guard isActive else { return }
        NSLog("[\(tag)] User confirmed awake")
        waitingForResponse = false
        responseTimer?.invalidate()
        missedChecks = 0
        setVolume?(1.0)
        checkIntervalSec = min(checkIntervalSec + 120, 20 * 60)
        onConfirmed?()
        scheduleNextCheck()
    }

    private func scheduleNextCheck() {
        checkTimer?.invalidate()
        guard isActive else { return }
        checkTimer = Timer.scheduledTimer(withTimeInterval: checkIntervalSec, repeats: false) { [weak self] _ in
            DispatchQueue.main.async { self?.performCheck() }
        }
    }

    private func performCheck() {
        guard isActive, isPlaying?() == true else {
            scheduleNextCheck()
            return
        }
        NSLog("[\(tag)] Awake check (missed: \(missedChecks))")
        playChime()
        waitingForResponse = true
        onCheck?(missedChecks, Int(responseWindowSec))

        responseTimer?.invalidate()
        responseTimer = Timer.scheduledTimer(withTimeInterval: responseWindowSec, repeats: false) { [weak self] _ in
            DispatchQueue.main.async { self?.handleMissed() }
        }
    }

    private func handleMissed() {
        guard waitingForResponse, isActive else { return }
        missedChecks += 1
        waitingForResponse = false
        NSLog("[\(tag)] Missed check #\(missedChecks)")

        if missedChecks == 1 {
            setVolume?(0.5)
            checkIntervalSec = max(checkIntervalSec / 2, minIntervalSec)
            onMissed?(missedChecks, false)
            scheduleNextCheck()
        } else {
            let pos = getCurrentTimeMs?() ?? 0
            pausePlayback?()
            stop()
            onMissed?(missedChecks, true)
            onSleepDetected?(pos)
        }
    }

    private func playChime() {
        guard let url = Bundle.main.url(forResource: "bell", withExtension: "wav") else { return }
        do {
            chimePlayer = try AVAudioPlayer(contentsOf: url)
            chimePlayer?.volume = 0.3
            chimePlayer?.play()
        } catch {
            NSLog("[\(tag)] Chime failed: \(error)")
        }
    }
}
