
export const state = () => ({
  streamAudiobook: null,
  playOnLoad: false,
  serverUrl: null,
  user: null,
  currentVersion: null,
  latestVersion: null,
  hasUpdate: true
})

export const actions = {}

export const mutations = {
  setCurrentVersion(state, verObj) {
    state.currentVersion = verObj
  },
  setLatestVersion(state, verObj) {
    state.latestVersion = verObj
  },
  setHasUpdate(state, val) {
    state.hasUpdate = val
  },
  closeStream(state, audiobookId) {
    if (state.streamAudiobook && state.streamAudiobook.id !== audiobookId) {
      return
    }
    state.streamAudiobook = null
  },
  setPlayOnLoad(state, val) {
    state.playOnLoad = val
  },
  setStreamAudiobook(state, audiobook) {
    state.streamAudiobook = audiobook
  },
  setServerUrl(state, url) {
    state.serverUrl = url
  },
  setUser(state, user) {
    state.user = user
  }
}