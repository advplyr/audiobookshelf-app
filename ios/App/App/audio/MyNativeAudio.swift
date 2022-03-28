import Foundation
import Capacitor
import MediaPlayer
import AVKit

func parseSleepTime(millis: String?) -> Double {
    (Double(millis ?? "0") ?? 0) / 1000
}

@objc(MyNativeAudio)
public class MyNativeAudio: CAPPlugin {
    var playerContext = 0
    
    var currentSleepTimer: Timer? = nil
    var remainingSleepDuration: Double = 0
    
    @objc func initPlayer(_ call: CAPPluginCall)  {
        NSLog("Init Player")
        let audiobook = Audiobook(
            streamId: call.getString("id")!,
            audiobookId: call.getString("audiobookId")!,
            playlistUrl: call.getString("playlistUrl")!,
            
            startTime: (Double(call.getString("startTime") ?? "0") ?? 0.0) / 1000,
            duration: call.getDouble("duration") ?? 0,
            
            title: call.getString("title") ?? "No Title",
            series: call.getString("series"),
            author: call.getString("author"),
            artworkUrl: call.getString("cover"),
            
            token: call.getString("token") ?? ""
        )
        let playWhenReady = call.getBool("playWhenReady", false)
        
        if AudioPlayer.instance != nil && AudioPlayer.instance?.audiobook.streamId == audiobook.streamId {
            if playWhenReady {
                AudioPlayer.instance?.play()
            }
            
            call.resolve(["success": true])
            return
        } else if AudioPlayer.instance != nil && AudioPlayer.instance?.audiobook.streamId != audiobook.streamId {
            stop()
        }
        
        AudioPlayer.instance = AudioPlayer(audiobook: audiobook, playWhenReady: playWhenReady)
        AudioPlayer.instance!.addObserver(self, forKeyPath: #keyPath(AudioPlayer.status), options: .new, context: &playerContext)
        
        call.resolve(["success": true])
    }
    override public func load() {
        NSLog("Load MyNativeAudio")
        NotificationCenter.default.addObserver(self, selector: #selector(sendMetadata), name: UIApplication.willEnterForegroundNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(sendMetadata), name: UIApplication.didBecomeActiveNotification, object: nil)
    }
    
    public override func observeValue(forKeyPath keyPath: String?, of object: Any?, change: [NSKeyValueChangeKey : Any]?, context: UnsafeMutableRawPointer?) {
        guard context == &playerContext else {
            super.observeValue(forKeyPath: keyPath, of: object, change: change, context: context)
            return
        }
        
        NSLog("AudioPlayer state change: \(String(describing: keyPath))")

        if keyPath == #keyPath(AudioPlayer.status) || keyPath == #keyPath(AudioPlayer.rate) {
            sendMetadata()
        }
    }
    
    @objc func seekForward(_ call: CAPPluginCall) {
        if AudioPlayer.instance == nil {
            call.resolve()
            return
        }
        
        let amount = (Double(call.getString("amount", "0")) ?? 0) / 1000
        let destinationTime = AudioPlayer.instance!.getCurrentTime() + amount
        
        AudioPlayer.instance!.seek(destinationTime)
        call.resolve()
    }
    @objc func seekBackward(_ call: CAPPluginCall) {
        if AudioPlayer.instance == nil {
            call.resolve()
            return
        }
        
        let amount = (Double(call.getString("amount", "0")) ?? 0) / 1000
        let destinationTime = AudioPlayer.instance!.getCurrentTime() - amount
        
        AudioPlayer.instance!.seek(destinationTime)
        call.resolve()
    }
    @objc func seekPlayer(_ call: CAPPluginCall) {
        if AudioPlayer.instance == nil {
            call.resolve()
            return
        }
        
        let seekTime = (Double(call.getString("timeMs", "0")) ?? 0) / 1000
        NSLog("Seek Player \(seekTime)")
        
        AudioPlayer.instance!.seek(seekTime)
        call.resolve()
    }
    
    @objc func pausePlayer(_ call: CAPPluginCall) {
        if AudioPlayer.instance == nil {
            call.resolve()
            return
        }
        
        AudioPlayer.instance!.pause()
        
        sendPlaybackStatusUpdate(false)
        call.resolve()
    }
    @objc func playPlayer(_ call: CAPPluginCall) {
        if AudioPlayer.instance == nil {
            call.resolve()
            return
        }
        
        AudioPlayer.instance!.play(allowSeekBack: true)
        
        sendPlaybackStatusUpdate(true)
        call.resolve()
    }
    
    @objc func terminateStream(_ call: CAPPluginCall) {
        stop()
        call.resolve()
    }
    @objc func stop(_ call: CAPPluginCall? = nil) {
        if AudioPlayer.instance != nil {
            AudioPlayer.instance!.destroy()
        }
        AudioPlayer.instance = nil
        
        if call != nil {
            call!.resolve([ "result": true ])
        }
    }
    
    @objc func getCurrentTime(_ call: CAPPluginCall) {
        if AudioPlayer.instance == nil {
            call.resolve()
            return
        }
        
        let currentTime = AudioPlayer.instance?.getCurrentTime() ?? 0
        call.resolve([ "value": currentTime * 1000, "bufferedTime": currentTime * 1000 ])
    }
    @objc func getStreamSyncData(_ call: CAPPluginCall) {
        if AudioPlayer.instance == nil {
            call.resolve([ "isPlaying": false, "lastPauseTime": 0, "id": nil ])
            return
        }
        
        call.resolve([ "isPlaying": AudioPlayer.instance!.rate > 0.0, "lastPauseTime": 0, "id": AudioPlayer.instance?.audiobook.streamId as Any ])
    }
    
    @objc func setPlaybackSpeed(_ call: CAPPluginCall) {
        if AudioPlayer.instance == nil {
            call.resolve()
            return
        }
        
        let speed = call.getFloat("speed") ?? 0
        AudioPlayer.instance!.setPlaybackRate(speed)
        
        call.resolve()
    }
    
    @objc func setSleepTimer(_ call: CAPPluginCall) {
        if AudioPlayer.instance == nil {
            call.resolve()
            return
        }
        
        let time = parseSleepTime(millis: call.getString("time"))
        setSleepTimer(seconds: time)
        
        call.resolve([ "success": true ])
    }
    @objc func increaseSleepTime(_ call: CAPPluginCall) {
        if AudioPlayer.instance == nil {
            call.resolve()
            return
        }
        
        var time = self.remainingSleepDuration + parseSleepTime(millis: call.getString("time"))
        if time > AudioPlayer.instance!.getDuration() {
            time = AudioPlayer.instance!.getDuration()
        }
        
        setSleepTimer(seconds: time)
        call.resolve([ "success": true ])
    }
    @objc func decreaseSleepTime(_ call: CAPPluginCall) {
        if self.currentSleepTimer == nil {
            call.resolve()
            return
        }
        
        var time = parseSleepTime(millis: call.getString("time"))
        if time < 0 {
            time = 0
        }
        
        setSleepTimer(seconds: time)
        call.resolve([
            "success": true,
        ])
    }
    @objc func cancelSleepTimer(_ call: CAPPluginCall) {
        setSleepTimer(seconds: 0)
        call.resolve([
            "success": true,
        ])
    }
    
    func setSleepTimer(seconds: Double) {
        if AudioPlayer.instance == nil {
            return
        }
        
        remainingSleepDuration = seconds
        currentSleepTimer?.invalidate()
        
        self.notifyListeners("onSleepTimerSet", data: [
            "value": self.remainingSleepDuration,
        ])
        
        if seconds == 0 {
            return
        }
        
        DispatchQueue.main.async {
            self.currentSleepTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { timer in
                self.updateSleepTime()
            }
        }
    }
    func updateSleepTime() {
        if AudioPlayer.instance == nil {
            return
        }
        
        if self.remainingSleepDuration <= 0 {
            if currentSleepTimer != nil {
                currentSleepTimer!.invalidate()
            }
            self.notifyListeners("onSleepTimerEnded", data: [
                "value": AudioPlayer.instance!.getCurrentTime(),
            ])
            
            AudioPlayer.instance!.pause()
            return
        }
        
        remainingSleepDuration -= 1
        self.notifyListeners("onSleepTimerSet", data: [
            "value": self.remainingSleepDuration,
        ])
    }
    
    @objc func sendMetadata() {
        if AudioPlayer.instance == nil {
            return
        }
        
        NSLog("fired metadata update")
        
        self.notifyListeners("onMetadata", data: [
            "duration": AudioPlayer.instance!.getDuration() * 1000,
            "currentTime": AudioPlayer.instance!.getCurrentTime() * 1000,
            "stateName": "unknown",
            
            "currentRate": AudioPlayer.instance!.rate
        ])
        sendPlaybackStatusUpdate(AudioPlayer.instance!.rate != 0.0)
    }
    func sendPlaybackStatusUpdate(_ playing: Bool) {
        self.notifyListeners("onPlayingUpdate", data: [
            "value": playing
        ])
    }
}
