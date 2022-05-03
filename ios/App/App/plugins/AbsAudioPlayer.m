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
           CAP_PLUGIN_METHOD(closePlayback, CAPPluginReturnPromise);
           
           CAP_PLUGIN_METHOD(setPlaybackSpeed, CAPPluginReturnPromise);
           
           CAP_PLUGIN_METHOD(playPlayer, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(pausePlayer, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(playPause, CAPPluginReturnPromise);
           
           CAP_PLUGIN_METHOD(seek, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(seekForward, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(seekBackward, CAPPluginReturnPromise);
           
           CAP_PLUGIN_METHOD(getCurrentTime, CAPPluginReturnPromise);
           
           CAP_PLUGIN_METHOD(cancelSleepTimer, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(decreaseSleepTime, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(increaseSleepTime, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(getSleepTimerTime, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(setSleepTimer, CAPPluginReturnPromise);
           )
