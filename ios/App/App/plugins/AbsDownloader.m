//
//  AbsDownloader.m
//  App
//
//  Created by advplyr on 5/13/22.
//

#import <Foundation/Foundation.h>
#import <Capacitor/Capacitor.h>

CAP_PLUGIN(AbsDownloader, "AbsDownloader",
           CAP_PLUGIN_METHOD(downloadLibraryItem, CAPPluginReturnPromise);
           )
