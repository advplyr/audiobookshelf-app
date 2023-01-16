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
      const settingsObj = await Storage.get({ key: 'userSettings' }) || {}
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

  async setUseTotalTrack(useTotalTrack) {
    try {
      await Storage.set({ key: 'useTotalTrack', value: useTotalTrack ? '1' : '0' })
    } catch (error) {
      console.error('[LocalStorage] Failed to set use total track', error)
    }
  }

  async getUseTotalTrack() {
    try {
      var obj = await Storage.get({ key: 'useTotalTrack' }) || {}
      return obj.value === '1'
    } catch (error) {
      console.error('[LocalStorage] Failed to get use total track', error)
      return false
    }
  }

  async setPlayerLock(lock) {
    try {
      await Storage.set({ key: 'playerLock', value: lock ? '1' : '0' })
    } catch (error) {
      console.error('[LocalStorage] Failed to set player lock', error)
    }
  }

  async getPlayerLock() {
    try {
      var obj = await Storage.get({ key: 'playerLock' }) || {}
      return obj.value === '1'
    } catch (error) {
      console.error('[LocalStorage] Failed to get player lock', error)
      return false
    }
  }

  async setBookshelfListView(useIt) {
    try {
      await Storage.set({ key: 'bookshelfListView', value: useIt ? '1' : '0' })
    } catch (error) {
      console.error('[LocalStorage] Failed to set bookshelf list view', error)
    }
  }

  async getBookshelfListView() {
    try {
      var obj = await Storage.get({ key: 'bookshelfListView' }) || {}
      return obj.value === '1'
    } catch (error) {
      console.error('[LocalStorage] Failed to get bookshelf list view', error)
      return false
    }
  }

  async setLastLibraryId(libraryId) {
    try {
      await Storage.set({ key: 'lastLibraryId', value: libraryId })
      console.log('[LocalStorage] Set Last Library Id', libraryId)
    } catch (error) {
      console.error('[LocalStorage] Failed to set last library id', error)
    }
  }

  async removeLastLibraryId() {
    try {
      await Storage.remove({ key: 'lastLibraryId' })
      console.log('[LocalStorage] Remove Last Library Id')
    } catch (error) {
      console.error('[LocalStorage] Failed to remove last library id', error)
    }
  }

  async getLastLibraryId() {
    try {
      var obj = await Storage.get({ key: 'lastLibraryId' }) || {}
      return obj.value || null
    } catch (error) {
      console.error('[LocalStorage] Failed to get last library id', error)
      return false
    }
  }
}


export default ({ app, store }, inject) => {
  inject('localStore', new LocalStorage(store))
}