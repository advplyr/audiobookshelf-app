//
//  Store.swift
//  App
//
//  Created by Rasmus Krämer on 11.04.22.
//

import Foundation
import RealmSwift

class Store {
    public static var serverConfig: ServerConnectionConfig {
        get {
            return Database.getServerConnectionConfig()
        }
        set(updated) {
            Database.setServerConnectionConfig(config: updated)
        }
    }
}
