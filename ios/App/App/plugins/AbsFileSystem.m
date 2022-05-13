//
//  AbsFileSystem.m
//  App
//
//  Created by advplyr on 5/13/22.
//

#import <Foundation/Foundation.h>
#import <Capacitor/Capacitor.h>

CAP_PLUGIN(AbsFileSystem, "AbsFileSystem",
           CAP_PLUGIN_METHOD(selectFolder, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(checkFolderPermission, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(scanFolder, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(removeFolder, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(removeLocalLibraryItem, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(scanLocalLibraryItem, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(deleteItem, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(deleteTrackFromItem, CAPPluginReturnPromise);
           )
