//
//  AbsDatabase.swift
//  App
//
//  Created by Rasmus Krämer on 11.04.22.
//

import Foundation
import Capacitor
import RealmSwift

extension String {

    func fromBase64() -> String? {
        guard let data = Data(base64Encoded: self) else {
            return nil
        }

        return String(data: data, encoding: .utf8)
    }

    func toBase64() -> String {
        return Data(self.utf8).base64EncodedString()
    }

}

@objc(AbsDatabase)
public class AbsDatabase: CAPPlugin {
    @objc func setCurrentServerConnectionConfig(_ call: CAPPluginCall) {
        var id = call.getString("id")
        let address = call.getString("address", "")
        let userId = call.getString("userId", "")
        let username = call.getString("username", "")
        let token = call.getString("token", "")
        
        let name = "\(address) (\(username))"
        let config = ServerConnectionConfig()
        
        if id == nil {
            id = "\(address)@\(username)".toBase64()
        }
        
        config.id = id!
        config.name = name
        config.address = address
        config.userId = userId
        config.username = username
        config.token = token
        
        Store.serverConfig = config
        call.resolve(convertServerConnectionConfigToJSON(config: config))
    }
    @objc func logout(_ call: CAPPluginCall) {
        Store.serverConfig = nil
        call.resolve()
    }
    
    @objc func getDeviceData(_ call: CAPPluginCall) {
        let configs = Database.getServerConnectionConfigs()
        let index = Database.getLastActiveConfigIndex()
        
        call.resolve([
            "serverConnectionConfigs": configs.map { config in convertServerConnectionConfigToJSON(config: config) },
            "lastServerConnectionConfigId": configs.first { config in config.index == index }?.id,
            // Luckily this isn't implemented yet
            // "currentLocalPlaybackSession": nil,
        ])
    }
    
    @objc func getLocalLibraryItems(_ call: CAPPluginCall) {
        call.resolve([ "value": [] ])
    }
    @objc func getLocalLibraryItem(_ call: CAPPluginCall) {
        call.resolve()
    }
    @objc func getLocalLibraryItemByLLId(_ call: CAPPluginCall) {
        call.resolve()
    }
    @objc func getLocalLibraryItemsInFolder(_ call: CAPPluginCall) {
        call.resolve([ "value": [] ])
    }
}
