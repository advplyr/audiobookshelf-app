# TTS Read Aloud Feature

This branch adds **Text-to-Speech (Read Aloud)** support for ebooks in audiobookshelf-app.

## Files Added

| File | Purpose |
|---|---|
| `plugins/tts.js` | Capacitor plugin registration |
| `plugins/tts-web.js` | Web `speechSynthesis` fallback |
| `store/tts.js` | Vuex store (state, actions, getters) |
| `components/modals/TtsControlPanel.vue` | Bottom sheet UI (play/pause, speed, pitch, voice) |
| `components/readers/EpubReaderTtsMixin.js` | Mixin for EpubReader.vue (sentence extraction, highlighting) |
| `utils/tts-sentences.js` | Sentence splitter utility |
| `android/app/src/main/java/.../TtsPlugin.kt` | Kotlin native plugin (Android `TextToSpeech` API) |
| `ios/App/App/TtsPlugin.swift` | Swift native plugin (iOS `AVSpeechSynthesizer`) |

## Integration Steps

### 1. Register Vuex module in `store/index.js`
```js
import * as tts from './tts'
// add to modules:
modules: { tts }
```

### 2. Register Capacitor plugin in `nuxt.config.js`
```js
plugins: [
  // ...existing
  { src: '~/plugins/tts.js', mode: 'client' }
]
```

### 3. Register native Android plugin in `MainActivity.kt`
```kotlin
import com.audiobookshelf.app.plugins.TtsPlugin
// In onCreate, add:
add(TtsPlugin::class.java)
```

### 4. Register native iOS plugin in `AppDelegate.swift`
```swift
// The plugin is auto-discovered via @objc annotation
// Ensure TtsPlugin.swift is included in the Xcode project target
```

### 5. Add mixin + button to `EpubReader.vue`
```js
import EpubReaderTtsMixin from './EpubReaderTtsMixin'
export default {
  mixins: [EpubReaderTtsMixin],
  // ...
}
```
In template, add TTS button to the bottom bar:
```html
<button @click="openTtsPanel" class="ml-2 p-1">
  <svg ...speaker icon... />
</button>
<TtsControlPanel :show="showTtsPanel" :theme="ereaderSettings.theme"
  @close="closeTtsPanel" @start="startTtsReading" />
```
Also call `injectTtsStyles()` inside `rendition.on('rendered', ...)` event.

### 6. Android Manifest — add to `android/app/src/main/AndroidManifest.xml`
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"/>
```

### 7. iOS Info.plist — add background audio mode
```xml
<key>UIBackgroundModes</key>
<array><string>audio</string></array>
```

## How It Works

1. User taps 🔊 button in ebook reader toolbar
2. Bottom sheet opens with play controls
3. On Play: sentences extracted from current epub view → dispatched to Vuex store
4. Vuex store calls native `TtsPlugin.speak()` sentence by sentence
5. Native engine (Android `TextToSpeech` / iOS `AVSpeechSynthesizer`) reads aloud
6. Current sentence highlighted in yellow in epub view
7. Progress synced to ABS server on stop
8. Works in background, responds to Bluetooth controls (iOS: via `MPRemoteCommandCenter`)
