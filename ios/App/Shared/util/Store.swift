//
//  Store.swift
//  App
//
//  Created by Rasmus Krämer on 11.04.22.
//

import Foundation
import RealmSwift

class Store {
    @ThreadSafe private static var _serverConfig: ServerConnectionConfig?
    public static var serverConfig: ServerConnectionConfig? {
        get {
            return _serverConfig
        }
        set(updated) {
            if updated != nil {
                Database.setServerConnectionConfig(config: updated!)
            } else {
                Database.setLastActiveConfigIndexToNil()
            }
            
            Database.realmQueue.sync {
                _serverConfig = updated
            }
        }
    }
}
