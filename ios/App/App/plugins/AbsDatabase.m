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
           CAP_PLUGIN_METHOD(getDeviceData, CAPPluginReturnPromise);
           )

