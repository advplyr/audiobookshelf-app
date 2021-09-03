
#import <Foundation/Foundation.h>
#import <Capacitor/Capacitor.h>

CAP_PLUGIN(MyNativeAudio, "MyNativeAudio",
    CAP_PLUGIN_METHOD(initPlayer, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(playPlayer, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(pausePlayer, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(seekForward10, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(seekBackward10, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(seekPlayer, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(terminateStream, CAPPluginReturnPromise);
)
