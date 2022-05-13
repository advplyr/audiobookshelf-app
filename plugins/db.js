import { AbsDatabase } from './capacitor/AbsDatabase'

class DbService {
  constructor() { }

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
    return AbsDatabase.getLocalFolders().then((data) => data.value).catch((error) => {
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
    return AbsDatabase.getLocalLibraryItemsInFolder({ folderId }).then((data) => data.value)
  }

  getLocalLibraryItems(mediaType = null) {
    return AbsDatabase.getLocalLibraryItems({ mediaType }).then((data) => data.value)
  }

  getLocalLibraryItem(id) {
    return AbsDatabase.getLocalLibraryItem({ id })
  }

  getLocalLibraryItemByLLId(libraryItemId) {
    return AbsDatabase.getLocalLibraryItemByLLId({ libraryItemId })
  }

  getAllLocalMediaProgress() {
    return AbsDatabase.getAllLocalMediaProgress().then((data) => data.value)
  }

  removeLocalMediaProgress(localMediaProgressId) {
    return AbsDatabase.removeLocalMediaProgress({ localMediaProgressId })
  }

  syncLocalMediaProgressWithServer() {
    return AbsDatabase.syncLocalMediaProgressWithServer()
  }

  updateLocalTrackOrder(payload) {
    return AbsDatabase.updateLocalTrackOrder(payload)
  }

  // input: { localMediaProgressId:String, isFinished:Boolean }
  updateLocalMediaProgressFinished(payload) {
    return AbsDatabase.updateLocalMediaProgressFinished(payload)
  }
}

export default ({ app, store }, inject) => {
  inject('db', new DbService())
}