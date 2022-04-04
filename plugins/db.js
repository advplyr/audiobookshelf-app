import { Capacitor } from '@capacitor/core';
import { DbManager } from './capacitor/DbManager'

const isWeb = Capacitor.getPlatform() == 'web'

class DbService {
  constructor() { }

  save(db, key, value) {
    if (isWeb) return
    return DbManager.saveFromWebview({ db, key, value }).then(() => {
      console.log('Saved data', db, key, JSON.stringify(value))
    }).catch((error) => {
      console.error('Failed to save data', error)
    })
  }

  load(db, key) {
    if (isWeb) return null
    return DbManager.loadFromWebview({ db, key }).then((data) => {
      console.log('Loaded data', db, key, JSON.stringify(data))
      return data
    }).catch((error) => {
      console.error('Failed to load', error)
      return null
    })
  }

  getDeviceData() {
    return DbManager.getDeviceData_WV().then((data) => {
      console.log('Loaded device data', JSON.stringify(data))
      return data
    })
  }

  setServerConnectionConfig(serverConnectionConfig) {
    return DbManager.setCurrentServerConnectionConfig_WV(serverConnectionConfig).then((data) => {
      console.log('Set server connection config', JSON.stringify(data))
      return data
    })
  }

  removeServerConnectionConfig(serverConnectionConfigId) {
    return DbManager.removeServerConnectionConfig_WV({ serverConnectionConfigId }).then((data) => {
      console.log('Removed server connection config', serverConnectionConfigId)
      return true
    })
  }

  logout() {
    return DbManager.logout_WV()
  }

  getLocalFolders() {
    if (isWeb) return []
    return DbManager.getLocalFolders_WV().then((data) => {
      console.log('Loaded local folders', JSON.stringify(data))
      if (data.folders && typeof data.folders == 'string') {
        return JSON.parse(data.folders)
      }
      return data.folders
    }).catch((error) => {
      console.error('Failed to load', error)
      return null
    })
  }

  getLocalFolder(folderId) {
    if (isWeb) return null
    return DbManager.getLocalFolder_WV({ folderId }).then((data) => {
      console.log('Got local folder', JSON.stringify(data))
      return data
    })
  }

  getLocalMediaItemsInFolder(folderId) {
    if (isWeb) return []
    return DbManager.getLocalMediaItemsInFolder_WV({ folderId }).then((data) => {
      console.log('Loaded local media items in folder', JSON.stringify(data))
      if (data.localMediaItems && typeof data.localMediaItems == 'string') {
        return JSON.parse(data.localMediaItems)
      }
      return data.localMediaItems
    })
  }

  getLocalLibraryItems() {
    if (isWeb) return []
    return DbManager.getLocalLibraryItems_WV().then((data) => {
      console.log('Loaded all local media items', JSON.stringify(data))
      if (data.localLibraryItems && typeof data.localLibraryItems == 'string') {
        return JSON.parse(data.localLibraryItems)
      }
      return data.localLibraryItems
    })
  }

  getLocalLibraryItem(id) {
    if (isWeb) return null
    return DbManager.getLocalLibraryItem_WV({ id })
  }
}

export default ({ app, store }, inject) => {
  inject('db', new DbService())
}