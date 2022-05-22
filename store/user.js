export const state = () => ({
  user: null,
  serverConnectionConfig: null,
  settings: {
    mobileOrderBy: 'addedAt',
    mobileOrderDesc: true,
    mobileFilterBy: 'all',
    orderBy: 'book.title',
    orderDesc: false,
    filterBy: 'all',
    playbackRate: 1,
    bookshelfCoverSize: 120
  },
  settingsListeners: []
})

export const getters = {
  getIsRoot: (state) => state.user && state.user.type === 'root',
  getToken: (state) => {
    return state.user ? state.user.token : null
  },
  getServerAddress: (state) => {
    return state.serverConnectionConfig ? state.serverConnectionConfig.address : null
  },
  getUserMediaProgress: (state) => (libraryItemId, episodeId = null) => {
    if (!state.user || !state.user.mediaProgress) return null
    return state.user.mediaProgress.find(li => {
      if (episodeId && li.episodeId !== episodeId) return false
      return li.libraryItemId == libraryItemId
    })
  },
  getUserBookmarksForItem: (state) => (libraryItemId) => {
    if (!state.user.bookmarks) return []
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
  async updateUserSettings({ state, commit }, payload) {
    if (state.serverConnectionConfig) {
      var updatePayload = {
        ...payload
      }
      return this.$axios.$patch('/api/me/settings', updatePayload).then((result) => {
        if (result.success) {
          commit('setSettings', result.settings)
          return true
        } else {
          return false
        }
      }).catch((error) => {
        console.error('Failed to update settings', error)
        return false
      })
    } else {
      console.log('Update settings without server')
      commit('setSettings', payload)
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
    var mediaProgressIndex = state.user.mediaProgress.findIndex(mp => mp.id === data.id)
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

    var hasChanges = false
    for (const key in settings) {
      if (state.settings[key] !== settings[key]) {
        if (key === 'mobileOrderBy' && settings[key] === 'recent') {
          settings[key] = 'addedAt'
        }
        hasChanges = true
        state.settings[key] = settings[key]
      }
    }
    if (hasChanges) {
      this.$localStore.setUserSettings({ ...state.settings })
      state.settingsListeners.forEach((listener) => {
        listener.meth(state.settings)
      })
    }
  },
  addSettingsListener(state, listener) {
    var index = state.settingsListeners.findIndex(l => l.id === listener.id)
    if (index >= 0) state.settingsListeners.splice(index, 1, listener)
    else state.settingsListeners.push(listener)
  },
  removeSettingsListener(state, listenerId) {
    state.settingsListeners = state.settingsListeners.filter(l => l.id !== listenerId)
  }
}