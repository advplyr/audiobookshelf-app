//
//  AbsDatabase.swift
//  App
//
//  Created by Rasmus Krämer on 11.04.22.
//

import Foundation
import Capacitor
import RealmSwift
import SwiftUI

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
        
        if id == nil {
            id = "\(address)@\(username)".toBase64()
        }
        
        let config = ServerConnectionConfig(id: id!, index: 1, name: name, address: address, userId: userId, username: username, token: token)
        
        Store.serverConfig = config
        call.resolve(convertServerConnectionConfigToJSON(config: config))
    }
    @objc func removeServerConnectionConfig(_ call: CAPPluginCall) {
        let id = call.getString("serverConnectionConfigId", "")
        Database.shared.deleteServerConnectionConfig(id: id)
        
        call.resolve()
    }
    @objc func logout(_ call: CAPPluginCall) {
        Store.serverConfig = nil
        call.resolve()
    }
    
    @objc func getDeviceData(_ call: CAPPluginCall) {
        let configs = Database.shared.getServerConnectionConfigs()
        let index = Database.shared.getLastActiveConfigIndex()
        let settings = Database.shared.getDeviceSettings()
        
        call.resolve([
            "serverConnectionConfigs": configs.map { config in convertServerConnectionConfigToJSON(config: config) },
            "lastServerConnectionConfigId": configs.first { config in config.index == index }?.id as Any,
            "deviceSettings": deviceSettingsToJSON(settings: settings)
        ])
    }
    
    @objc func getLocalLibraryItems(_ call: CAPPluginCall) {
        do {
            let items = Database.shared.getLocalLibraryItems()
            call.resolve([ "value": try items.asDictionaryArray()])
        } catch(let exception) {
            NSLog("error while readling local library items")
            debugPrint(exception)
            call.resolve()
        }
    }
    
    @objc func getLocalLibraryItem(_ call: CAPPluginCall) {
        do {
            let item = Database.shared.getLocalLibraryItem(localLibraryItemId: call.getString("id") ?? "")
            switch item {
                case .some(let foundItem):
                    call.resolve(try foundItem.asDictionary())
                default:
                    call.resolve()
            }
        } catch(let exception) {
            NSLog("error while readling local library items")
            debugPrint(exception)
            call.resolve()
        }
    }
    
    @objc func getLocalLibraryItemByLId(_ call: CAPPluginCall) {
        do {
            let item = Database.shared.getLocalLibraryItem(byServerLibraryItemId: call.getString("libraryItemId") ?? "")
            switch item {
                case .some(let foundItem):
                    call.resolve(try foundItem.asDictionary())
                default:
                    call.resolve()
            }
        } catch(let exception) {
            NSLog("error while readling local library items")
            debugPrint(exception)
            call.resolve()
        }
    }
    
    @objc func getLocalLibraryItemsInFolder(_ call: CAPPluginCall) {
        call.resolve([ "value": [] ])
    }
    
    @objc func getAllLocalMediaProgress(_ call: CAPPluginCall) {
        do {
            call.resolve([ "value": try Database.shared.getAllLocalMediaProgress().asDictionaryArray() ])
        } catch {
            NSLog("Error while loading local media progress")
            debugPrint(error)
            call.resolve(["value": []])
        }
    }
    
    @objc func removeLocalMediaProgress(_ call: CAPPluginCall) {
        let localMediaProgressId = call.getString("localMediaProgressId")
        guard let localMediaProgressId = localMediaProgressId else {
            call.reject("localMediaProgressId not specificed")
            return
        }
        Database.shared.removeLocalMediaProgress(localMediaProgressId: localMediaProgressId)
        call.resolve()
    }
    
    @objc func syncLocalMediaProgressWithServer(_ call: CAPPluginCall) {
        guard Store.serverConfig != nil else {
            call.reject("syncLocalMediaProgressWithServer not connected to server")
            return
        }
        ApiClient.syncMediaProgress { results in
            do {
                call.resolve(try results.asDictionary())
            } catch {
                call.reject("Failed to report synced media progress")
            }
        }
    }
    
    @objc func syncServerMediaProgressWithLocalMediaProgress(_ call: CAPPluginCall) {
        let serverMediaProgress = call.getJson("mediaProgress", type: MediaProgress.self)
        let localLibraryItemId = call.getString("localLibraryItemId")
        let localEpisodeId = call.getString("localEpisodeId")
        let localMediaProgressId = call.getString("localMediaProgressId")
        
        do {
            guard let localLibraryItemId = localLibraryItemId else {
                call.reject("localLibraryItemId not specified")
                return
            }
            guard let serverMediaProgress = serverMediaProgress else {
                call.reject("serverMediaProgress not specified")
                return
            }
            
            let localMediaProgress = LocalMediaProgress.fetchOrCreateLocalMediaProgress(localMediaProgressId: localMediaProgressId, localLibraryItemId: localLibraryItemId, localEpisodeId: localEpisodeId)
            guard var localMediaProgress = localMediaProgress else {
                call.reject("Local media progress not found or created")
                return
            }
            localMediaProgress.updateFromServerMediaProgress(serverMediaProgress)
            
            NSLog("syncServerMediaProgressWithLocalMediaProgress: Saving local media progress")
            Database.shared.saveLocalMediaProgress(localMediaProgress)
            call.resolve(try localMediaProgress.asDictionary())
        } catch {
            call.reject("Failed to sync media progress")
            debugPrint(error)
        }
    }
    
    @objc func updateLocalMediaProgressFinished(_ call: CAPPluginCall) {
        let localLibraryItemId = call.getString("localLibraryItemId")
        let localEpisodeId = call.getString("localEpisodeId")
        let localMediaProgressId = call.getString("localMediaProgressId")
        let isFinished = call.getBool("isFinished", false)
        
        NSLog("updateLocalMediaProgressFinished \(localMediaProgressId ?? "Unknown") | Is Finished: \(isFinished)")
        
        let localMediaProgress = LocalMediaProgress.fetchOrCreateLocalMediaProgress(localMediaProgressId: localMediaProgressId, localLibraryItemId: localLibraryItemId, localEpisodeId: localEpisodeId)
        guard var localMediaProgress = localMediaProgress else {
            call.resolve(["error": "Library Item not found"])
            return
        }

        // Update finished status
        localMediaProgress.updateIsFinished(isFinished)
        Database.shared.saveLocalMediaProgress(localMediaProgress)
        
        // Build API response
        let progressDictionary = try? localMediaProgress.asDictionary()
        var response: [String: Any] = ["local": true, "server": false, "localMediaProgress": progressDictionary ?? ""]
        
        // Send update to the server if logged in
        let hasLinkedServer = localMediaProgress.serverConnectionConfigId != nil
        let loggedIntoServer = Store.serverConfig?.id == localMediaProgress.serverConnectionConfigId
        if hasLinkedServer && loggedIntoServer {
            response["server"] = true
            let payload = ["isFinished": isFinished]
            ApiClient.updateMediaProgress(libraryItemId: localMediaProgress.libraryItemId!, episodeId: localEpisodeId, payload: payload) {
                call.resolve(response)
            }
        } else {
            call.resolve(response)
        }
    }
    
    @objc func updateDeviceSettings(_ call: CAPPluginCall) {
        let disableAutoRewind = call.getBool("disableAutoRewind") ?? false
        let enableAltView = call.getBool("enableAltView") ?? false
        let jumpBackwardsTime = call.getInt("jumpBackwardsTime") ?? 10
        let jumpForwardTime = call.getInt("jumpForwardTime") ?? 10
        let settings = DeviceSettings(disableAutoRewind: disableAutoRewind, enableAltView: enableAltView, jumpBackwardsTime: jumpBackwardsTime, jumpForwardTime: jumpForwardTime)
        
        Database.shared.setDeviceSettings(deviceSettings: settings)
        
//        call.resolve([ "value": [] ])
        getDeviceData(call)
    }
}
