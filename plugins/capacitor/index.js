import Vue from 'vue'
import { AbsAudioPlayer } from './AbsAudioPlayer'
import { AbsDownloader } from './AbsDownloader'
import { AbsFileSystem } from './AbsFileSystem'
import { AbsDatabase } from './AbsDatabase'
import { AbsLogger } from './AbsLogger'
import { AbsCertificate } from './AbsCertificate'
import { Capacitor } from '@capacitor/core'

Vue.prototype.$platform = Capacitor.getPlatform()

export { AbsAudioPlayer, AbsDownloader, AbsFileSystem, AbsLogger, AbsDatabase, AbsCertificate }
