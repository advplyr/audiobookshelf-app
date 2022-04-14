//
//  AbsAudioPlayer.m
//  App
//
//  Created by Rasmus Krämer on 13.04.22.
//

#import <Foundation/Foundation.h>
#import <Capacitor/Capacitor.h>

CAP_PLUGIN(AbsAudioPlayer, "AbsAudioPlayer",
           CAP_PLUGIN_METHOD(prepareLibraryItem, CAPPluginReturnPromise);
           )

