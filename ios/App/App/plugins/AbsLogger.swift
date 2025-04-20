//
//  AbsLogger.swift
//  Audiobookshelf
//
//  Created by advplyr on 4/20/25.
//

import Foundation
import Capacitor

@objc(AbsLogger)
public class AbsLogger: CAPPlugin, CAPBridgedPlugin {
    public var identifier = "AbsLoggerPlugin"
    public var jsName = "AbsLogger"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "info", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "error", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getAllLogs", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "clearLogs", returnType: CAPPluginReturnPromise)
    ]
    
    private let logger = AppLogger(category: "AbsLogger")
    
    @objc func info(_ call: CAPPluginCall) {
        let message = call.getString("message") ?? ""
        let tag = call.getString("tag") ?? ""

        logger.log("[\(tag)] \(message)")
        call.resolve()
    }
    
    @objc func error(_ call: CAPPluginCall) {
        let message = call.getString("message") ?? ""
        let tag = call.getString("tag") ?? ""

        logger.error("[\(tag)] \(message)")
        call.resolve()
    }
    
    @objc func getAllLogs(_ call: CAPPluginCall) {
        call.unimplemented("Not implemented on iOS")
    }
    
    @objc func clearLogs(_ call: CAPPluginCall) {
        call.unimplemented("Not implemented on iOS")
    }
}
