import { registerPlugin } from '@capacitor/core'

/**
 * TtsPlugin - Capacitor bridge to native Android TextToSpeech / iOS AVSpeechSynthesizer
 * Falls back to window.speechSynthesis on web
 */
export const TtsPlugin = registerPlugin('TtsPlugin', {
  web: () => import('./tts-web').then((m) => new m.TtsPluginWeb())
})

export default TtsPlugin
