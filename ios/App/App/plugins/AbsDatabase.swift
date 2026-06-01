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
public class AbsDatabase: CAPPlugin, CAPBridgedPlugin {
    public var identifier = "AbsDatabasePlugin"
    public var jsName = "AbsDatabase"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "setCurrentServerConnectionConfig", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "removeServerConnectionConfig", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getRefreshToken", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "clearRefreshToken", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "logout", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getDeviceData", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getLocalLibraryItems", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getLocalLibraryItem", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getLocalLibraryItemByLId", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getLocalLibraryItemsInFolder", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getAllLocalMediaProgress", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "removeLocalMediaProgress", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "syncServerMediaProgressWithLocalMediaProgress", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "syncLocalSessionsWithServer", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "updateLocalMediaProgressFinished", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "updateDeviceSettings", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "updateLocalEbookProgress", returnType: CAPPluginReturnPromise)
    ]

    private let secureStorage = SecureStorage()

    // Used to notify the webview frontend that the token has been refreshed
    static var tokenRefreshCallback: ((String, [String: Any]) -> Void)?

    override public func load() {
        AbsDatabase.tokenRefreshCallback = { [weak self] eventName, data in
            self?.notifyListeners(eventName, data: data)
        }
    }

    @objc func setCurrentServerConnectionConfig(_ call: CAPPluginCall) {
        var id = call.getString("id")
        let address = call.getString("address", "")
        let version = call.getString("version", "")
        let userId = call.getString("userId", "")
        let username = call.getString("username", "")
        let token = call.getString("token", "")
        let refreshToken = call.getString("refreshToken", "") // Refresh only sent after login or refresh

        let name = "\(address) (\(username))"
        
        if id == nil {
            id = "\(address)@\(username)".toBase64()
        }
        
        if (refreshToken != "") {
            // Store refresh token securely if provided
            let hasRefreshToken = secureStorage.storeRefreshToken(serverConnectionConfigId: id ?? "", refreshToken: refreshToken)
            AbsLogger.info(message: "Refresh token secured = \(hasRefreshToken)")
        }

        let config = ServerConnectionConfig()
        config.id = id ?? ""
        config.index = 0
        config.name = name
        config.address = address
        config.version = version
        config.userId = userId
        config.username = username
        config.token = token

        Store.serverConfig = config
        let savedConfig = Store.serverConfig // Fetch the latest value
        call.resolve(convertServerConnectionConfigToJSON(config: savedConfig!))
    }
    
    @objc func removeServerConnectionConfig(_ call: CAPPluginCall) {
        let id = call.getString("serverConnectionConfigId", "")
        
        // Remove refresh token if it exists
        _ = secureStorage.removeRefreshToken(serverConnectionConfigId: id)
        
        Database.shared.deleteServerConnectionConfig(id: id)
        call.resolve()
    }
    
    @objc func getRefreshToken(_ call: CAPPluginCall) {
        let serverConnectionConfigId = call.getString("serverConnectionConfigId", "")
        
        let refreshToken = secureStorage.getRefreshToken(serverConnectionConfigId: serverConnectionConfigId)
        if let refreshToken = refreshToken {
            call.resolve(["refreshToken": refreshToken])
        } else {
            call.resolve()
        }
    }
    
    @objc func clearRefreshToken(_ call: CAPPluginCall) {
        let serverConnectionConfigId = call.getString("serverConnectionConfigId", "")
        
        let success = secureStorage.removeRefreshToken(serverConnectionConfigId: serverConnectionConfigId)
        call.resolve(["success": success])
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
            AbsLogger.error(message: "error reading local library items \(exception)")
            debugPrint(exception)
            call.resolve()
        }
    }

    @objc func getLocalLibraryItem(_ call: CAPPluginCall) {
        let id = call.getString("id") ?? ""
        do {
            let item = Database.shared.getLocalLibraryItem(localLibraryItemId: id)
            switch item {
                case .some(let foundItem):
                    call.resolve(try foundItem.asDictionary())
                default:
                    call.resolve()
            }
        } catch(let exception) {
            AbsLogger.error(message: "error reading local library item[\(id)] \(exception)")
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
            AbsLogger.error(message: "error while readling local library items: \(exception)", error: exception)
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
            AbsLogger.error(message: "Error while loading local media progress", error: error)
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
        AbsLogger.info(message: "Starting syncLocalSessionsWithServer isFirstSync=\(isFirstSync)")
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

            AbsLogger.info(message: "Saving local media progress \(serverMediaProgress)")
            try localMediaProgress.updateFromServerMediaProgress(serverMediaProgress)

            call.resolve(try localMediaProgress.asDictionary())
        } catch {
            call.reject("Failed to sync media progress")
            AbsLogger.error(message: "Failed to sync: \(error)")
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

        AbsLogger.info(message: "\(localMediaProgressId): isFinished=\(isFinished)")

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
        let coverTapToTogglePlayPause = call.getBool("coverTapToTogglePlayPause") ?? false
        let jumpBackwardsTime = call.getInt("jumpBackwardsTime") ?? 10
        let jumpForwardTime = call.getInt("jumpForwardTime") ?? 10
        let lockOrientation = call.getString("lockOrientation") ?? "NONE"
        let hapticFeedback = call.getString("hapticFeedback") ?? "LIGHT"
        let languageCode = call.getString("languageCode") ?? "en-us"
        let downloadUsingCellular = call.getString("downloadUsingCellular") ?? "ALWAYS"
        let streamingUsingCellular = call.getString("streamingUsingCellular") ?? "ALWAYS"
        let disableSleepTimerFadeOut = call.getBool("disableSleepTimerFadeOut") ?? false
        let settings = DeviceSettings()
        settings.disableAutoRewind = disableAutoRewind
        settings.enableAltView = enableAltView
        settings.allowSeekingOnMediaControls = allowSeekingOnMediaControls
        settings.coverTapToTogglePlayPause = coverTapToTogglePlayPause
        settings.jumpBackwardsTime = jumpBackwardsTime
        settings.jumpForwardTime = jumpForwardTime
        settings.lockOrientation = lockOrientation
        settings.hapticFeedback = hapticFeedback
        settings.languageCode = languageCode
        settings.downloadUsingCellular = downloadUsingCellular
        settings.streamingUsingCellular = streamingUsingCellular
        settings.disableSleepTimerFadeOut = disableSleepTimerFadeOut

        Database.shared.setDeviceSettings(deviceSettings: settings)

        // Updates the media notification controls (for allowSeekingOnMediaControls setting)
        PlayerHandler.updateRemoteTransportControls()

        getDeviceData(call)
    }

    @objc func updateLocalEbookProgress(_ call: CAPPluginCall) {
        let localLibraryItemId = call.getString("localLibraryItemId")
        let ebookLocation = call.getString("ebookLocation", "")
        let ebookProgress = call.getDouble("ebookProgress", 0.0)

        AbsLogger.info(message: "\(localLibraryItemId ?? "Unknown"): ebookLocation=\(ebookLocation) ebookProgress=\(ebookProgress)")

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
