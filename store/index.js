
export const state = () => ({
  streamAudiobook: null,
  playOnLoad: false,
  serverUrl: null,
  user: null,
  appUpdateInfo: null
})

export const actions = {}

export const mutations = {
  setAppUpdateInfo(state, info) {
    state.appUpdateInfo = info
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