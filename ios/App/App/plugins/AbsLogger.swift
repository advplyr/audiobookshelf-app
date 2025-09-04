//
//  AbsLogger.swift
//  Audiobookshelf
//
//  Created by advplyr on 4/20/25.
//

import Foundation
import Capacitor

enum AbsLogLevel {
    case info
    case warn
    case error
}

func fileName(file: String = #file) -> String {
    return (file as NSString).lastPathComponent
}

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
    
    private static let shared: AbsLogger = {
        AbsLogger()
    }()
    
    public static func info(_ tag: String = #file, message: String)  {
        try? shared.info(tag: fileName(file: tag), message: message)
    }
    
    public static func error(_ tag: String = #file, message: String, error: Error? = nil)  {
        try? shared.error(tag: fileName(file: tag), message: message)
    }
    
    private let loggers: Dictionary<String, AppLogger> = [:]
    
    private func _logger(_ tag: String) -> AppLogger {
        return loggers[tag, default: AppLogger(category: tag)]
    }
    
    private func saveLogEntry(_ level: AbsLogLevel, tag: String, message: String) throws {
        let entry = LogEntry()
        entry.tag = tag
        entry.message = message
        entry.level = "\(level)"
        entry.timestamp = Int(Date().timeIntervalSince1970 * 1000)
        try Database.shared.saveLog(entry)
        self.notifyListeners("onLog", data: ["value": entry])
    }
    
    public func info(tag: String = "\(#file)", message: String) throws {
        _logger(tag).log("[\(tag)] \(message)")
        try saveLogEntry(.info, tag: tag, message: message)
    }
    
    public func error(tag: String = "\(#file)", message: String, error: Error? = nil) throws {
        _logger(tag).error("[\(tag)] \(message)")
        if let error = error { _logger(tag).error(error) }
        try saveLogEntry(.error, tag: tag, message: message)
    }
    
    @objc func info(_ call: CAPPluginCall) {
        let message = call.getString("message") ?? ""
        let tag = call.getString("tag") ?? ""
        
        do {
            try info(tag: tag, message: message)
            call.resolve()
        } catch {
            call.reject("Failed to log \(message)", "101", error)
        }
        
        
    }
    
    @objc func error(_ call: CAPPluginCall) {
        let message = call.getString("message") ?? ""
        let tag = call.getString("tag") ?? ""
        
        do {
            try error(tag: tag, message: message)
            call.resolve()
        } catch {
            call.reject("Failed to log \(message)", "101", error)
        }
        
    }
    
    @objc func getAllLogs(_ call: CAPPluginCall) {
        do {
            let logs = Database.shared.getAllLogs()
            call.resolve([ "value": try logs.asDictionaryArray()])
        } catch {
            call.reject("Failed to get logs", "100", error)
        }
        
    }
    
    @objc func clearLogs(_ call: CAPPluginCall) {
        do {
            try Database.shared.clearLogs()
            call.resolve()
        } catch {
            call.reject("Failed to clear logs", "100", error)
        }
        
    }
}
