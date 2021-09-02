
export const state = () => ({
  streamAudiobook: null,
  playOnLoad: false,
  serverUrl: null,
  user: null
})

export const actions = {

}

export const mutations = {
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