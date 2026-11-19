import Foundation

struct DownloadSpeedTracker {
    private var lastBytes: Double?
    private var lastTime: TimeInterval?
    private(set) var bytesPerSecond: Double = 0

    mutating func record(totalBytes: Double, at time: TimeInterval) {
        guard let previousBytes = lastBytes, let previousTime = lastTime else {
            lastBytes = totalBytes
            lastTime = time
            return
        }

        let elapsed = time - previousTime
        guard elapsed > 0.1 else { return }

        let instantaneousRate = max(0, (totalBytes - previousBytes) / elapsed)
        bytesPerSecond = bytesPerSecond == 0
            ? instantaneousRate
            : bytesPerSecond * 0.7 + instantaneousRate * 0.3
        lastBytes = totalBytes
        lastTime = time
    }
}
