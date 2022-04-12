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
    
    public static func startPlayback(session: PlaybackSession) {
        if player != nil {
            player?.destroy()
            player = nil
        }
    }
}
