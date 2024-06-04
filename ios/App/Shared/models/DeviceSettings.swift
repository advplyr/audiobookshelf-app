//
//  DeviceSettings.swift
//  App
//
//  Created by advplyr on 7/2/22.
//

import Foundation
import RealmSwift

class DeviceSettings: Object {
    @Persisted var disableAutoRewind: Bool = false
    @Persisted var enableAltView: Bool = true
    @Persisted var allowSeekingOnMediaControls: Bool = false
    @Persisted var jumpBackwardsTime: Int = 10
    @Persisted var jumpForwardTime: Int = 10
    @Persisted var lockOrientation: String = "NONE"
    @Persisted var hapticFeedback: String = "LIGHT"
    @Persisted var languageCode: String = "en-us"
    @Persisted var downloadUsingCellular: String = "ALWAYS"
    @Persisted var streamingUsingCellular: String = "ALWAYS"
}

func getDefaultDeviceSettings() -> DeviceSettings {
    return DeviceSettings()
}

func deviceSettingsToJSON(settings: DeviceSettings) -> Dictionary<String, Any> {
    return [
        "disableAutoRewind": settings.disableAutoRewind,
        "enableAltView": settings.enableAltView,
        "allowSeekingOnMediaControls": settings.allowSeekingOnMediaControls,
        "jumpBackwardsTime": settings.jumpBackwardsTime,
        "jumpForwardTime": settings.jumpForwardTime,
        "lockOrientation": settings.lockOrientation,
        "hapticFeedback": settings.hapticFeedback,
        "languageCode": settings.languageCode,
        "downloadUsingCellular": settings.downloadUsingCellular,
        "streamingUsingCellular": settings.streamingUsingCellular
    ]
}
