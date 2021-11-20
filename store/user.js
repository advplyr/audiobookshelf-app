export const state = () => ({
  user: null,
  userAudiobookData: [],
  localUserAudiobooks: {},
  settings: {
    mobileOrderBy: 'recent',
    mobileOrderDesc: true,
    mobileFilterBy: 'all',
    orderBy: 'book.title',
    orderDesc: false,
    filterBy: 'all',
    playbackRate: 1,
    bookshelfCoverSize: 120
  },
  settingsListeners: [],
  userAudiobooksListeners: [],
  collections: [],
  collectionsLoaded: false
})

export const getters = {
  getIsRoot: (state) => state.user && state.user.type === 'root',
  getToken: (state) => {
    return state.user ? state.user.token : null
  },
  getUserAudiobook: (state) => (audiobookId) => {
    return state.user && state.user.audiobooks ? state.user.audiobooks[audiobookId] || null : null
  },
  getLocalUserAudiobook: (state) => (audiobookId) => {
    return state.localUserAudiobooks ? state.localUserAudiobooks[audiobookId] || null : null
  },
  getMostRecentUserAudiobookData: (state, getters) => (audiobookId) => {
    return state.userAudiobookData.find(uabd => uabd.audiobookId === audiobookId)
    // var userAb = getters.getUserAudiobook(audiobookId)
    // var localUserAb = getters.getLocalUserAudiobook(audiobookId)
    // if (!localUserAb) return userAb
    // if (!userAb) return localUserAb
    // return localUserAb.lastUpdate > userAb.lastUpdate ? localUserAb : userAb
  },
  getUserSetting: (state) => (key) => {
    return state.settings ? state.settings[key] || null : null
  },
  getFilterOrderKey: (state) => {
    return Object.values(state.settings).join('-')
  },
  getCollection: state => id => {
    return state.collections.find(c => c.id === id)
  }
}

export const actions = {
  async updateUserSettings({ commit }, payload) {
    if (this.$server.connected) {
      var updatePayload = {
        ...payload
      }
      return this.$axios.$patch('/api/user/settings', updatePayload).then((result) => {
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
  },
  loadUserCollections({ state, commit }) {
    if (!this.$server.connected) {
      console.error('Not loading collections - not connected')
      return []
    }
    if (state.collectionsLoaded) {
      console.log('Collections already loaded')
      return state.collections
    }

    return this.$axios.$get('/api/collections').then((collections) => {
      commit('setCollections', collections)
      return collections
    }).catch((error) => {
      console.error('Failed to get collections', error)
      return []
    })
  },
  async syncUserAudiobookData({ state, commit }) {
    if (!state.user) {
      console.error('Sync user audiobook data invalid no user')
      return
    }
    var localUserAudiobookData = await this.$sqlStore.getAllUserAudiobookData() || []
    this.$axios.$post(`/api/syncUserAudiobookData`, { data: localUserAudiobookData }).then(async (abData) => {
      console.log('Synced user audiobook data', abData)
      await this.$sqlStore.setAllUserAudiobookData(abData)
    }).catch((error) => {
      console.error('Failed to sync user ab data', error)
    })
  },
  async updateUserAudiobookData({ state, commit }, uabdUpdate) {
    var userAbData = state.userAudiobookData.find(uab => uab.audiobookId === uabdUpdate.audiobookId)
    if (!userAbData) {
      uabdUpdate.startedAt = Date.now()
      this.$sqlStore.setUserAudiobookData(uabdUpdate)
    } else {
      var mergedUabData = { ...userAbData }
      for (const key in uabdUpdate) {
        mergedUabData[key] = uabdUpdate[key]
      }
      this.$sqlStore.setUserAudiobookData(mergedUabData)
    }
  }
}

export const mutations = {
  setUserAudiobookData(state, abdata) {
    var index = state.userAudiobookData.findIndex(uab => uab.audiobookId === abdata.audiobookId)
    if (index >= 0) {
      state.userAudiobookData.splice(index, 1, abdata)
    } else {
      state.userAudiobookData.push(abdata)
    }
  },
  removeUserAudiobookData(state, audiobookId) {
    state.userAudiobookData = state.userAudiobookData.filter(uab => uab.audiobookId !== audiobookId)
  },
  setAllUserAudiobookData(state, allAbData) {
    state.userAudiobookData = allAbData
  },
  setLocalUserAudiobooks(state, userAudiobooks) {
    // state.localUserAudiobooks = userAudiobooks
    // state.userAudiobooksListeners.forEach((listener) => {
    //   listener.meth()
    // })
  },
  setUserAudiobooks(state, userAudiobooks) {
    if (!state.user) return
    state.user.audiobooks = {
      ...userAudiobooks
    }
  },
  setUser(state, user) {
    state.user = user
    if (user) {
      if (user.token) this.$localStore.setToken(user.token)
      console.log('setUser', user.username)
    } else {
      this.$localStore.setToken(null)
      console.warn('setUser cleared')
    }
  },
  setSettings(state, settings) {
    if (!settings) return

    var hasChanges = false
    for (const key in settings) {
      if (state.settings[key] !== settings[key]) {
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
  },
  addUserAudiobookListener(state, listener) {
    var index = state.userAudiobooksListeners.findIndex(l => l.id === listener.id)
    if (index >= 0) state.userAudiobooksListeners.splice(index, 1, listener)
    else state.userAudiobooksListeners.push(listener)
  },
  removeUserAudiobookListener(state, listenerId) {
    state.userAudiobooksListeners = state.userAudiobooksListeners.filter(l => l.id !== listenerId)
  },
  setCollections(state, collections) {
    state.collections = collections
    state.collectionsLoaded = true
  },
  addUpdateCollection(state, collection) {
    var index = state.collections.findIndex(c => c.id === collection.id)
    if (index >= 0) {
      state.collections.splice(index, 1, collection)
    } else {
      state.collections.push(collection)
    }
  },
}