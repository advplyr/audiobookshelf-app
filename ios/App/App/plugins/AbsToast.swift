//
//  AbsToast.swift
//  App
//
//  Created by GitHub Copilot on 12/09/2025.
//

import Foundation
import Capacitor

@objc(AbsToast)
public class AbsToast: CAPPlugin, CAPBridgedPlugin {
    public var identifier = "AbsToastPlugin"
    public var jsName = "AbsToast"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "show", returnType: CAPPluginReturnPromise)
    ]
    
    private let logger = AppLogger(category: "AbsToast")
    
    @objc func show(_ call: CAPPluginCall) {
        let message = call.getString("message", "")
        
        logger.log("Showing toast: \(message)")
        
        // For iOS, we'll use a simple approach since iOS doesn't have native toasts like Android
        // We could use a UIAlertController or just log it
        DispatchQueue.main.async {
            // For now, just resolve - iOS can use the web implementation or a custom overlay
            call.resolve()
        }
    }
}
