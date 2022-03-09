import Foundation
import Capacitor
import MediaPlayer
import AVKit

func parseSleepTime(millis: String?) -> Double {
    (Double(millis ?? "0") ?? 0) / 1000
}

@objc(MyNativeAudio)
public class MyNativeAudio: CAPPlugin {
    var currentCall: CAPPluginCall?
    var currentPlayer: AudioPlayer?
    
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
        
        if self.currentPlayer != nil && self.currentPlayer?.audiobook.streamId == audiobook.streamId {
            if playWhenReady {
                self.currentPlayer?.play()
            }
            
            call.resolve(["success": true])
            return
        }
        
        self.currentPlayer = AudioPlayer(audiobook: audiobook, playWhenReady: playWhenReady)
        self.currentPlayer!.addObserver(self, forKeyPath: #keyPath(AudioPlayer.status), options: .new, context: &playerContext)
        
        call.resolve(["success": true])
    }
    
    public override func observeValue(forKeyPath keyPath: String?, of object: Any?, change: [NSKeyValueChangeKey : Any]?, context: UnsafeMutableRawPointer?) {
        guard context == &playerContext else {
            super.observeValue(forKeyPath: keyPath, of: object, change: change, context: context)
            return
        }

        if keyPath == #keyPath(AudioPlayer.status) {
            sendMetadata()
        }
    }
    
    @objc func seekForward(_ call: CAPPluginCall) {
        if self.currentPlayer == nil {
            call.resolve()
            return
        }
        
        let amount = (Double(call.getString("amount", "0")) ?? 0) / 1000
        let destinationTime = self.currentPlayer!.getCurrentTime() + amount
        
        self.currentPlayer!.seek(destinationTime)
        call.resolve()
    }
    @objc func seekBackward(_ call: CAPPluginCall) {
        if self.currentPlayer == nil {
            call.resolve()
            return
        }
        
        let amount = (Double(call.getString("amount", "0")) ?? 0) / 1000
        let destinationTime = self.currentPlayer!.getCurrentTime() - amount
        
        self.currentPlayer!.seek(destinationTime)
        call.resolve()
    }
    @objc func seekPlayer(_ call: CAPPluginCall) {
        if self.currentPlayer == nil {
            call.resolve()
            return
        }
        
        let seekTime = (Double(call.getString("timeMs", "0")) ?? 0) / 1000
        NSLog("Seek Player \(seekTime)")
        
        self.currentPlayer!.seek(seekTime)
        call.resolve()
    }
    
    @objc func pausePlayer(_ call: CAPPluginCall) {
        if self.currentPlayer == nil {
            call.resolve()
            return
        }
        
        self.currentPlayer!.pause()
        
        sendPlaybackStatusUpdate(false)
        call.resolve()
    }
    @objc func playPlayer(_ call: CAPPluginCall) {
        if self.currentPlayer == nil {
            call.resolve()
            return
        }
        
        self.currentPlayer!.play()
        
        sendPlaybackStatusUpdate(true)
        call.resolve()
    }
    
    @objc func terminateStream(_ call: CAPPluginCall) {
        stop()
        call.resolve()
    }
    @objc func stop() {
        if let call = currentCall {
            if self.currentPlayer != nil {
                self.currentPlayer!.destroy()
            }
            
            self.currentPlayer = nil
            currentCall = nil;
            call.resolve([ "result": true ])
        }
    }
    
    @objc func getCurrentTime(_ call: CAPPluginCall) {
        if self.currentPlayer == nil {
            call.resolve()
            return
        }
        
        let currentTime = self.currentPlayer?.getCurrentTime() ?? 0
        call.resolve([ "value": currentTime * 1000, "bufferedTime": currentTime * 1000 ])
    }
    @objc func getStreamSyncData(_ call: CAPPluginCall) {
        if self.currentPlayer == nil {
            call.resolve([ "isPlaying": false, "lastPauseTime": 0, "id": nil ])
            return
        }
        
        call.resolve([ "isPlaying": self.currentPlayer!.rate > 0.0, "lastPauseTime": 0, "id": self.currentPlayer?.audiobook.streamId as Any ])
    }
    
    @objc func setPlaybackSpeed(_ call: CAPPluginCall) {
        if self.currentPlayer == nil {
            call.resolve()
            return
        }
        
        let speed = call.getFloat("speed") ?? 0
        self.currentPlayer!.setPlaybackRate(speed)
        
        call.resolve()
    }
    
    @objc func setSleepTimer(_ call: CAPPluginCall) {
        if self.currentPlayer == nil {
            call.resolve()
            return
        }
        
        let time = parseSleepTime(millis: call.getString("time"))
        setSleepTimer(seconds: time)
        
        call.resolve([ "success": true ])
    }
    @objc func increaseSleepTime(_ call: CAPPluginCall) {
        if self.currentPlayer == nil {
            call.resolve()
            return
        }
        
        var time = self.remainingSleepDuration + parseSleepTime(millis: call.getString("time"))
        if time > self.currentPlayer!.getDuration() {
            time = self.currentPlayer!.getDuration()
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
        if currentPlayer == nil {
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
        if currentPlayer == nil {
            return
        }
        
        if self.remainingSleepDuration <= 0 {
            if currentSleepTimer != nil {
                currentSleepTimer!.invalidate()
            }
            self.notifyListeners("onSleepTimerEnded", data: [
                "value": currentPlayer!.getCurrentTime(),
            ])
            
            currentPlayer!.pause()
            return
        }
        
        remainingSleepDuration -= 1
        self.notifyListeners("onSleepTimerSet", data: [
            "value": self.remainingSleepDuration,
        ])
    }
    
    func sendMetadata() {
        if self.currentPlayer == nil {
            return
        }
        
        self.notifyListeners("onMetadata", data: [
            "duration": self.currentPlayer!.getDuration() * 1000,
            "currentTime": self.currentPlayer!.getCurrentTime() * 1000,
            "stateName": "unknown"
        ])
    }
    func sendPlaybackStatusUpdate(_ playing: Bool) {
        self.notifyListeners("onPlayingUpdate", data: [
            "value": playing
        ])
    }
}
