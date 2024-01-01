//
//  PlayerSettings.swift
//  App
//
//  Created by Ron Heft on 8/18/22.
//

import Foundation
import RealmSwift

class PlayerSettings: Object {
    // The webapp has a persisted setting for playback speed, but it's not always available to the native code
    // Lets track it natively as well, so we never have a situation where the UI and native player are out of sync
    @Persisted var playbackRate: Float = 1.0
    @Persisted var chapterTrack: Bool = true

    
    // Singleton pattern for Realm objects
    static func main() -> PlayerSettings {
        let realm = try! Realm()
        
        if let settings = realm.objects(PlayerSettings.self).last {
            return settings
        }
        
        let settings = PlayerSettings()
        try! realm.write {
            realm.add(settings)
        }
        return settings
    }
}
