//
//  PlayerHandler.swift
//  App
//
//  Created by Rasmus Krämer on 11.04.22.
//

import Foundation

class PlayerHandler {
    private static var player: AudioPlayer?
    private static var session: PlaybackSession?
    
    public static func startPlayback(session: PlaybackSession, playWhenReady: Bool) {
        if player != nil {
            player?.destroy()
            player = nil
        }
        
        NowPlayingInfo.setSessionMetadata(metadata: NowPlayingMetadata(id: session.id, itemId: session.libraryItemId!, artworkUrl: session.coverPath, title: session.displayTitle ?? "Unknown title", author: session.displayAuthor, series: nil))
        self.session = session
        player = AudioPlayer(playbackSession: session, playWhenReady: playWhenReady)
    }
}
