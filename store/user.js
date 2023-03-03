export const state = () => ({
  user: null,
  serverConnectionConfig: null,
  settings: {
    mobileOrderBy: 'addedAt',
    mobileOrderDesc: true,
    mobileFilterBy: 'all',
    playbackRate: 1,
    collapseSeries: false,
    collapseBookSeries: false
  }
})

export const getters = {
  getIsRoot: (state) => state.user && state.user.type === 'root',
  getIsAdminOrUp: (state) => state.user && (state.user.type === 'admin' || state.user.type === 'root'),
  getToken: (state) => {
    return state.user ? state.user.token : null
  },
  getServerConnectionConfigId: (state) => {
    return state.serverConnectionConfig ? state.serverConnectionConfig.id : null
  },
  getServerAddress: (state) => {
    return state.serverConnectionConfig ? state.serverConnectionConfig.address : null
  },
  getServerConfigName: (state) => {
    return state.serverConnectionConfig ? state.serverConnectionConfig.name : null
  },
  getCustomHeaders: (state) => {
    return state.serverConnectionConfig ? state.serverConnectionConfig.customHeaders : null
  },
  getUserMediaProgress: (state) => (libraryItemId, episodeId = null) => {
    if (!state.user || !state.user.mediaProgress) return null
    return state.user.mediaProgress.find(li => {
      if (episodeId && li.episodeId !== episodeId) return false
      return li.libraryItemId == libraryItemId
    })
  },
  getUserBookmarksForItem: (state) => (libraryItemId) => {
    if (!state?.user?.bookmarks) return []
    return state.user.bookmarks.filter(bm => bm.libraryItemId === libraryItemId)
  },
  getUserSetting: (state) => (key) => {
    return state.settings ? state.settings[key] || null : null
  },
  getUserCanDownload: (state) => {
    return state.user && state.user.permissions ? !!state.user.permissions.download : false
  }
}

export const actions = {
  // When changing libraries make sure sort and filter is still valid
  checkUpdateLibrarySortFilter({ state, dispatch, commit }, mediaType) {
    const settingsUpdate = {}
    if (mediaType == 'podcast') {
      if (state.settings.mobileOrderBy == 'media.metadata.authorName' || state.settings.mobileOrderBy == 'media.metadata.authorNameLF') {
        settingsUpdate.mobileOrderBy = 'media.metadata.author'
      }
      if (state.settings.mobileOrderBy == 'media.duration') {
        settingsUpdate.mobileOrderBy = 'media.numTracks'
      }
      if (state.settings.mobileOrderBy == 'media.metadata.publishedYear') {
        settingsUpdate.mobileOrderBy = 'media.metadata.title'
      }
      const invalidFilters = ['series', 'authors', 'narrators', 'languages', 'progress', 'issues']
      const filterByFirstPart = (state.settings.mobileFilterBy || '').split('.').shift()
      if (invalidFilters.includes(filterByFirstPart)) {
        settingsUpdate.mobileFilterBy = 'all'
      }
    } else {
      if (state.settings.mobileOrderBy == 'media.metadata.author') {
        settingsUpdate.mobileOrderBy = 'media.metadata.authorName'
      }
      if (state.settings.mobileOrderBy == 'media.numTracks') {
        settingsUpdate.mobileOrderBy = 'media.duration'
      }
    }
    if (Object.keys(settingsUpdate).length) {
      dispatch('updateUserSettings', settingsUpdate)
    }
  },
  async updateUserSettings({ state, commit }, payload) {
    if (!payload) return false

    let hasChanges = false
    const existingSettings = { ...state.settings }
    for (const key in existingSettings) {
      if (payload[key] !== undefined && existingSettings[key] !== payload[key]) {
        hasChanges = true
        existingSettings[key] = payload[key]
      }
    }
    if (hasChanges) {
      commit('setSettings', existingSettings)
      await this.$localStore.setUserSettings(existingSettings)
      this.$eventBus.$emit('user-settings', state.settings)
    }
  },
  async loadUserSettings({ state, commit }) {
    const userSettingsFromLocal = await this.$localStore.getUserSettings()

    if (userSettingsFromLocal) {
      const userSettings = { ...state.settings }
      for (const key in userSettings) {
        if (userSettingsFromLocal[key] !== undefined) {
          userSettings[key] = userSettingsFromLocal[key]
        }
      }
      commit('setSettings', userSettings)
      this.$eventBus.$emit('user-settings', state.settings)
    }
  }
}

export const mutations = {
  logout(state) {
    state.user = null
    state.serverConnectionConfig = null
  },
  setUser(state, user) {
    state.user = user
  },
  removeMediaProgress(state, id) {
    if (!state.user) return
    state.user.mediaProgress = state.user.mediaProgress.filter(mp => mp.id != id)
  },
  updateUserMediaProgress(state, data) {
    if (!data || !state.user) return
    const mediaProgressIndex = state.user.mediaProgress.findIndex(mp => mp.id === data.id)
    if (mediaProgressIndex >= 0) {
      state.user.mediaProgress.splice(mediaProgressIndex, 1, data)
    } else {
      state.user.mediaProgress.push(data)
    }
  },
  setServerConnectionConfig(state, serverConnectionConfig) {
    state.serverConnectionConfig = serverConnectionConfig
  },
  setSettings(state, settings) {
    if (!settings) return
    state.settings = settings
  }
}