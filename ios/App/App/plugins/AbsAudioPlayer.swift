//
//  AbsAudioPlayer.swift
//  App
//
//  Created by Rasmus Krämer on 13.04.22.
//

import Foundation
import Capacitor

@objc(AbsAudioPlayer)
public class AbsAudioPlayer: CAPPlugin {
    @objc func prepareLibraryItem(_ call: CAPPluginCall) {
        let libraryItemId = call.getString("libraryItemId")
        let episodeId = call.getString("episodeId")
        let playWhenReady = call.getBool("playWhenReady", true)
        
        if libraryItemId == nil {
            NSLog("provide library item id")
            return call.resolve()
        }
        if libraryItemId!.starts(with: "local") {
            NSLog("local items are not implemnted")
            return call.resolve()
        }
        
        ApiClient.startPlaybackSession(libraryItemId: libraryItemId!, episodeId: episodeId) { session in
            PlayerHandler.startPlayback(session: session, playWhenReady: playWhenReady)
            do {
                call.resolve(try session.asDictionary())
            } catch(let exception) {
                NSLog("failed to convert session to json")
                debugPrint(exception)
                
                call.resolve([:])
            }
        }
    }
}
