//
//  NowPlaying.swift
//  App
//
//  Created by Rasmus Krämer on 22.03.22.
//

import Foundation
import MediaPlayer

struct NowPlayingMetadata {
    var id: String
    var itemId: String
    var artworkUrl: String?
    var title: String
    var author: String?
    var series: String?
    var coverUrl: URL? {
        guard let url = URL(string: "\(Store.serverConfig!.address)/api/items/\(itemId)/cover?token=\(Store.serverConfig!.token)") else { return nil }
        return url
    }
}

class NowPlayingInfo {
    static var shared = {
        return NowPlayingInfo()
    }()
    
    private var nowPlayingInfo: [String: Any]
    private init() {
        self.nowPlayingInfo = [:]
    }
    
    public func setSessionMetadata(metadata: NowPlayingMetadata) {
        setMetadata(artwork: nil, metadata: metadata)
        
        let isLocalItem = metadata.itemId.starts(with: "local_")
        if isLocalItem {
            guard let artworkUrl = metadata.artworkUrl else { return }
            let coverImage = UIImage(contentsOfFile: artworkUrl)
            guard let coverImage = coverImage else { return }
            let artwork = MPMediaItemArtwork(boundsSize: coverImage.size) { _ -> UIImage in
                return coverImage
            }
            self.setMetadata(artwork: artwork, metadata: metadata)
        } else {
            guard let url = metadata.coverUrl else { return }
            ApiClient.getData(from: url) { [self] image in
                guard let downloadedImage = image else {
                    return
                }
                let artwork = MPMediaItemArtwork.init(boundsSize: downloadedImage.size, requestHandler: { _ -> UIImage in
                    return downloadedImage
                })
                
                self.setMetadata(artwork: artwork, metadata: metadata)
            }
        }
    }
    public func update(duration: Double, currentTime: Double, rate: Float) {
        nowPlayingInfo[MPMediaItemPropertyPlaybackDuration] = duration
        nowPlayingInfo[MPNowPlayingInfoPropertyElapsedPlaybackTime] = currentTime
        nowPlayingInfo[MPNowPlayingInfoPropertyPlaybackRate] = rate
        nowPlayingInfo[MPNowPlayingInfoPropertyDefaultPlaybackRate] = 1.0
            
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo
    }
    
    public func reset() {
        nowPlayingInfo = [:]
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }
    
    private func setMetadata(artwork: MPMediaItemArtwork?, metadata: NowPlayingMetadata?) {
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
    
    private func shouldFetchCover(id: String) -> Bool {
        nowPlayingInfo[MPNowPlayingInfoPropertyExternalContentIdentifier] as? String != id || nowPlayingInfo[MPMediaItemPropertyArtwork] == nil
    }
}
