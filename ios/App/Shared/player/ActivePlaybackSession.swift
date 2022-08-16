//
//  ActivePlaybackSession.swift
//  App
//
//  Created by Ron Heft on 8/16/22.
//

import Foundation
import RealmSwift

class ActivePlaybackSession {
    
    static let shared = ActivePlaybackSession()
    
    private let queue = DispatchQueue(label: "ABSActivePlaybackSession")
    private var _session: PlaybackSession?
    
    private init() {
        // Singleton
    }
    
    func startSession(_ session: ThreadSafeReference<PlaybackSession>) {
        queue.sync {
            _session = try? Realm().resolve(session)
        }
    }
    
    // This is a funky method, but it ensures the accessing thread gets a live reference to session properly resolved
    func get() -> PlaybackSession? {
        var activeSession: ThreadSafeReference<PlaybackSession>?
        queue.sync {
            let realm = try! Realm()
            guard let session = _session else { return }
            r
            activeSession = ThreadSafeReference(to: session)
        }
        guard let activeSession = activeSession else { return nil }
        return try? Realm().resolve(activeSession)
    }
    
}
