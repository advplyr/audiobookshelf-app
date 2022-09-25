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
    var title: String
    var author: String?
    var series: String?
    
    var coverUrl: URL? {
        if self.isLocal {
            guard let item = Database.shared.getLocalLibraryItem(byServerLibraryItemId: self.itemId) else { return nil }
            return item.coverUrl
        } else {
            guard let config = Store.serverConfig else { return nil }
            guard let url = URL(string: "\(config.address)/api/items/\(itemId)/cover?token=\(config.token)") else { return nil }
            return url
        }
    }
    
    var isLocal: Bool { id.starts(with: "play_local_") }
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
        self.setMetadata(artwork: nil, metadata: metadata)
        guard let url = metadata.coverUrl else { return }
        
        // For local images, "downloading" is occurring off disk, hence this code path works as expected
        ApiClient.getData(from: url) { [self] image in
            guard let downloadedImage = image else { return }
            let artwork = MPMediaItemArtwork.init(boundsSize: downloadedImage.size, requestHandler: { _ -> UIImage in
                return downloadedImage
            })
            self.setMetadata(artwork: artwork, metadata: metadata)
        }
    }
    public func update(duration: Double, currentTime: Double, rate: Float) {
        DispatchQueue.runOnMainQueue {
            self.nowPlayingInfo[MPMediaItemPropertyPlaybackDuration] = duration
            self.nowPlayingInfo[MPNowPlayingInfoPropertyElapsedPlaybackTime] = currentTime
            self.nowPlayingInfo[MPNowPlayingInfoPropertyPlaybackRate] = rate
            self.nowPlayingInfo[MPNowPlayingInfoPropertyDefaultPlaybackRate] = rate
            
            self.updateSystemNowPlaying(self.nowPlayingInfo)
        }
    }
    
    public func reset() {
        DispatchQueue.runOnMainQueue {
            self.nowPlayingInfo = [:]
            self.updateSystemNowPlaying(nil)
        }
    }
    
    private func setMetadata(artwork: MPMediaItemArtwork?, metadata: NowPlayingMetadata?) {
        guard let metadata = metadata else { return }
        
        DispatchQueue.runOnMainQueue {
            if artwork != nil {
                self.nowPlayingInfo[MPMediaItemPropertyArtwork] = artwork
            } else if self.shouldFetchCover(id: metadata.id) {
                self.nowPlayingInfo[MPMediaItemPropertyArtwork] = nil
            }
            
            self.nowPlayingInfo[MPNowPlayingInfoPropertyExternalContentIdentifier] = metadata.id
            self.nowPlayingInfo[MPNowPlayingInfoPropertyIsLiveStream] = false
            self.nowPlayingInfo[MPNowPlayingInfoPropertyMediaType] = MPNowPlayingInfoMediaType.audio.rawValue
            
            self.nowPlayingInfo[MPMediaItemPropertyTitle] = metadata.title
            self.nowPlayingInfo[MPMediaItemPropertyArtist] = metadata.author ?? "unknown"
            self.nowPlayingInfo[MPMediaItemPropertyAlbumTitle] = metadata.series
            
            self.updateSystemNowPlaying(self.nowPlayingInfo)
        }
    }
    
    private func shouldFetchCover(id: String) -> Bool {
        nowPlayingInfo[MPNowPlayingInfoPropertyExternalContentIdentifier] as? String != id || nowPlayingInfo[MPMediaItemPropertyArtwork] == nil
    }
    
    private func updateSystemNowPlaying(_ nowPlaying: [String: Any]?) {
        MPNowPlayingInfoCenter.default().nowPlayingInfo = self.nowPlayingInfo
    }
}
