//
//  PlayerEvents.swift
//  App
//
//  Created by Rasmus Krämer on 14.04.22.
//

import Foundation

enum PlayerEvents: String {
    case update = "com.bookshelf.app.player.update"
    case closed = "com.bookshelf.app.player.closed"
    case sleepSet = "com.bookshelf.app.player.sleep.set"
    case sleepEnded = "com.bookshelf.app.player.sleep.ended"
}
