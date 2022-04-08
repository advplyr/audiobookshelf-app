import { Capacitor } from '@capacitor/core';
import { AbsDatabase } from './capacitor/AbsDatabase'

const isWeb = Capacitor.getPlatform() == 'web'

class DbService {
  constructor() { }

  save(db, key, value) {
    if (isWeb) return
    return AbsDatabase.saveFromWebview({ db, key, value }).then(() => {
      console.log('Saved data', db, key, JSON.stringify(value))
    }).catch((error) => {
      console.error('Failed to save data', error)
    })
  }

  load(db, key) {
    if (isWeb) return null
    return AbsDatabase.loadFromWebview({ db, key }).then((data) => {
      console.log('Loaded data', db, key, JSON.stringify(data))
      return data
    }).catch((error) => {
      console.error('Failed to load', error)
      return null
    })
  }

  getDeviceData() {
    return AbsDatabase.getDeviceData().then((data) => {
      console.log('Loaded device data', JSON.stringify(data))
      return data
    })
  }

  setServerConnectionConfig(serverConnectionConfig) {
    return AbsDatabase.setCurrentServerConnectionConfig(serverConnectionConfig).then((data) => {
      console.log('Set server connection config', JSON.stringify(data))
      return data
    })
  }

  removeServerConnectionConfig(serverConnectionConfigId) {
    return AbsDatabase.removeServerConnectionConfig({ serverConnectionConfigId }).then((data) => {
      console.log('Removed server connection config', serverConnectionConfigId)
      return true
    })
  }

  logout() {
    return AbsDatabase.logout()
  }

  getLocalFolders() {
    return AbsDatabase.getLocalFolders().then((data) => {
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
    return AbsDatabase.getLocalFolder({ folderId }).then((data) => {
      console.log('Got local folder', JSON.stringify(data))
      return data
    })
  }

  getLocalLibraryItemsInFolder(folderId) {
    return AbsDatabase.getLocalLibraryItemsInFolder({ folderId }).then((data) => {
      console.log('Loaded local library items in folder', JSON.stringify(data))
      if (data.localLibraryItems && typeof data.localLibraryItems == 'string') {
        return JSON.parse(data.localLibraryItems)
      }
      return data.localLibraryItems
    })
  }

  getLocalLibraryItems(mediaType = null) {
    return AbsDatabase.getLocalLibraryItems({ mediaType }).then((data) => {
      console.log('Loaded all local media items', JSON.stringify(data))
      if (data.localLibraryItems && typeof data.localLibraryItems == 'string') {
        return JSON.parse(data.localLibraryItems)
      }
      return data.localLibraryItems
    })
  }

  getLocalLibraryItem(id) {
    return AbsDatabase.getLocalLibraryItem({ id })
  }

  getLocalLibraryItemByLLId(libraryItemId) {
    return AbsDatabase.getLocalLibraryItemByLLId({ libraryItemId })
  }
}

export default ({ app, store }, inject) => {
  inject('db', new DbService())
}