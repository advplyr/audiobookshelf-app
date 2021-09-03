import Foundation
import Capacitor
import AVKit

struct Audiobook {
    var title = "No Title"
    var author = "Unknown"
    var playWhenReady = false
    var startTime = 0
    var cover = ""
    var duration = 0
    var series = ""
    var playlistUrl = ""
    var token = ""
}

@objc(MyNativeAudio)
public class MyNativeAudio: CAPPlugin {
    var avPlayer: AVPlayer!
    var currentCall: CAPPluginCall?
    var audioPlayer: AVPlayer!
    var audiobook: Audiobook?

    override public func load() {
        NotificationCenter.default.addObserver(self, selector: #selector(stop),
                                               name:Notification.Name.AVPlayerItemDidPlayToEndTime, object: nil)
    }

    @objc func initPlayer(_ call: CAPPluginCall)  {
       audiobook = Audiobook(
            title: call.getString("title") ?? "No Title",
            author: call.getString("author") ?? "Unknown",
            playWhenReady: call.getBool("playWhenReady", false),
            startTime: call.getInt("startTime") ?? 0,
            cover: call.getString("cover") ?? "",
            duration: call.getInt("duration") ?? 0,
            series: call.getString("series") ?? "",
            playlistUrl: call.getString("playlistUrl") ?? "",
            token: call.getString("token") ?? ""
        )
        if (audiobook == nil) {
            return
        }

        let headers: [String:String] = [
            "Authorization": "Bearer \(audiobook!.token)"
        ]
        let url = URL(string:audiobook!.playlistUrl)
        let asset = AVURLAsset(
            url: url!,
            options: ["AVURLAssetHTTPHeaderFieldsKey": headers]
        )
        let playerItem = AVPlayerItem(asset: asset)
        self.audioPlayer = AVPlayer(playerItem: playerItem)
//        self.audioPlayer = AVPlayer(url: url)

        self.audioPlayer.play()
    }
    
    @objc func seekForward10() {
        let duration = self.audioPlayer.currentItem?.duration.seconds ?? 0
        let currentTime = self.audioPlayer.currentItem?.currentTime().seconds ?? 0
        var destinationTime = currentTime + 10
        if (destinationTime > duration) { destinationTime = duration }
        
        let time = CMTime(seconds:destinationTime,preferredTimescale: 1000)
        self.audioPlayer.seek(to: time)
    }
    
    @objc func seekBackward10() {
        let currentTime = self.audioPlayer.currentItem?.currentTime().seconds ?? 0
        var destinationTime = currentTime - 10
        if (destinationTime < 0) { destinationTime = 0 }
        
        let time = CMTime(seconds:destinationTime,preferredTimescale: 1000)
        self.audioPlayer.seek(to: time)
    }
    
    @objc func seekPlayer(_ call: CAPPluginCall) {
        var seekTime = call.getInt("timeMs") ?? 0
        seekTime /= 1000
        if (seekTime < 0) { seekTime = 0 }
        
        let time = CMTime(seconds:Double(seekTime),preferredTimescale: 1000)
        self.audioPlayer.seek(to: time)
    }
    
    @objc func pausePlayer() {
        self.audioPlayer.pause()
    }
    
    @objc func playPlayer() {
        self.audioPlayer.play()
    }
    
    @objc func terminateStream() {
        self.audioPlayer.pause()
    }
    
    @objc func stop() {
        if let call = currentCall {
            currentCall = nil;
            call.resolve([ "result": true])
        }
    }
}
