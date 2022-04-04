import { Storage } from '@capacitor/storage'

class LocalStorage {
  constructor(vuexStore) {
    this.vuexStore = vuexStore
  }

  async setUserSettings(settings) {
    try {
      await Storage.set({ key: 'userSettings', value: JSON.stringify(settings) })
    } catch (error) {
      console.error('[LocalStorage] Failed to update user settings', error)
    }
  }

  async getUserSettings() {
    try {
      var settingsObj = await Storage.get({ key: 'userSettings' }) || {}
      return settingsObj.value ? JSON.parse(settingsObj.value) : null
    } catch (error) {
      console.error('[LocalStorage] Failed to get user settings', error)
      return null
    }
  }

  async setServerSettings(settings) {
    try {
      await Storage.set({ key: 'serverSettings', value: JSON.stringify(settings) })
      console.log('Saved server settings', JSON.stringify(settings))
    } catch (error) {
      console.error('[LocalStorage] Failed to update server settings', error)
    }
  }

  async getServerSettings() {
    try {
      var settingsObj = await Storage.get({ key: 'serverSettings' }) || {}
      return settingsObj.value ? JSON.parse(settingsObj.value) : null
    } catch (error) {
      console.error('[LocalStorage] Failed to get server settings', error)
      return null
    }
  }

  async setUseChapterTrack(useChapterTrack) {
    try {
      await Storage.set({ key: 'useChapterTrack', value: useChapterTrack ? '1' : '0' })
    } catch (error) {
      console.error('[LocalStorage] Failed to set use chapter track', error)
    }
  }

  async getUseChapterTrack() {
    try {
      var obj = await Storage.get({ key: 'useChapterTrack' }) || {}
      return obj.value === '1'
    } catch (error) {
      console.error('[LocalStorage] Failed to get use chapter track', error)
      return false
    }
  }
}


export default ({ app, store }, inject) => {
  inject('localStore', new LocalStorage(store))
}