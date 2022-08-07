//
//  Store.swift
//  App
//
//  Created by Rasmus Krämer on 11.04.22.
//

import Foundation
import RealmSwift

class Store {
    private static var _serverConfig: ServerConnectionConfig?
    public static var serverConfig: ServerConnectionConfig? {
        get {
            return _serverConfig
        }
        set(updated) {
            if updated != nil {
                Database.shared.setServerConnectionConfig(config: updated!)
            } else {
                Database.shared.setLastActiveConfigIndexToNil()
            }
            
           _serverConfig = updated
        }
    }
}
