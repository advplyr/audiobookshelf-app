import { registerPlugin, Capacitor, WebPlugin } from '@capacitor/core'

/**
 * @typedef {Object} ServerConnectionConfig
 * @property {string} id
 * @property {number} index
 * @property {string} name
 * @property {string} address
 * @property {string} version
 * @property {string} userId
 * @property {string} username
 * @property {string} token
 * @property {string} [refreshToken] - Only passed in when setting config, then stored in secure storage
 */

class AbsDatabaseWeb extends WebPlugin {
  constructor() {
    super()
  }

  async getDeviceData() {
    var dd = localStorage.getItem('device')
    if (dd) {
      return JSON.parse(dd)
    }
    const deviceData = {
      serverConnectionConfigs: [],
      lastServerConnectionConfigId: null,
      currentLocalPlaybackSession: null,
      deviceSettings: {}
    }
    return deviceData
  }

  /**
   *
   * @param {ServerConnectionConfig} serverConnectionConfig
   * @returns {Promise<ServerConnectionConfig>}
   */
  async setCurrentServerConnectionConfig(serverConnectionConfig) {
    var deviceData = await this.getDeviceData()

    var ssc = deviceData.serverConnectionConfigs.find((_ssc) => _ssc.id == serverConnectionConfig.id)
    if (ssc) {
      deviceData.lastServerConnectionConfigId = ssc.id
      ssc.name = `${ssc.address} (${serverConnectionConfig.username})`
      ssc.token = serverConnectionConfig.token
      ssc.userId = serverConnectionConfig.userId
      ssc.username = serverConnectionConfig.username
      ssc.version = serverConnectionConfig.version
      ssc.customHeaders = serverConnectionConfig.customHeaders || {}

      if (serverConnectionConfig.refreshToken) {
        console.log('[AbsDatabase] Updating refresh token...', serverConnectionConfig.refreshToken)
        // Only using local storage for web version that is only used for testing
        localStorage.setItem(`refresh_token_${ssc.id}`, serverConnectionConfig.refreshToken)
      }

      localStorage.setItem('device', JSON.stringify(deviceData))
    } else {
      ssc = {
        id: encodeURIComponent(Buffer.from(`${serverConnectionConfig.address}@${serverConnectionConfig.username}`).toString('base64')),
        index: deviceData.serverConnectionConfigs.length,
        name: `${serverConnectionConfig.address} (${serverConnectionConfig.username})`,
        userId: serverConnectionConfig.userId,
        username: serverConnectionConfig.username,
        address: serverConnectionConfig.address,
        token: serverConnectionConfig.token,
        version: serverConnectionConfig.version,
        customHeaders: serverConnectionConfig.customHeaders || {}
      }

      if (serverConnectionConfig.refreshToken) {
        console.log('[AbsDatabase] Setting refresh token...', serverConnectionConfig.refreshToken)
        // Only using local storage for web version that is only used for testing
        localStorage.setItem(`refresh_token_${ssc.id}`, serverConnectionConfig.refreshToken)
      }

      deviceData.serverConnectionConfigs.push(ssc)
      deviceData.lastServerConnectionConfigId = ssc.id
      localStorage.setItem('device', JSON.stringify(deviceData))
    }
    return ssc
  }

  async getRefreshToken({ serverConnectionConfigId }) {
    console.log('[AbsDatabase] Getting refresh token...', serverConnectionConfigId)
    const refreshToken = localStorage.getItem(`refresh_token_${serverConnectionConfigId}`)
    return refreshToken ? { refreshToken } : null
  }

  async clearRefreshToken({ serverConnectionConfigId }) {
    console.log('[AbsDatabase] Clearing refresh token...', serverConnectionConfigId)
    localStorage.removeItem(`refresh_token_${serverConnectionConfigId}`)
  }

  async removeServerConnectionConfig(serverConnectionConfigCallObject) {
    var serverConnectionConfigId = serverConnectionConfigCallObject.serverConnectionConfigId
    var deviceData = await this.getDeviceData()
    deviceData.serverConnectionConfigs = deviceData.serverConnectionConfigs.filter((ssc) => ssc.id != serverConnectionConfigId)
    localStorage.setItem('device', JSON.stringify(deviceData))
  }

  async logout() {
    console.log('[AbsDatabase] Logging out...')
    var deviceData = await this.getDeviceData()
    deviceData.lastServerConnectionConfigId = null
    localStorage.setItem('device', JSON.stringify(deviceData))
  }

  //
  // For testing on web
  //
  async getLocalFolders() {
    return {
      value: [
        {
          id: 'test1',
          name: 'Audiobooks',
          contentUrl: 'test',
          absolutePath: '/audiobooks',
          simplePath: 'audiobooks',
          storageType: 'primary',
          mediaType: 'book'
        }
      ]
    }
  }
  async getLocalFolder({ folderId }) {
    return this.getLocalFolders().then((data) => data.value[0])
  }
  async getLocalLibraryItems(payload) {
    return {
      value: [
        {
          id: 'local_test',
          libraryItemId: 'test34',
          serverAddress: 'https://abs.test.com',
          serverUserId: 'test56',
          folderId: 'test1',
          absolutePath: 'a',
          contentUrl: 'c',
          isInvalid: false,
          mediaType: 'book',
          media: {
            metadata: {
              title: 'Test Book',
              authorName: 'Test Author Name'
            },
            coverPath: null,
            tags: [],
            audioFiles: [],
            chapters: [],
            tracks: [
              {
                index: 1,
                startOffset: 0,
                duration: 10000,
                title: 'Track Title 1',
                contentUrl: 'test',
                mimeType: 'audio/mpeg',
                metadata: null,
                isLocal: true,
                localFileId: 'lf1',
                audioProbeResult: {}
              },
              {
                index: 2,
                startOffset: 0,
                duration: 15000,
                title: 'Track Title 2',
                contentUrl: 'test2',
                mimeType: 'audio/mpeg',
                metadata: null,
                isLocal: true,
                localFileId: 'lf2',
                audioProbeResult: {}
              },
              {
                index: 3,
                startOffset: 0,
                duration: 20000,
                title: 'Track Title 3',
                contentUrl: 'test3',
                mimeType: 'audio/mpeg',
                metadata: null,
                isLocal: true,
                localFileId: 'lf3',
                audioProbeResult: {}
              }
            ]
          },
          localFiles: [
            {
              id: 'lf1',
              filename: 'lf1.mp3',
              contentUrl: 'test',
              absolutePath: 'test',
              simplePath: 'test',
              mimeType: 'audio/mpeg',
              size: 39048290
            }
          ],
          coverContentUrl: null,
          coverAbsolutePath: null,
          isLocal: true
        }
      ]
    }
  }
  async getLocalLibraryItemsInFolder({ folderId }) {
    return this.getLocalLibraryItems()
  }
  async getLocalLibraryItem({ id }) {
    return this.getLocalLibraryItems().then((data) => data.value[0])
  }
  async getLocalLibraryItemByLId({ libraryItemId }) {
    return this.getLocalLibraryItems().then((data) => data.value.find((lli) => lli.libraryItemId == libraryItemId))
  }
  async getAllLocalMediaProgress() {
    return {
      value: [
        {
          id: 'local_test',
          localLibraryItemId: 'local_test',
          episodeId: null,
          duration: 100,
          progress: 0.5,
          currentTime: 50,
          isFinished: false,
          lastUpdate: 394089090,
          startedAt: 239048209,
          finishedAt: null
          // For local lib items from server to support server sync
          // var serverConnectionConfigId:String?,
          // var serverAddress:String?,
          // var serverUserId:String?,
          // var libraryItemId:String?
        }
      ]
    }
  }
  async removeLocalMediaProgress({ localMediaProgressId }) {
    return null
  }

  async syncLocalSessionsWithServer({ isFirstSync }) {
    return null
  }

  async syncServerMediaProgressWithLocalMediaProgress(payload) {
    return null
  }

  async updateLocalTrackOrder({ localLibraryItemId, tracks }) {
    return []
  }

  async updateLocalMediaProgressFinished(payload) {
    // { localLibraryItemId, localEpisodeId, isFinished }
    return null
  }

  async updateDeviceSettings(payload) {
    const deviceData = await this.getDeviceData()
    deviceData.deviceSettings = payload
    localStorage.setItem('device', JSON.stringify(deviceData))
    return deviceData
  }

  async getMediaItemHistory({ mediaId }) {
    console.log('Get media item history', mediaId)
    return {
      id: mediaId,
      mediaDisplayTitle: 'Test Book',
      libraryItemId: mediaId,
      episodeId: null,
      isLocal: false,
      serverConnectionConfigId: null,
      serverAddress: null,
      createdAt: Date.now(),
      events: [
        {
          name: 'Pause',
          type: 'Playback',
          description: null,
          currentTime: 81,
          serverSyncAttempted: true,
          serverSyncSuccess: true,
          serverSyncMessage: null,
          timestamp: Date.now() - 1000 * 60 * 22 + 13000 // 22 mins ago + 13s
        },
        {
          name: 'Play',
          type: 'Playback',
          description: null,
          currentTime: 68,
          serverSyncAttempted: false,
          serverSyncSuccess: null,
          serverSyncMessage: null,
          timestamp: Date.now() - 1000 * 60 * 22 // 22 mins ago
        },
        {
          name: 'Pause',
          type: 'Playback',
          description: null,
          currentTime: 68,
          serverSyncAttempted: true,
          serverSyncSuccess: false,
          serverSyncMessage: null,
          timestamp: Date.now() - 1000 * 60 * 60 + 58000 // 1 hour ago + 58s
        },
        {
          name: 'Save',
          type: 'Playback',
          description: null,
          currentTime: 55,
          serverSyncAttempted: true,
          serverSyncSuccess: true,
          serverSyncMessage: null,
          timestamp: Date.now() - 1000 * 60 * 60 + 45000 // 1 hour ago + 45s
        },
        {
          name: 'Save',
          type: 'Playback',
          description: null,
          currentTime: 40,
          serverSyncAttempted: true,
          serverSyncSuccess: true,
          serverSyncMessage: null,
          timestamp: Date.now() - 1000 * 60 * 60 + 30000 // 1 hour ago + 30s
        },
        {
          name: 'Save',
          type: 'Playback',
          description: null,
          currentTime: 25,
          serverSyncAttempted: true,
          serverSyncSuccess: true,
          serverSyncMessage: null,
          timestamp: Date.now() - 1000 * 60 * 60 + 15000 // 1 hour ago + 15s
        },
        {
          name: 'Play',
          type: 'Playback',
          description: null,
          currentTime: 10,
          serverSyncAttempted: false,
          serverSyncSuccess: null,
          serverSyncMessage: null,
          timestamp: Date.now() - 1000 * 60 * 60 // 1 hour ago
        },
        {
          name: 'Stop',
          type: 'Playback',
          description: null,
          currentTime: 10,
          serverSyncAttempted: true,
          serverSyncSuccess: true,
          serverSyncMessage: null,
          timestamp: Date.now() - 1000 * 60 * 60 * 25 + 10000 // 25 hours ago + 10s
        },
        {
          name: 'Seek',
          type: 'Playback',
          description: null,
          currentTime: 6,
          serverSyncAttempted: true,
          serverSyncSuccess: true,
          serverSyncMessage: null,
          timestamp: Date.now() - 1000 * 60 * 60 * 25 + 2000 // 25 hours ago + 2s
        },
        {
          name: 'Play',
          type: 'Playback',
          description: null,
          currentTime: 0,
          serverSyncAttempted: false,
          serverSyncSuccess: null,
          serverSyncMessage: null,
          timestamp: Date.now() - 1000 * 60 * 60 * 25 // 25 hours ago
        },
        {
          name: 'Play',
          type: 'Playback',
          description: null,
          currentTime: 0,
          serverSyncAttempted: false,
          serverSyncSuccess: null,
          serverSyncMessage: null,
          timestamp: Date.now() - 1000 * 60 * 60 * 50 // 50 hours ago
        }
      ]
    }
  }
}

const AbsDatabase = registerPlugin('AbsDatabase', {
  web: () => new AbsDatabaseWeb()
})

export { AbsDatabase }
