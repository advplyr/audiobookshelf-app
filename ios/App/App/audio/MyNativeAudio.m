#import <Foundation/Foundation.h>
#import <Capacitor/Capacitor.h>

CAP_PLUGIN(MyNativeAudio, "MyNativeAudio",
           CAP_PLUGIN_METHOD(initPlayer, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(playPlayer, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(pausePlayer, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(seekForward, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(seekBackward, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(seekPlayer, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(terminateStream, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(getStreamSyncData, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(getCurrentTime, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(setPlaybackSpeed, CAPPluginReturnPromise);
           
           CAP_PLUGIN_METHOD(setSleepTimer, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(increaseSleepTime, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(decreaseSleepTime, CAPPluginReturnPromise);
           CAP_PLUGIN_METHOD(cancelSleepTimer, CAPPluginReturnPromise);
           )
