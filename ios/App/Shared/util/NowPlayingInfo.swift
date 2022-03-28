//
//  NowPlaying.swift
//  App
//
//  Created by Rasmus Krämer on 22.03.22.
//

import Foundation
import MediaPlayer

func getData(from url: URL, completion: @escaping (UIImage?) -> Void) {
    URLSession.shared.dataTask(with: url, completionHandler: {(data, response, error) in
        if let data = data {
            completion(UIImage(data:data))
        }
    }).resume()
}

class NowPlayingInfo {
    private static var nowPlayingInfo: [String: Any] = [:]
    private static var audiobook: Audiobook?
    
    public static func setAudiobook(audiobook: Audiobook) {
        self.audiobook = audiobook
        setMetadata(nil)
        
        if !shouldFetchCover() || audiobook.artworkUrl == nil {
            return
        }
        
        guard let url = URL(string: audiobook.artworkUrl!) else { return }
        getData(from: url) { [self] image in
            guard let downloadedImage = image else {
                return
            }
            let artwork = MPMediaItemArtwork.init(boundsSize: downloadedImage.size, requestHandler: { _ -> UIImage in
                return downloadedImage
            })
            
            self.setMetadata(artwork)
        }
    }
    public static func update(duration: Double, currentTime: Double, rate: Float) {
        nowPlayingInfo[MPMediaItemPropertyPlaybackDuration] = duration
        nowPlayingInfo[MPNowPlayingInfoPropertyElapsedPlaybackTime] = currentTime
        nowPlayingInfo[MPNowPlayingInfoPropertyPlaybackRate] = rate
        nowPlayingInfo[MPNowPlayingInfoPropertyDefaultPlaybackRate] = 1.0
            
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo
    }
    public static func reset() {
        audiobook = nil
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }
    
    private static func setMetadata(_ artwork: MPMediaItemArtwork?) {
        if self.audiobook == nil {
            return
        }
        
        if artwork != nil {
            nowPlayingInfo[MPMediaItemPropertyArtwork] = artwork
        } else if shouldFetchCover() {
            nowPlayingInfo[MPMediaItemPropertyArtwork] = nil
        }
        
        nowPlayingInfo[MPNowPlayingInfoPropertyExternalContentIdentifier] = audiobook!.streamId
        nowPlayingInfo[MPNowPlayingInfoPropertyAssetURL] = URL(string: audiobook!.playlistUrl)
        nowPlayingInfo[MPNowPlayingInfoPropertyIsLiveStream] = false
        nowPlayingInfo[MPNowPlayingInfoPropertyMediaType] = "hls"
        
        nowPlayingInfo[MPMediaItemPropertyTitle] = audiobook!.title
        nowPlayingInfo[MPMediaItemPropertyArtist] = audiobook!.author ?? "unknown"
        nowPlayingInfo[MPMediaItemPropertyAlbumTitle] = audiobook!.series
    }
    private static func shouldFetchCover() -> Bool {
        audiobook != nil && (nowPlayingInfo[MPNowPlayingInfoPropertyExternalContentIdentifier] as? String != audiobook!.streamId || nowPlayingInfo[MPMediaItemPropertyArtwork] == nil)
    }
}
