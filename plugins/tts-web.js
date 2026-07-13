/**
 * Web fallback using window.speechSynthesis
 */
export class TtsPluginWeb {
  constructor() {
    this._synth = window.speechSynthesis || null
    this._utterance = null
    this._listeners = {}
  }

  async initialize() {
    if (!this._synth) throw new Error('SpeechSynthesis not supported')
    return { ready: true }
  }

  async getVoices() {
    const voices = this._synth.getVoices().map((v) => ({
      name: v.name,
      lang: v.lang,
      localService: v.localService
    }))
    return { voices }
  }

  async speak({ text, utteranceId, rate, pitch, voiceName }) {
    if (!this._synth) throw new Error('SpeechSynthesis not supported')
    this._synth.cancel()
    this._utterance = new SpeechSynthesisUtterance(text)
    if (rate) this._utterance.rate = rate
    if (pitch) this._utterance.pitch = pitch
    if (voiceName) {
      const voice = this._synth.getVoices().find((v) => v.name === voiceName)
      if (voice) this._utterance.voice = voice
    }
    this._utterance.onend = () => this._emit('ttsDone', { utteranceId })
    this._utterance.onerror = (e) => this._emit('ttsError', { utteranceId, error: e.error })
    this._utterance.onboundary = (e) => {
      if (e.name === 'word') {
        this._emit('ttsWord', { utteranceId, charIndex: e.charIndex, charLength: e.charLength })
      }
    }
    this._synth.speak(this._utterance)
    return { started: true }
  }

  async stop() {
    this._synth?.cancel()
    return { stopped: true }
  }

  async pause() {
    this._synth?.pause()
    return { paused: true }
  }

  async resume() {
    this._synth?.resume()
    return { resumed: true }
  }

  async setRate({ rate }) {
    return { ok: true }
  }

  async setPitch({ pitch }) {
    return { ok: true }
  }

  async setVoice({ voiceName }) {
    return { ok: true }
  }

  addListener(eventName, callback) {
    if (!this._listeners[eventName]) this._listeners[eventName] = []
    this._listeners[eventName].push(callback)
    return { remove: () => this._removeListener(eventName, callback) }
  }

  _removeListener(eventName, callback) {
    if (!this._listeners[eventName]) return
    this._listeners[eventName] = this._listeners[eventName].filter((cb) => cb !== callback)
  }

  _emit(eventName, data) {
    if (!this._listeners[eventName]) return
    this._listeners[eventName].forEach((cb) => cb(data))
  }
}
