//
//  SecureStorage.swift
//  App
//
//  Created by advplyr on 2025-07-05
//

import Foundation
import Security

class SecureStorage {
    private static let tag = "SecureStorage"
    private static let serviceName = "AudiobookshelfRefreshTokens"
    
    /**
     * Encrypts and stores a refresh token for a specific server connection
     */
    func storeRefreshToken(serverConnectionConfigId: String, refreshToken: String) -> Bool {
        let key = "refresh_token_\(serverConnectionConfigId)"
        
        guard let data = refreshToken.data(using: .utf8) else {
            print("\(Self.tag): Failed to convert refresh token to data")
            return false
        }
        
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.serviceName,
            kSecAttrAccount as String: key,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock
        ]
        
        // First, try to delete any existing item
        SecItemDelete(query as CFDictionary)
        
        // Then add the new item
        let status = SecItemAdd(query as CFDictionary, nil)
        
        if status == errSecSuccess {
            print("\(Self.tag): Successfully stored encrypted refresh token for server: \(serverConnectionConfigId)")
            return true
        } else {
            print("\(Self.tag): Failed to store refresh token for server: \(serverConnectionConfigId), status: \(status)")
            return false
        }
    }
    
    /**
     * Retrieves and decrypts a refresh token for a specific server connection
     */
    func getRefreshToken(serverConnectionConfigId: String) -> String? {
        let key = "refresh_token_\(serverConnectionConfigId)"
        
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.serviceName,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        
        if status == errSecSuccess,
           let data = result as? Data,
           let refreshToken = String(data: data, encoding: .utf8) {
            return refreshToken
        } else {
            print("\(Self.tag): Failed to retrieve refresh token for server: \(serverConnectionConfigId), status: \(status)")
            return nil
        }
    }
    
    /**
     * Removes a refresh token for a specific server connection
     */
    func removeRefreshToken(serverConnectionConfigId: String) -> Bool {
        let key = "refresh_token_\(serverConnectionConfigId)"
        
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.serviceName,
            kSecAttrAccount as String: key
        ]
        
        let status = SecItemDelete(query as CFDictionary)
        
        if status == errSecSuccess || status == errSecItemNotFound {
            print("\(Self.tag): Successfully removed refresh token for server: \(serverConnectionConfigId)")
            return true
        } else {
            print("\(Self.tag): Failed to remove refresh token for server: \(serverConnectionConfigId), status: \(status)")
            return false
        }
    }
    
    /**
     * Checks if a refresh token exists for a specific server connection
     */
    func hasRefreshToken(serverConnectionConfigId: String) -> Bool {
        let key = "refresh_token_\(serverConnectionConfigId)"
        
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.serviceName,
            kSecAttrAccount as String: key,
            kSecReturnData as String: false,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        
        let status = SecItemCopyMatching(query as CFDictionary, nil)
        return status == errSecSuccess
    }
} 