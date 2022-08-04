//
//  AbsDatabase.m
//  App
//
//  Created by Rasmus Krämer on 11.04.22.
//

#import <Foundation/Foundation.h>
#import <Capacitor/Capacitor.h>

CAP_PLUGIN(AbsDatabase, "AbsDatabase",
           CAP_PLUGIN_METHOD(setCurrentServerConnectionConfig, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(removeServerConnectionConfig, CAPPluginReturnPromise);
           
           CAP_PLUGIN_METHOD(logout, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(getDeviceData, CAPPluginReturnPromise);
           
           CAP_PLUGIN_METHOD(getLocalLibraryItems, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(getLocalLibraryItem, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(getLocalLibraryItemByLId, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(getLocalLibraryItemsInFolder, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(getAllLocalMediaProgress, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(updateDeviceSettings, CAPPluginReturnPromise);
           )

