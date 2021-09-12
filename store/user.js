import { Storage } from '@capacitor/storage'
import Vue from 'vue'

export const state = () => ({
  user: null,
  localUserAudiobooks: {},
  settings: {
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
  getUserAudiobook: (state) => (audiobookId) => {
    return state.user && state.user.audiobooks ? state.user.audiobooks[audiobookId] || null : null
  },
  getUserSetting: (state) => (key) => {
    return state.settings ? state.settings[key] || null : null
  },
  getFilterOrderKey: (state) => {
    return Object.values(state.settings).join('-')
  }
}

export const actions = {
  async updateUserSettings({ commit }, payload) {

    if (Vue.prototype.$server.connected) {
      var updatePayload = {
        ...payload
      }
      return this.$axios.$patch('/api/user/settings', updatePayload).then((result) => {
        if (result.success) {
          commit('setSettings', result.settings)
          console.log('Settings updated', result.settings)
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
  setLocalUserAudiobooks(state, userAudiobooks) {
    state.localUserAudiobooks = userAudiobooks
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
      console.log('Update settings in local storage')
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