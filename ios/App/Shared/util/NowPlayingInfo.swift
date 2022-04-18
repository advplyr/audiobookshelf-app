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

struct NowPlayingMetadata {
    var id: String
    var itemId: String
    var artworkUrl: String?
    var title: String
    var author: String?
    var series: String?
}

class NowPlayingInfo {
    private static var nowPlayingInfo: [String: Any] = [:]
    
    public static func setSessionMetadata(metadata: NowPlayingMetadata) {
        setMetadata(artwork: nil, metadata: metadata)
        
        /*
        if !shouldFetchCover(id: metadata.id) || metadata.artworkUrl == nil {
            return
        }
         */
        
        guard let url = URL(string: "\(Store.serverConfig!.address)/api/items/\(metadata.itemId)/cover?token=\(Store.serverConfig!.token)") else {
            return
        }
        
        getData(from: url) { [self] image in
            guard let downloadedImage = image else {
                return
            }
            let artwork = MPMediaItemArtwork.init(boundsSize: downloadedImage.size, requestHandler: { _ -> UIImage in
                return downloadedImage
            })
            
            self.setMetadata(artwork: artwork, metadata: metadata)
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
        nowPlayingInfo = [:]
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }
    
    private static func setMetadata(artwork: MPMediaItemArtwork?, metadata: NowPlayingMetadata?) {
        if metadata == nil {
            return
        }
        
        if artwork != nil {
            nowPlayingInfo[MPMediaItemPropertyArtwork] = artwork
        } else if shouldFetchCover(id: metadata!.id) {
            nowPlayingInfo[MPMediaItemPropertyArtwork] = nil
        }
        
        nowPlayingInfo[MPNowPlayingInfoPropertyExternalContentIdentifier] = metadata!.id
        nowPlayingInfo[MPNowPlayingInfoPropertyIsLiveStream] = false
        nowPlayingInfo[MPNowPlayingInfoPropertyMediaType] = "hls"
        
        nowPlayingInfo[MPMediaItemPropertyTitle] = metadata!.title
        nowPlayingInfo[MPMediaItemPropertyArtist] = metadata!.author ?? "unknown"
        nowPlayingInfo[MPMediaItemPropertyAlbumTitle] = metadata!.series
    }
    private static func shouldFetchCover(id: String) -> Bool {
        nowPlayingInfo[MPNowPlayingInfoPropertyExternalContentIdentifier] as? String != id || nowPlayingInfo[MPMediaItemPropertyArtwork] == nil
    }
}
