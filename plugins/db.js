import { AbsDatabase } from './capacitor/AbsDatabase'

class DbService {
  constructor() {}

  getDeviceData() {
    return AbsDatabase.getDeviceData().then((data) => {
      console.log('Loaded device data', JSON.stringify(data))
      return data
    })
  }

  /**
   * Retrieves refresh token from secure storage
   * @param {string} serverConnectionConfigId
   * @return {Promise<string|null>}
   */
  async getRefreshToken(serverConnectionConfigId) {
    const refreshTokenData = await AbsDatabase.getRefreshToken({ serverConnectionConfigId })
    return refreshTokenData?.refreshToken
  }

  /**
   * Clears refresh token from secure storage
   * @param {string} serverConnectionConfigId
   * @returns {Promise<boolean>}
   */
  async clearRefreshToken(serverConnectionConfigId) {
    const result = await AbsDatabase.clearRefreshToken({ serverConnectionConfigId })
    return !!result?.success
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
    return AbsDatabase.getLocalFolders()
      .then((data) => data.value)
      .catch((error) => {
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

  saveLocalFolder(folder) {
    return AbsDatabase.saveLocalFolder({ folder: JSON.stringify(folder) })
  }

  removeLocalFolder(folderId) {
    return AbsDatabase.removeLocalFolder({ folderId })
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

  getLocalLibraryItemByLId(libraryItemId) {
    return AbsDatabase.getLocalLibraryItemByLId({ libraryItemId })
  }

  getAllLocalMediaProgress() {
    return AbsDatabase.getAllLocalMediaProgress().then((data) => data.value)
  }

  getLocalMediaProgressForServerItem(payload) {
    return AbsDatabase.getLocalMediaProgressForServerItem(payload)
  }

  removeLocalMediaProgress(localMediaProgressId) {
    return AbsDatabase.removeLocalMediaProgress({ localMediaProgressId })
  }

  syncLocalSessionsWithServer(isFirstSync) {
    return AbsDatabase.syncLocalSessionsWithServer({ isFirstSync })
  }

  syncServerMediaProgressWithLocalMediaProgress(payload) {
    return AbsDatabase.syncServerMediaProgressWithLocalMediaProgress(payload)
  }

  updateLocalTrackOrder(payload) {
    return AbsDatabase.updateLocalTrackOrder(payload)
  }

  // input: { localLibraryItemId:String, localEpisodeId:String, isFinished:Boolean }
  updateLocalMediaProgressFinished(payload) {
    return AbsDatabase.updateLocalMediaProgressFinished(payload)
  }

  // input: { localLibraryItemId:String, ebookLocation:String, ebookProgress:Double }
  updateLocalEbookProgress(payload) {
    return AbsDatabase.updateLocalEbookProgress(payload)
  }

  updateDeviceSettings(payload) {
    return AbsDatabase.updateDeviceSettings(payload)
  }

  getMediaItemHistory(mediaId) {
    return AbsDatabase.getMediaItemHistory({ mediaId })
  }
}

export default ({ app, store }, inject) => {
  inject('db', new DbService())

  // Listen for token refresh events from native app
  AbsDatabase.addListener('onTokenRefresh', (data) => {
    console.log('[db] onTokenRefresh', data)
    store.commit('user/setAccessToken', data.accessToken)
  })

  // Listen for token refresh failure events from native app
  AbsDatabase.addListener('onTokenRefreshFailure', async (data) => {
    console.log('[db] onTokenRefreshFailure', data)
    // Clear store and redirect to login page
    await store.dispatch('user/logout')
    if (window.location.pathname !== '/connect') {
      window.location.href = '/connect'
    }
  })
}
