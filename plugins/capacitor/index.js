import Vue from 'vue'
import { AbsAudioPlayer } from './AbsAudioPlayer'
import { AbsTTSPlayer, isNativeTTSPlayerAvailable } from './AbsTTSPlayer'
import { AbsDownloader } from './AbsDownloader'
import { AbsFileSystem } from './AbsFileSystem'
import { AbsDatabase } from './AbsDatabase'
import { AbsLogger } from './AbsLogger'
import { Capacitor } from '@capacitor/core'

Vue.prototype.$platform = Capacitor.getPlatform()

export { AbsAudioPlayer, AbsTTSPlayer, isNativeTTSPlayerAvailable, AbsDownloader, AbsFileSystem, AbsLogger, AbsDatabase }
