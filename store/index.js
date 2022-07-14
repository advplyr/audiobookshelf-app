import { Network } from '@capacitor/network'

export const state = () => ({
  deviceData: null,
  playerLibraryItemId: null,
  playerEpisodeId: null,
  playerIsLocal: false,
  playerIsPlaying: false,
  playerIsFullscreen: false,
  isCasting: false,
  isCastAvailable: false,
  socketConnected: false,
  networkConnected: false,
  networkConnectionType: null,
  isFirstLoad: true,
  hasStoragePermission: false,
  selectedLibraryItem: null,
  showReader: false,
  showSideDrawer: false,
  isNetworkListenerInit: false,
  serverSettings: null,
  lastBookshelfScrollData: {},
  lastLocalMediaSyncResults: null
})

export const getters = {
  getIsItemStreaming: state => libraryItemId => {
    return state.playerLibraryItemId == libraryItemId
  },
  getIsEpisodeStreaming: state => (libraryItemId, episodeId) => {
    return state.playerLibraryItemId == libraryItemId && state.playerEpisodeId == episodeId
  },
  getServerSetting: state => key => {
    if (!state.serverSettings) return null
    return state.serverSettings[key]
  },
  getBookCoverAspectRatio: state => {
    if (!state.serverSettings) return 1
    return state.serverSettings.coverAspectRatio === 0 ? 1.6 : 1
  },
  getJumpForwardTime: state => {
    if (!state.deviceData || !state.deviceData.deviceSettings) return 10
    return state.deviceData.deviceSettings.jumpForwardTime || 10
  },
  getJumpBackwardsTime: state => {
    if (!state.deviceData || !state.deviceData.deviceSettings) return 10
    return state.deviceData.deviceSettings.jumpBackwardsTime || 10
  }
}

export const actions = {
  // Listen for network connection
  async setupNetworkListener({ state, commit }) {
    if (state.isNetworkListenerInit) return
    commit('setNetworkListenerInit', true)

    var status = await Network.getStatus()
    console.log('Network status', status)
    commit('setNetworkStatus', status)

    Network.addListener('networkStatusChange', (status) => {
      console.log('Network status changed', status.connected, status.connectionType)
      commit('setNetworkStatus', status)
    })
  }
}

export const mutations = {
  setDeviceData(state, deviceData) {
    state.deviceData = deviceData
  },
  setLastBookshelfScrollData(state, { scrollTop, path, name }) {
    state.lastBookshelfScrollData[name] = { scrollTop, path }
  },
  setPlayerItem(state, playbackSession) {
    state.playerIsLocal = playbackSession ? playbackSession.playMethod == this.$constants.PlayMethod.LOCAL : false

    if (state.playerIsLocal) {
      state.playerLibraryItemId = playbackSession ? playbackSession.localLibraryItem.id || null : null
      state.playerEpisodeId = playbackSession ? playbackSession.localEpisodeId || null : null
    } else {
      state.playerLibraryItemId = playbackSession ? playbackSession.libraryItemId || null : null
      state.playerEpisodeId = playbackSession ? playbackSession.episodeId || null : null
    }

    var mediaPlayer = playbackSession ? playbackSession.mediaPlayer : null
    state.isCasting = mediaPlayer === "cast-player"
  },
  setMediaPlayer(state, mediaPlayer) {
    state.isCasting = mediaPlayer === 'cast-player'
  },
  setCastAvailable(state, available) {
    state.isCastAvailable = available
  },
  setPlayerPlaying(state, val) {
    state.playerIsPlaying = val
  },
  setPlayerFullscreen(state, val) {
    state.playerIsFullscreen = val
  },
  setHasStoragePermission(state, val) {
    state.hasStoragePermission = val
  },
  setIsFirstLoad(state, val) {
    state.isFirstLoad = val
  },
  setSocketConnected(state, val) {
    state.socketConnected = val
  },
  setNetworkListenerInit(state, val) {
    state.isNetworkListenerInit = val
  },
  setNetworkStatus(state, val) {
    state.networkConnected = val.connected
    state.networkConnectionType = val.connectionType
  },
  openReader(state, libraryItem) {
    state.selectedLibraryItem = libraryItem
    state.showReader = true
  },
  setShowReader(state, val) {
    state.showReader = val
  },
  setShowSideDrawer(state, val) {
    state.showSideDrawer = val
  },
  setServerSettings(state, val) {
    state.serverSettings = val
    this.$localStore.setServerSettings(state.serverSettings)
  },
  setLastLocalMediaSyncResults(state, val) {
    state.lastLocalMediaSyncResults = val
  }
}