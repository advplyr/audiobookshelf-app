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
    private let logger = AppLogger(category: "AbsDatabase")

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

        let config = ServerConnectionConfig()
        config.id = id ?? ""
        config.index = 0
        config.name = name
        config.address = address
        config.userId = userId
        config.username = username
        config.token = token

        Store.serverConfig = config
        let savedConfig = Store.serverConfig // Fetch the latest value
        call.resolve(convertServerConnectionConfigToJSON(config: savedConfig!))
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
            logger.error("error while readling local library items")
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
            logger.error("error while readling local library items")
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
            logger.error("error while readling local library items")
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
            logger.error("Error while loading local media progress")
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
        try? Database.shared.removeLocalMediaProgress(localMediaProgressId: localMediaProgressId)
        call.resolve()
    }

    @objc func syncLocalSessionsWithServer(_ call: CAPPluginCall) {
        let isFirstSync = call.getBool("isFirstSync", false)
        logger.log("syncLocalSessionsWithServer: Starting (First sync: \(isFirstSync))")
        guard Store.serverConfig != nil else {
            call.reject("syncLocalSessionsWithServer not connected to server")
            return call.resolve()
        }

        Task {
            await ApiClient.syncLocalSessionsWithServer(isFirstSync: isFirstSync)
            call.resolve()
        }
    }

    @objc func syncServerMediaProgressWithLocalMediaProgress(_ call: CAPPluginCall) {
        let serverMediaProgress = call.getJson("mediaProgress", type: MediaProgress.self)
        let localLibraryItemId = call.getString("localLibraryItemId")
        let localEpisodeId = call.getString("localEpisodeId")
        let localMediaProgressId = call.getString("localMediaProgressId")

        do {
            guard let serverMediaProgress = serverMediaProgress else {
                return call.reject("serverMediaProgress not specified")
            }
            guard localLibraryItemId != nil || localMediaProgressId != nil else {
                return call.reject("localLibraryItemId or localMediaProgressId must be specified")
            }

            let localMediaProgress = try LocalMediaProgress.fetchOrCreateLocalMediaProgress(localMediaProgressId: localMediaProgressId, localLibraryItemId: localLibraryItemId, localEpisodeId: localEpisodeId)
            guard let localMediaProgress = localMediaProgress else {
                call.reject("Local media progress not found or created")
                return
            }

            logger.log("syncServerMediaProgressWithLocalMediaProgress: Saving local media progress")
            try localMediaProgress.updateFromServerMediaProgress(serverMediaProgress)

            call.resolve(try localMediaProgress.asDictionary())
        } catch {
            call.reject("Failed to sync media progress")
            debugPrint(error)
        }
    }

    @objc func updateLocalMediaProgressFinished(_ call: CAPPluginCall) {
        let localLibraryItemId = call.getString("localLibraryItemId")
        let localEpisodeId = call.getString("localEpisodeId")
        let isFinished = call.getBool("isFinished", false)

        var localMediaProgressId = localLibraryItemId ?? ""
        if localEpisodeId != nil {
            localMediaProgressId += "-\(localEpisodeId ?? "")"
        }

        logger.log("updateLocalMediaProgressFinished \(localMediaProgressId) | Is Finished: \(isFinished)")

        do {
            let localMediaProgress = try LocalMediaProgress.fetchOrCreateLocalMediaProgress(localMediaProgressId: localMediaProgressId, localLibraryItemId: localLibraryItemId, localEpisodeId: localEpisodeId)
            guard let localMediaProgress = localMediaProgress else {
                call.resolve(["error": "Library Item not found"])
                return
            }

            // Update finished status
            try localMediaProgress.updateIsFinished(isFinished)

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
        } catch {
            debugPrint(error)
            call.resolve(["error": "Failed to mark as complete"])
            return
        }
    }

    @objc func updateDeviceSettings(_ call: CAPPluginCall) {
        let disableAutoRewind = call.getBool("disableAutoRewind") ?? false
        let enableAltView = call.getBool("enableAltView") ?? false
        let allowSeekingOnMediaControls = call.getBool("allowSeekingOnMediaControls") ?? false
        let jumpBackwardsTime = call.getInt("jumpBackwardsTime") ?? 10
        let jumpForwardTime = call.getInt("jumpForwardTime") ?? 10
        let lockOrientation = call.getString("lockOrientation") ?? "NONE"
        let hapticFeedback = call.getString("hapticFeedback") ?? "LIGHT"
        let languageCode = call.getString("languageCode") ?? "en-us"
        let downloadUsingCellular = call.getString("downloadUsingCellular") ?? "ALWAYS"
        let streamingUsingCellular = call.getString("streamingUsingCellular") ?? "ALWAYS"
        let settings = DeviceSettings()
        settings.disableAutoRewind = disableAutoRewind
        settings.enableAltView = enableAltView
        settings.allowSeekingOnMediaControls = allowSeekingOnMediaControls
        settings.jumpBackwardsTime = jumpBackwardsTime
        settings.jumpForwardTime = jumpForwardTime
        settings.lockOrientation = lockOrientation
        settings.hapticFeedback = hapticFeedback
        settings.languageCode = languageCode
        settings.downloadUsingCellular = downloadUsingCellular
        settings.streamingUsingCellular = streamingUsingCellular

        Database.shared.setDeviceSettings(deviceSettings: settings)

        // Updates the media notification controls (for allowSeekingOnMediaControls setting)
        PlayerHandler.updateRemoteTransportControls()

        getDeviceData(call)
    }

    @objc func updateLocalEbookProgress(_ call: CAPPluginCall) {
        let localLibraryItemId = call.getString("localLibraryItemId")
        let ebookLocation = call.getString("ebookLocation", "")
        let ebookProgress = call.getDouble("ebookProgress", 0.0)

        logger.log("updateLocalEbookProgress \(localLibraryItemId ?? "Unknown") | ebookLocation: \(ebookLocation) | ebookProgress: \(ebookProgress)")

        do {
            let localMediaProgress = try LocalMediaProgress.fetchOrCreateLocalMediaProgress(localMediaProgressId: localLibraryItemId, localLibraryItemId: localLibraryItemId, localEpisodeId: nil)
            guard let localMediaProgress = localMediaProgress else {
                call.resolve(["error": "Library Item not found"])
                return
            }

            // Update finished status
            try localMediaProgress.updateEbookProgress(ebookLocation: ebookLocation, ebookProgress: ebookProgress)

            // Build API response
            let progressDictionary = try? localMediaProgress.asDictionary()
            let response: [String: Any] = ["localMediaProgress": progressDictionary ?? ""]
            call.resolve(response)
        } catch {
            debugPrint(error)
            call.resolve(["error": "Failed to update ebook progress"])
            return
        }
    }
}
