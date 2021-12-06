import { Storage } from '@capacitor/storage'

class LocalStorage {
  constructor(vuexStore) {
    this.vuexStore = vuexStore

    this.userAudiobooksLoaded = false
    this.downloadFolder = null
  }

  async setToken(token) {
    try {
      if (token) {
        await Storage.set({ key: 'token', value: token })
      } else {
        await Storage.remove({ key: 'token' })
      }
    } catch (error) {
      console.error('[LocalStorage] Failed to set token', error)
    }
  }

  async getToken() {
    try {
      return (await Storage.get({ key: 'token' }) || {}).value || null
    } catch (error) {
      console.error('[LocalStorage] Failed to get token', error)
      return null
    }
  }

  async setCurrentLibrary(library) {
    try {
      if (library) {
        await Storage.set({ key: 'library', value: JSON.stringify(library) })
      } else {
        await Storage.remove({ key: 'library' })
      }
    } catch (error) {
      console.error('[LocalStorage] Failed to set library', error)
    }
  }

  async getCurrentLibrary() {
    try {
      var _value = (await Storage.get({ key: 'library' }) || {}).value || null
      if (!_value) return null
      return JSON.parse(_value)
    } catch (error) {
      console.error('[LocalStorage] Failed to get current library', error)
      return null
    }
  }

  async setDownloadFolder(folderObj) {
    try {
      if (folderObj) {
        await Storage.set({ key: 'downloadFolder', value: JSON.stringify(folderObj) })
        this.downloadFolder = folderObj
        this.vuexStore.commit('setDownloadFolder', { ...this.downloadFolder })
      } else {
        await Storage.remove({ key: 'downloadFolder' })
        this.downloadFolder = null
        this.vuexStore.commit('setDownloadFolder', null)
      }

    } catch (error) {
      console.error('[LocalStorage] Failed to set download folder', error)
    }
  }

  async getDownloadFolder() {
    try {
      var _value = (await Storage.get({ key: 'downloadFolder' }) || {}).value || null
      if (!_value) return null
      this.downloadFolder = JSON.parse(_value)
      this.vuexStore.commit('setDownloadFolder', { ...this.downloadFolder })
      return this.downloadFolder
    } catch (error) {
      console.error('[LocalStorage] Failed to get download folder', error)
      return null
    }
  }

  async getServerUrl() {
    try {
      return (await Storage.get({ key: 'serverUrl' }) || {}).value || null
    } catch (error) {
      console.error('[LocalStorage] Failed to get serverUrl', error)
      return null
    }
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

  async setCurrent(current) {
    try {
      if (current) {
        await Storage.set({ key: 'current', value: JSON.stringify(current) })
      } else {
        await Storage.remove({ key: 'current' })
      }
    } catch (error) {
      console.error('[LocalStorage] Failed to set current', error)
    }
  }

  async getCurrent() {
    try {
      var currentObj = await Storage.get({ key: 'current' }) || {}
      return currentObj.value ? JSON.parse(currentObj.value) : null
    } catch (error) {
      console.error('[LocalStorage] Failed to get current', error)
      return null
    }
  }

  async setBookshelfView(view) {
    try {
      await Storage.set({ key: 'bookshelfView', value: view })
    } catch (error) {
      console.error('[LocalStorage] Failed to set bookshelf view', error)
    }
  }

  async getBookshelfView() {
    try {
      var view = await Storage.get({ key: 'bookshelfView' }) || {}
      return view.value || null
    } catch (error) {
      console.error('[LocalStorage] Failed to get bookshelf view', error)
      return null
    }
  }
}


export default ({ app, store }, inject) => {
  inject('localStore', new LocalStorage(store))
}