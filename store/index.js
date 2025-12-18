import { Network } from '@capacitor/network'
import { AbsAudioPlayer } from '@/plugins/capacitor'
import { PlayMethod } from '@/plugins/constants'

export const state = () => ({
  deviceData: null,
  currentPlaybackSession: null,
  playerIsPlaying: false,
  playerIsFullscreen: false,
  playerIsStartingPlayback: false, // When pressing play before native play response
  playerStartingPlaybackMediaId: null,
  isCasting: false,
  isCastAvailable: false,
  attemptingConnection: false,
  socketConnected: false,
  networkConnected: false,
  networkConnectionType: null,
  isNetworkUnmetered: true,
  isFirstLoad: true,
  isFirstAudioLoad: true,
  hasStoragePermission: false,
  selectedLibraryItem: null,
  showReader: false,
  ereaderKeepProgress: false,
  ereaderFileId: null,
  showSideDrawer: false,
  isNetworkListenerInit: false,
  serverSettings: null,
  lastBookshelfScrollData: {},
  lastItemScrollData: {},
  playbackQueue: []
})

export const getters = {
  getCurrentPlaybackSessionId: (state) => {
    return state.currentPlaybackSession?.id || null
  },
  getIsPlayerOpen: (state) => {
    return !!state.currentPlaybackSession
  },
  getIsCurrentSessionLocal: (state) => {
    return state.currentPlaybackSession?.playMethod == PlayMethod.LOCAL
  },
  getIsMediaStreaming: (state) => (libraryItemId, episodeId) => {
    if (!state.currentPlaybackSession || !libraryItemId) return false

    // Check using local library item id and local episode id
    const isLocalLibraryItemId = libraryItemId.startsWith('local_')
    if (isLocalLibraryItemId) {
      if (state.currentPlaybackSession.localLibraryItem?.id !== libraryItemId) {
        return false
      }
      if (!episodeId) return true
      return state.currentPlaybackSession.localEpisodeId === episodeId
    }

    if (state.currentPlaybackSession.libraryItemId !== libraryItemId) {
      return false
    }
    if (!episodeId) return true
    return state.currentPlaybackSession.episodeId === episodeId
  },
  getServerSetting: (state) => (key) => {
    if (!state.serverSettings) return null
    return state.serverSettings[key]
  },
  getJumpForwardTime: (state) => {
    if (!state.deviceData?.deviceSettings) return 10
    return state.deviceData.deviceSettings.jumpForwardTime || 10
  },
  getJumpBackwardsTime: (state) => {
    if (!state.deviceData?.deviceSettings) return 10
    return state.deviceData.deviceSettings.jumpBackwardsTime || 10
  },
  getAltViewEnabled: (state) => {
    if (!state.deviceData?.deviceSettings) return true
    return state.deviceData.deviceSettings.enableAltView
  },
  getOrientationLockSetting: (state) => {
    return state.deviceData?.deviceSettings?.lockOrientation
  },
  getCanDownloadUsingCellular: (state) => {
    if (!state.deviceData?.deviceSettings?.downloadUsingCellular) return 'ALWAYS'
    return state.deviceData.deviceSettings.downloadUsingCellular || 'ALWAYS'
  },
  getCanStreamingUsingCellular: (state) => {
    if (!state.deviceData?.deviceSettings?.streamingUsingCellular) return 'ALWAYS'
    return state.deviceData.deviceSettings.streamingUsingCellular || 'ALWAYS'
  },
  /**
   * Old server versions require a token for images
   *
   * @param {*} state
   * @returns {boolean} True if server version is less than 2.17
   */
  getDoesServerImagesRequireToken: (state) => {
    const serverVersion = state.serverSettings?.version
    if (!serverVersion) return false
    const versionParts = serverVersion.split('.')
    const majorVersion = parseInt(versionParts[0])
    const minorVersion = parseInt(versionParts[1])
    return majorVersion < 2 || (majorVersion == 2 && minorVersion < 17)
  },
  getPlaybackQueue: (state) => {
    return state.playbackQueue
  },
  getNextQueueItem: (state) => {
    return state.playbackQueue.length > 0 ? state.playbackQueue[0] : null
  },
  hasQueueItems: (state) => {
    return state.playbackQueue.length > 0
  },
  getQueueLength: (state) => {
    return state.playbackQueue.length
  }
}

export const actions = {
  // Listen for network connection
  async setupNetworkListener({ state, commit }) {
    if (state.isNetworkListenerInit) return
    commit('setNetworkListenerInit', true)

    const status = await Network.getStatus()
    console.log('Network status', status)
    commit('setNetworkStatus', status)

    Network.addListener('networkStatusChange', (status) => {
      console.log('Network status changed', status.connected, status.connectionType)
      commit('setNetworkStatus', status)
    })

    AbsAudioPlayer.addListener('onNetworkMeteredChanged', (payload) => {
      const isUnmetered = payload.value
      console.log('On network metered changed', isUnmetered)
      commit('setIsNetworkUnmetered', isUnmetered)
    })
  },

  async addToQueue({ commit, state }, queueItem) {
    commit('addToQueue', queueItem)
    await this.$localStore.setPlaybackQueue(state.playbackQueue)
  },

  async removeFromQueue({ commit, state }, index) {
    commit('removeFromQueue', index)
    await this.$localStore.setPlaybackQueue(state.playbackQueue)
  },

  async clearQueue({ commit, state }) {
    commit('clearQueue')
    await this.$localStore.setPlaybackQueue(state.playbackQueue)
  },

  async moveQueueItem({ commit, state }, { fromIndex, toIndex }) {
    commit('moveQueueItem', { fromIndex, toIndex })
    await this.$localStore.setPlaybackQueue(state.playbackQueue)
  },

  async loadSavedQueue({ commit }) {
    const savedQueue = await this.$localStore.getPlaybackQueue()
    // Always update state, even when queue is empty
    if (savedQueue) {
      commit('setPlaybackQueue', savedQueue)
      console.log('[Store] Loaded saved queue with', savedQueue.length, 'items')
    } else {
      commit('setPlaybackQueue', [])
      console.log('[Store] No saved queue found, clearing queue')
    }
  },

  async playNextInQueue({ commit, getters, dispatch }) {
    const nextItem = getters.getNextQueueItem
    if (nextItem) {
      await dispatch('removeFromQueue', 0)
      dispatch('playQueueItem', nextItem)
    }
  },

  playQueueItem({ commit }, queueItem) {
    if (typeof window !== 'undefined' && window.$nuxt) {
      window.$nuxt.$eventBus.$emit('play-item', {
        libraryItemId: queueItem.libraryItemId,
        episodeId: queueItem.episodeId,
        startTime: queueItem.currentTime || 0,
        paused: false,
        serverLibraryItemId: queueItem.serverLibraryItemId,
        serverEpisodeId: queueItem.serverEpisodeId
      })
    }
  },

  async addCurrentlyPlayingToQueue({ commit, state, dispatch, getters }) {
    const currentSession = state.currentPlaybackSession
    if (!currentSession) return

    const isLocal = currentSession.localLibraryItem != null
    const libraryItem = isLocal ? currentSession.localLibraryItem : {
      id: currentSession.libraryItemId,
      media: {
        metadata: {
          title: currentSession.displayTitle,
          authorName: currentSession.displayAuthor
        },
        duration: currentSession.duration,
        coverPath: currentSession.coverPath
      }
    }

    let episode = null
    if (currentSession.episodeId) {
      episode = isLocal ? currentSession.localEpisode : {
        id: currentSession.episodeId,
        title: currentSession.displayTitle,
        duration: currentSession.duration
      }
    }

    const currentTime = currentSession.currentTime || 0

    const queueItem = {
      libraryItemId: currentSession.libraryItemId,
      episodeId: currentSession.episodeId,
      serverLibraryItemId: currentSession.libraryItemId,
      serverEpisodeId: currentSession.episodeId,
      title: currentSession.displayTitle,
      author: currentSession.displayAuthor,
      duration: currentSession.duration,
      coverPath: currentSession.coverPath,
      libraryItem: libraryItem,
      episode: episode,
      isLocal: isLocal,
      currentTime: currentTime
    }

    commit('addToQueueAtIndex', { queueItem, index: 0 })
    await this.$localStore.setPlaybackQueue(state.playbackQueue)
  }
}

export const mutations = {
  setDeviceData(state, deviceData) {
    state.deviceData = deviceData
  },
  setLastBookshelfScrollData(state, { scrollTop, path, name }) {
    state.lastBookshelfScrollData[name] = { scrollTop, path }
  },
  setLastItemScrollData(state, data) {
    state.lastItemScrollData = data
  },
  setPlaybackSession(state, playbackSession) {
    state.currentPlaybackSession = playbackSession

    state.isCasting = playbackSession?.mediaPlayer === 'cast-player'
  },
  setMediaPlayer(state, mediaPlayer) {
    state.isCasting = mediaPlayer === 'cast-player'
  },
  setCastAvailable(state, available) {
    state.isCastAvailable = available
  },
  setAttemptingConnection(state, val) {
    state.attemptingConnection = val
  },
  setPlayerPlaying(state, val) {
    state.playerIsPlaying = val
  },
  setPlayerFullscreen(state, val) {
    state.playerIsFullscreen = val
  },
  setPlayerIsStartingPlayback(state, mediaId) {
    state.playerStartingPlaybackMediaId = mediaId
    state.playerIsStartingPlayback = true
  },
  setPlayerDoneStartingPlayback(state) {
    state.playerStartingPlaybackMediaId = null
    state.playerIsStartingPlayback = false
  },
  setHasStoragePermission(state, val) {
    state.hasStoragePermission = val
  },
  setIsFirstLoad(state, val) {
    state.isFirstLoad = val
  },
  setIsFirstAudioLoad(state, val) {
    state.isFirstAudioLoad = val
  },
  setSocketConnected(state, val) {
    state.socketConnected = val
  },
  setNetworkListenerInit(state, val) {
    state.isNetworkListenerInit = val
  },
  setNetworkStatus(state, val) {
    if (val.connectionType !== 'none') {
      state.networkConnected = true
    } else {
      state.networkConnected = false
    }
    if (this.$platform === 'ios') {
      // Capacitor Network plugin only shows ios device connected if internet access is available.
      // This fix allows iOS users to use local servers without internet access.
      state.networkConnected = true
    }
    state.networkConnectionType = val.connectionType
  },
  setIsNetworkUnmetered(state, val) {
    state.isNetworkUnmetered = val
  },
  showReader(state, { libraryItem, keepProgress, fileId }) {
    state.selectedLibraryItem = libraryItem
    state.ereaderKeepProgress = keepProgress
    state.ereaderFileId = fileId

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

  addToQueue(state, queueItem) {
    const itemWithId = {
      ...queueItem,
      id: `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`
    }
    state.playbackQueue.push(itemWithId)
  },

  addToQueueAtIndex(state, { queueItem, index }) {
    const itemWithId = {
      ...queueItem,
      id: `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`
    }
    state.playbackQueue.splice(index, 0, itemWithId)
  },

  removeFromQueue(state, index) {
    if (index >= 0 && index < state.playbackQueue.length) {
      state.playbackQueue.splice(index, 1)
    }
  },

  clearQueue(state) {
    state.playbackQueue = []
  },

  moveQueueItem(state, { fromIndex, toIndex }) {
    if (fromIndex >= 0 && fromIndex < state.playbackQueue.length &&
        toIndex >= 0 && toIndex < state.playbackQueue.length) {
      const item = state.playbackQueue.splice(fromIndex, 1)[0]
      state.playbackQueue.splice(toIndex, 0, item)
    }
  },

  setPlaybackQueue(state, queue) {
    state.playbackQueue = queue
  }
}
