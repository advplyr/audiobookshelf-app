import { TtsPlugin } from '../plugins/tts'

export const state = () => ({
  isInitialized: false,
  isPlaying: false,
  isPaused: false,
  sentences: [],
  currentSentenceIndex: -1,
  rate: 1.0,
  pitch: 1.0,
  selectedVoice: null,
  availableVoices: [],
  libraryItemId: null,
  serverLibraryItemId: null,
  _listeners: []
})

export const mutations = {
  SET_INITIALIZED(state, val) {
    state.isInitialized = val
  },
  SET_PLAYING(state, val) {
    state.isPlaying = val
  },
  SET_PAUSED(state, val) {
    state.isPaused = val
  },
  SET_SENTENCES(state, sentences) {
    state.sentences = sentences
  },
  SET_CURRENT_INDEX(state, index) {
    state.currentSentenceIndex = index
  },
  SET_RATE(state, rate) {
    state.rate = rate
  },
  SET_PITCH(state, pitch) {
    state.pitch = pitch
  },
  SET_VOICE(state, voice) {
    state.selectedVoice = voice
  },
  SET_VOICES(state, voices) {
    state.availableVoices = voices
  },
  SET_LIBRARY_ITEM(state, { libraryItemId, serverLibraryItemId }) {
    state.libraryItemId = libraryItemId
    state.serverLibraryItemId = serverLibraryItemId
  },
  ADD_LISTENER(state, listener) {
    state._listeners.push(listener)
  },
  CLEAR_LISTENERS(state) {
    state._listeners = []
  }
}

export const actions = {
  async initTts({ commit, state }) {
    if (state.isInitialized) return
    try {
      await TtsPlugin.initialize()
      commit('SET_INITIALIZED', true)

      // Load available voices
      const { voices } = await TtsPlugin.getVoices()
      commit('SET_VOICES', voices || [])

      // Listen for TTS done event to advance to next sentence
      const doneListener = TtsPlugin.addListener('ttsDone', ({ utteranceId }) => {
        // handled in startReading action via direct call
      })
      commit('ADD_LISTENER', doneListener)
    } catch (e) {
      console.error('[TTS] init failed', e)
    }
  },

  async startReading({ commit, dispatch, state }, { sentences, startIndex = 0, libraryItemId, serverLibraryItemId }) {
    await dispatch('initTts')
    commit('SET_SENTENCES', sentences)
    commit('SET_LIBRARY_ITEM', { libraryItemId, serverLibraryItemId })
    commit('SET_PLAYING', true)
    commit('SET_PAUSED', false)
    await dispatch('speakFrom', startIndex)
  },

  async speakFrom({ commit, dispatch, state }, index) {
    if (index < 0 || index >= state.sentences.length) {
      // Finished all sentences
      commit('SET_PLAYING', false)
      commit('SET_CURRENT_INDEX', -1)
      dispatch('syncProgress', 1.0)
      return
    }

    commit('SET_CURRENT_INDEX', index)
    const text = state.sentences[index]

    try {
      await TtsPlugin.speak({
        text,
        utteranceId: String(index),
        rate: state.rate,
        pitch: state.pitch,
        voiceName: state.selectedVoice
      })

      // After speak resolves (sentence done), move to next
      if (state.isPlaying && !state.isPaused) {
        await dispatch('speakFrom', index + 1)
      }
    } catch (e) {
      console.error('[TTS] speak error', e)
      commit('SET_PLAYING', false)
    }
  },

  async pauseReading({ commit }) {
    await TtsPlugin.pause()
    commit('SET_PAUSED', true)
    commit('SET_PLAYING', false)
  },

  async resumeReading({ commit, dispatch, state }) {
    commit('SET_PAUSED', false)
    commit('SET_PLAYING', true)
    // Resume from current sentence
    await dispatch('speakFrom', state.currentSentenceIndex >= 0 ? state.currentSentenceIndex : 0)
  },

  async stopReading({ commit, dispatch, state }) {
    await TtsPlugin.stop()
    const progress = state.sentences.length > 0 && state.currentSentenceIndex >= 0
      ? state.currentSentenceIndex / state.sentences.length
      : 0
    dispatch('syncProgress', progress)
    commit('SET_PLAYING', false)
    commit('SET_PAUSED', false)
    commit('SET_CURRENT_INDEX', -1)
    commit('SET_SENTENCES', [])
  },

  async nextSentence({ dispatch, state }) {
    await TtsPlugin.stop()
    const next = (state.currentSentenceIndex + 1)
    if (next < state.sentences.length) {
      await dispatch('speakFrom', next)
    }
  },

  async prevSentence({ dispatch, state }) {
    await TtsPlugin.stop()
    const prev = Math.max(0, state.currentSentenceIndex - 1)
    await dispatch('speakFrom', prev)
  },

  setRate({ commit }, rate) {
    commit('SET_RATE', rate)
    TtsPlugin.setRate({ rate })
  },

  setPitch({ commit }, pitch) {
    commit('SET_PITCH', pitch)
    TtsPlugin.setPitch({ pitch })
  },

  setVoice({ commit }, voiceName) {
    commit('SET_VOICE', voiceName)
    TtsPlugin.setVoice({ voiceName })
  },

  async syncProgress({ state, rootGetters }, progress) {
    if (!state.serverLibraryItemId) return
    try {
      const axios = this.$axios
      if (!axios) return
      await axios.patch(`/api/me/progress/${state.serverLibraryItemId}`, {
        ebookProgress: progress,
        isFinished: progress >= 1.0
      })
    } catch (e) {
      console.error('[TTS] syncProgress failed', e)
    }
  }
}

export const getters = {
  isPlaying: (state) => state.isPlaying,
  isPaused: (state) => state.isPaused,
  currentSentenceIndex: (state) => state.currentSentenceIndex,
  sentences: (state) => state.sentences,
  rate: (state) => state.rate,
  pitch: (state) => state.pitch,
  selectedVoice: (state) => state.selectedVoice,
  availableVoices: (state) => state.availableVoices,
  progress: (state) => {
    if (!state.sentences.length) return 0
    return Math.round((state.currentSentenceIndex / state.sentences.length) * 100)
  }
}
