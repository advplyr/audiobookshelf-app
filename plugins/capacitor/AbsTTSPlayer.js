import { registerPlugin, Capacitor } from '@capacitor/core'

/**
 * Native read aloud (TTS) player. The native side keeps speaking with the
 * screen off, shows a media notification and (on Android) integrates with
 * the media session / Android Auto.
 *
 * See docs/native-tts-player-design.md for the plugin contract.
 * F1 implements Android; other platforms fall back to the WebView loop
 * in mixins/ttsPlayer.js.
 */
const AbsTTSPlayer = registerPlugin('AbsTTSPlayer')

export const isNativeTTSPlayerAvailable = () => {
  return Capacitor.getPlatform() === 'android' && Capacitor.isPluginAvailable('AbsTTSPlayer')
}

export { AbsTTSPlayer }
