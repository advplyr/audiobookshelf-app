import Foundation
import Capacitor
import MediaPlayer
import AVKit

extension UIImage {
    func imageWith(newSize: CGSize) -> UIImage {
        let renderer = UIGraphicsImageRenderer(size: newSize)
        let image = renderer.image { _ in
            self.draw(in: CGRect.init(origin: CGPoint.zero, size: newSize))
        }
        return image.withRenderingMode(self.renderingMode)
    }
}

struct Audiobook {
    var streamId = ""
    var audiobookId = ""
    var title = "No Title"
    var author = "Unknown"
    var playWhenReady = false
    var startTime = 0.0
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
    
    enum PlayerState {
        case stopped
        case playing
        case paused
    }
    
    private var playerState: PlayerState = .stopped
    
    // Key-value observing context
    private var playerItemContext = 0

    override public func load() {
        NSLog("Load MyNativeAudio")
        NotificationCenter.default.addObserver(self, selector: #selector(stop),
                                               name:Notification.Name.AVPlayerItemDidPlayToEndTime, object: nil)
        NotificationCenter.default.addObserver(self,
                                               selector: #selector(appDidEnterBackground),
                                               name: UIApplication.didEnterBackgroundNotification, object: nil)
        
        NotificationCenter.default.addObserver(self,
                                               selector: #selector(appWillEnterForeground),
                                               name: UIApplication.willEnterForegroundNotification, object: nil)
        
        setupRemoteTransportControls()
    }

    @objc func initPlayer(_ call: CAPPluginCall)  {
        NSLog("Init Player")
       audiobook = Audiobook(
            streamId: call.getString("id") ?? "",
            audiobookId: call.getString("audiobookId") ?? "",
            title: call.getString("title") ?? "No Title",
            author: call.getString("author") ?? "Unknown",
            playWhenReady: call.getBool("playWhenReady", false),
            startTime: Double(call.getString("startTime") ?? "0") ?? 0.0,
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
        
        print("Playing audiobook url \(String(describing: url))")
        
        // For play in background
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio, options: [.allowAirPlay])
            NSLog("[TEST] Playback OK")
            try AVAudioSession.sharedInstance().setActive(true)
            NSLog("[TEST] Session is Active")
        } catch {
            NSLog("[TEST] Failed to set BG Data")
            print(error)
        }
        
        let playerItem = AVPlayerItem(asset: asset)
        
        // Register as an observer of the player item's status property
        playerItem.addObserver(self,
                               forKeyPath: #keyPath(AVPlayerItem.status),
                               options: [.old, .new],
                               context: &playerItemContext)

        self.audioPlayer = AVPlayer(playerItem: playerItem)
        
        let startTime = CMTime(seconds: (audiobook?.startTime ?? 0.0) / 1000, preferredTimescale: 1000)
        self.audioPlayer.seek(to: startTime)
        
        let time = self.audioPlayer.currentItem?.currentTime()
        print("Audio Player Initialized \(String(describing: time))")
        
        call.resolve(["success": true])
    }
    
    @objc func seekForward(_ call: CAPPluginCall) {
        let amount = (Double(call.getString("amount", "0")) ?? 0) / 1000
        
        let duration = self.audioPlayer.currentItem?.duration.seconds ?? 0
        let currentTime = self.audioPlayer.currentItem?.currentTime().seconds ?? 0
        var destinationTime = currentTime + amount
        if (destinationTime > duration) { destinationTime = duration }
        
        let time = CMTime(seconds:destinationTime,preferredTimescale: 1000)
        self.audioPlayer.seek(to: time)
        call.resolve()
    }
    
    @objc func seekBackward(_ call: CAPPluginCall) {
        let amount = (Double(call.getString("amount", "0")) ?? 0) / 1000
        
        let currentTime = self.audioPlayer.currentItem?.currentTime().seconds ?? 0
        var destinationTime = currentTime - amount
        if (destinationTime < 0) { destinationTime = 0 }
        
        let time = CMTime(seconds:destinationTime,preferredTimescale: 1000)
        self.audioPlayer.seek(to: time)
        call.resolve()
    }
    
    @objc func seekPlayer(_ call: CAPPluginCall) {
        var seekTime = (Int(call.getString("timeMs", "0")) ?? 0) / 1000
        NSLog("Seek Player \(seekTime)")
        
        if (seekTime < 0) { seekTime = 0 }
        
        let time = CMTime(seconds:Double(seekTime),preferredTimescale: 1000)
        self.audioPlayer.seek(to: time)
        call.resolve()
    }
    
    @objc func pausePlayer(_ call: CAPPluginCall) {
        pause()
        call.resolve()
    }
    
    @objc func playPlayer(_ call: CAPPluginCall) {
        play()
        call.resolve()
    }
    
    @objc func terminateStream(_ call: CAPPluginCall) {
        pause()
        call.resolve()
    }
    
    @objc func stop() {
        if let call = currentCall {
            currentCall = nil;
            call.resolve([ "result": true])
        }
    }
    
    @objc func getCurrentTime(_ call: CAPPluginCall) {
        let currTime = self.audioPlayer.currentItem?.currentTime().seconds ?? 0
        let buffTime = self.audioPlayer.currentItem?.currentTime().seconds ?? 0
        NSLog("AVPlayer getCurrentTime \(currTime)")
        call.resolve([ "value": currTime * 1000, "bufferedTime": buffTime * 1000 ])
    }
    
    @objc func getStreamSyncData(_ call: CAPPluginCall) {
        let streamId = audiobook?.streamId ?? ""
        call.resolve([ "isPlaying": false, "lastPauseTime": 0, "id": streamId ])
    }
    
    @objc func setPlaybackSpeed(_ call: CAPPluginCall) {
        let speed = call.getFloat("speed") ?? 0
        NSLog("[TEST] Set Playback Speed \(speed)")
        audioPlayer.rate = speed
        
        call.resolve()
    }

    func play() {
        audioPlayer.play()
        self.notifyListeners("onPlayingUpdate", data: [
            "value": true
        ])
        sendMetadata()
        
        playerState = .playing
        updateNowPlaying()
    }
    
    func pause() {
        audioPlayer.pause()
        self.notifyListeners("onPlayingUpdate", data: [
            "value": false
        ])
        sendMetadata()
        
        playerState = .paused
    }
    
    func currentTime() -> Double {
        return self.audioPlayer.currentItem?.currentTime().seconds ?? 0
    }
    
    func duration() -> Double {
        return self.audioPlayer.currentItem?.duration.seconds ?? 0
    }
    
    func playbackRate() -> Float {
        return self.audioPlayer.rate
    }
    
    func sendMetadata() {
        let currTime = self.audioPlayer.currentItem?.currentTime().seconds ?? 0
        let duration = self.audioPlayer.currentItem?.duration.seconds ?? 0
        self.notifyListeners("onMetadata", data: [
            "duration": duration * 1000,
            "currentTime": currTime * 1000,
            "stateName": "unknown"
        ])
    }
    
    
    public override func observeValue(forKeyPath keyPath: String?,
                               of object: Any?,
                               change: [NSKeyValueChangeKey : Any]?,
                               context: UnsafeMutableRawPointer?) {

        // Only handle observations for the playerItemContext
        guard context == &playerItemContext else {
            super.observeValue(forKeyPath: keyPath,
                               of: object,
                               change: change,
                               context: context)
            return
        }

        if keyPath == #keyPath(AVPlayerItem.status) {
            let status: AVPlayerItem.Status
            if let statusNumber = change?[.newKey] as? NSNumber {
                status = AVPlayerItem.Status(rawValue: statusNumber.intValue)!
                print("AVPlayer Status Change \(String(status.rawValue))")
            } else {
                status = .unknown
            }

            // Switch over status value
            switch status {
            case .readyToPlay:
                // Player item is ready to play.
                NSLog("AVPlayer ready to play")
                
                setNowPlayingMetadata()
                sendMetadata()
                
                if (audiobook?.playWhenReady == true) {
                    NSLog("AVPlayer playWhenReady == true")
                    play()
                }
                break
            case .failed:
                // Player item failed. See error.
                break
            case .unknown:
                // Player item is not yet ready
                break
            @unknown default:
                break
            }
        }
    }
    
    @objc func appDidEnterBackground() {
        updateNowPlaying()
        NSLog("[TEST] App Enter Backround")
    }

    @objc func appWillEnterForeground() {
        
        NSLog("[TEST] App Will Enter Foreground")
    }
    
    func setupRemoteTransportControls() {
        // Get the shared MPRemoteCommandCenter
        let commandCenter = MPRemoteCommandCenter.shared()
        
        commandCenter.playCommand.isEnabled = true
        commandCenter.playCommand.addTarget { [unowned self] event in
            play()
            return .success
        }
        commandCenter.pauseCommand.isEnabled = true
        commandCenter.pauseCommand.addTarget { [unowned self] event in
            pause()
            return .success
        }
        
        commandCenter.skipForwardCommand.isEnabled = true
        commandCenter.skipForwardCommand.preferredIntervals = [30]
        commandCenter.skipForwardCommand.addTarget { [unowned self] event in
            guard let command = event.command as? MPSkipIntervalCommand else {
                return .noSuchContent
            }
            
            self.audioPlayer.seek(to: CMTime(seconds: currentTime() + command.preferredIntervals[0].doubleValue, preferredTimescale: 1000))
            updateNowPlaying()
            
            return .success
        }
        commandCenter.skipBackwardCommand.isEnabled = true
        commandCenter.skipBackwardCommand.preferredIntervals = [30]
        commandCenter.skipBackwardCommand.addTarget { [unowned self] event in
            guard let command = event.command as? MPSkipIntervalCommand else {
                return .noSuchContent
            }
            
            self.audioPlayer.seek(to: CMTime(seconds: currentTime() - command.preferredIntervals[0].doubleValue, preferredTimescale: 1000))
            updateNowPlaying()
            
            return .success
        }
    }
    
    func getData(from url: URL, completion: @escaping (UIImage?) -> Void) {
         URLSession.shared.dataTask(with: url, completionHandler: {(data, response, error) in
             if let data = data {
                 completion(UIImage(data:data))
             }
         })
         .resume()
     }
    func setNowPlayingMetadata() {
        if (audiobook?.cover != nil) {
            guard let url = URL(string: audiobook!.cover) else { return }
            getData(from: url) { [weak self] image in
                guard let self = self,
                      let downloadedImage = image else {
                          return
                      }
                let artwork = MPMediaItemArtwork.init(boundsSize: downloadedImage.size, requestHandler: { _ -> UIImage in
                    return downloadedImage
                })
                
                self.setNowPlayingMetadataWithImage(artwork)
            }
        } else {
            setNowPlayingMetadataWithImage(nil)
        }
    }
    func setNowPlayingMetadataWithImage(_ artwork: MPMediaItemArtwork?) {
        let nowPlayingInfoCenter = MPNowPlayingInfoCenter.default()
        var nowPlayingInfo = [String: Any]()
        
        NSLog("%@", "**** Set track metadata: title \(audiobook?.title ?? "")")
        nowPlayingInfo[MPNowPlayingInfoPropertyAssetURL] = audiobook?.playlistUrl != nil ? URL(string: audiobook!.playlistUrl) : nil
        nowPlayingInfo[MPNowPlayingInfoPropertyMediaType] = "hls"
        nowPlayingInfo[MPNowPlayingInfoPropertyIsLiveStream] = false
        nowPlayingInfo[MPMediaItemPropertyTitle] = audiobook?.title ?? ""
        nowPlayingInfo[MPMediaItemPropertyArtist] = audiobook?.author ?? ""
        
        if (artwork != nil) {
            nowPlayingInfo[MPMediaItemPropertyArtwork] = artwork
        }
        
        nowPlayingInfoCenter.nowPlayingInfo = nowPlayingInfo
    }
    
    func updateNowPlaying() {
        
        if (playerState != .playing) {
            NSLog("[TEST] Not current playing so not updating now playing info")
            return
        }
        
        let nowPlayingInfoCenter = MPNowPlayingInfoCenter.default()
        var nowPlayingInfo = nowPlayingInfoCenter.nowPlayingInfo ?? [String: Any]()
        
        NSLog("%@", "**** Set playback info: rate \(playbackRate()), position \(currentTime()), duration \(duration())")
        nowPlayingInfo[MPMediaItemPropertyPlaybackDuration] = duration()
        nowPlayingInfo[MPNowPlayingInfoPropertyElapsedPlaybackTime] = currentTime()
        nowPlayingInfo[MPNowPlayingInfoPropertyPlaybackRate] = playbackRate()
        nowPlayingInfo[MPNowPlayingInfoPropertyDefaultPlaybackRate] = 1.0
        nowPlayingInfoCenter.nowPlayingInfo = nowPlayingInfo
    }
}
