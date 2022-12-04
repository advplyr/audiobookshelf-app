//
//  DeviceSettings.swift
//  App
//
//  Created by advplyr on 7/2/22.
//

import Foundation
import RealmSwift

enum LockOrientationSetting: Codable {
    case NONE
    case PORTRAIT
    case LANDSCAPE
}

class DeviceSettings: Object {
    @Persisted var disableAutoRewind: Bool = false
    @Persisted var enableAltView: Bool = false
    @Persisted var jumpBackwardsTime: Int = 10
    @Persisted var jumpForwardTime: Int = 10
    @Persisted var lockOrientation: LockOrientationSetting = LockOrientationSetting.NONE
}

func getDefaultDeviceSettings() -> DeviceSettings {
    return DeviceSettings()
}

func deviceSettingsToJSON(settings: DeviceSettings) -> Dictionary<String, Any> {
    return [
        "disableAutoRewind": settings.disableAutoRewind,
        "enableAltView": settings.enableAltView,
        "jumpBackwardsTime": settings.jumpBackwardsTime,
        "jumpForwardTime": settings.jumpForwardTime,
        "lockOrientation": settings.lockOrientation
    ]
}
