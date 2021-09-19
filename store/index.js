import Vue from 'vue'

export const state = () => ({
  streamAudiobook: null,
  playingDownload: null,
  playOnLoad: false,
  serverUrl: null,
  appUpdateInfo: null,
  socketConnected: false,
  networkConnected: false,
  networkConnectionType: null,
  streamListener: null,
  isFirstLoad: true,
  hasStoragePermission: false
})

export const getters = {
  playerIsOpen: (state) => {
    return state.streamAudiobook || state.playingDownload
  },
  isAudiobookStreaming: (state) => id => {
    return (state.streamAudiobook && state.streamAudiobook.id === id)
  },
  isAudiobookPlaying: (state) => id => {
    return (state.playingDownload && state.playingDownload.id === id) || (state.streamAudiobook && state.streamAudiobook.id === id)
  }
}

export const actions = {}

export const mutations = {
  setHasStoragePermission(state, val) {
    state.hasStoragePermission = val
  },
  setIsFirstLoad(state, val) {
    state.isFirstLoad = val
  },
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
    if (audiobook) {
      state.playingDownload = null
    }
    Vue.set(state, 'streamAudiobook', audiobook)
    if (state.streamListener) {
      state.streamListener('stream', audiobook)
    }
  },
  setPlayingDownload(state, download) {
    if (download) {
      state.streamAudiobook = null
    }
    Vue.set(state, 'playingDownload', download)
    if (state.streamListener) {
      state.streamListener('download', download)
    }
  },
  setServerUrl(state, url) {
    state.serverUrl = url
  },
  setSocketConnected(state, val) {
    state.socketConnected = val
  },
  setNetworkStatus(state, val) {
    state.networkConnected = val.connected
    state.networkConnectionType = val.connectionType
  },
  setStreamListener(state, val) {
    state.streamListener = val
  },
  removeStreamListener(state) {
    state.streamListener = null
  }
}