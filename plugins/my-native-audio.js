import Vue from 'vue'
import { registerPlugin, Capacitor } from '@capacitor/core';

Vue.prototype.$platform = Capacitor.getPlatform()

const MyNativeAudio = registerPlugin('MyNativeAudio');
export default MyNativeAudio;