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
  lastItemScrollData: {}
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
  }
}

export const actions = {
  // Listen for network connection
  async setupNetworkListener({ state, commit }) {
    if (state.isNetworkListenerInit) return
    commit('setNetworkListenerInit', true)

    const status = await Network.getStatus()
    commit('setNetworkStatus', status)

    Network.addListener('networkStatusChange', (status) => {
      commit('setNetworkStatus', status)
    })

    AbsAudioPlayer.addListener('onNetworkMeteredChanged', (payload) => {
      const isUnmetered = payload.value
      commit('setIsNetworkUnmetered', isUnmetered)
    })
  },

  // Save current playback session to local storage
  async saveCurrentPlaybackSession({ state }) {
    if (!state.currentPlaybackSession) return

    try {
      await this.$localStore.setLastPlaybackSession(state.currentPlaybackSession)
    } catch (error) {
      console.error('[Store] Failed to save current playback session', error)
    }
  },

  // Load and potentially resume from last playback session
  async loadLastPlaybackSession({ commit, dispatch }) {
    try {
      const lastSession = await this.$localStore.getLastPlaybackSession()
      if (!lastSession) {
        return null
      }

      return lastSession
    } catch (error) {
      console.error('[Store] Failed to load last playback session', error)
      return null
    }
  }, // Compare local session with server session and determine which is more recent
  async compareAndResumeSession({ state, commit, dispatch }, { localSession, serverSession }) {
    if (!localSession && !serverSession) return null

    // If only one session exists, use it
    if (!serverSession) return localSession
    if (!localSession) return serverSession

    // Compare book/episode to make sure they're the same media
    const isSameMedia = localSession.libraryItemId === serverSession.libraryItemId && localSession.episodeId === serverSession.episodeId

    if (!isSameMedia) {
      // Different media, prefer server session as it's likely more recent user action
      return serverSession
    }

    // Same media - compare timestamps and progress
    const localTime = localSession.updatedAt || localSession.startedAt || 0
    const serverTime = serverSession.updatedAt || serverSession.startedAt || 0

    // If server is newer and has progressed further, use server
    if (serverTime > localTime && serverSession.currentTime > localSession.currentTime) {
      return serverSession
    }

    // If local is newer or has progressed further, use local
    if (localTime >= serverTime || localSession.currentTime >= serverSession.currentTime) {
      return localSession
    }

    // Default to server session
    return serverSession
  },

  // Check if there's a resumable session available
  async hasResumableSession({ dispatch }) {
    try {
      const lastSession = await dispatch('loadLastPlaybackSession')
      if (!lastSession) return false

      const progress = lastSession.currentTime / lastSession.duration
      return progress > 0.01
    } catch (error) {
      console.error('[Store] Failed to check for resumable session', error)
      return false
    }
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

    // Auto-save session to local storage when it changes
    if (playbackSession && this.$localStore) {
      this.$localStore.setLastPlaybackSession(playbackSession).catch((error) => {
        console.error('[Store] Failed to auto-save playback session', error)
      })
    }
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
  }
}
