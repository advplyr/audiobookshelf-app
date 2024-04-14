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
    var isLocal: Bool
    
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
        guard let url = metadata.coverUrl else { return }
        // For local images, "downloading" is occurring off disk, hence this code path works as expected
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
    public func update(duration: Double, currentTime: Double, rate: Float, defaultRate: Float, chapterName: String? = nil, chapterNumber: Int? = nil, chapterCount: Int? = nil) {
        // Update on the main to prevent access collisions
        DispatchQueue.main.async { [weak self] in
            if let self = self {
                self.nowPlayingInfo[MPMediaItemPropertyPlaybackDuration] = duration
                self.nowPlayingInfo[MPNowPlayingInfoPropertyElapsedPlaybackTime] = currentTime
                self.nowPlayingInfo[MPNowPlayingInfoPropertyPlaybackRate] = rate
                self.nowPlayingInfo[MPNowPlayingInfoPropertyDefaultPlaybackRate] = defaultRate
                    
                
                if let chapterName = chapterName, let chapterNumber = chapterNumber, let chapterCount = chapterCount {
                    self.nowPlayingInfo[MPMediaItemPropertyTitle] = chapterName
                    self.nowPlayingInfo[MPNowPlayingInfoPropertyChapterNumber] = chapterNumber
                    self.nowPlayingInfo[MPNowPlayingInfoPropertyChapterCount] = chapterCount
                } else {
                    // Set the title back to the book title
                    self.nowPlayingInfo[MPMediaItemPropertyTitle] = self.nowPlayingInfo[MPMediaItemPropertyAlbumTitle]
                    self.nowPlayingInfo[MPNowPlayingInfoPropertyChapterNumber] = nil
                    self.nowPlayingInfo[MPNowPlayingInfoPropertyChapterCount] = nil
                }
                
                MPNowPlayingInfoCenter.default().nowPlayingInfo = self.nowPlayingInfo
            }
        }
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
        nowPlayingInfo[MPMediaItemPropertyAlbumTitle] = metadata!.title
    }
    
    private func shouldFetchCover(id: String) -> Bool {
        nowPlayingInfo[MPNowPlayingInfoPropertyExternalContentIdentifier] as? String != id || nowPlayingInfo[MPMediaItemPropertyArtwork] == nil
    }
}
