import { registerPlugin, Capacitor, WebPlugin } from '@capacitor/core';

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

  async setCurrentServerConnectionConfig(serverConnectionConfig) {
    var deviceData = await this.getDeviceData()

    var ssc = deviceData.serverConnectionConfigs.find(_ssc => _ssc.id == serverConnectionConfig.id)
    if (ssc) {
      deviceData.lastServerConnectionConfigId = ssc.id
      ssc.name = `${ssc.address} (${serverConnectionConfig.username})`
      ssc.token = serverConnectionConfig.token
      ssc.userId = serverConnectionConfig.userId
      ssc.username = serverConnectionConfig.username
      ssc.customHeaders = serverConnectionConfig.customHeaders || {}
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
        customHeaders: serverConnectionConfig.customHeaders || {}
      }
      deviceData.serverConnectionConfigs.push(ssc)
      deviceData.lastServerConnectionConfigId = ssc.id
      localStorage.setItem('device', JSON.stringify(deviceData))
    }
    return ssc
  }

  async removeServerConnectionConfig(serverConnectionConfigCallObject) {
    var serverConnectionConfigId = serverConnectionConfigCallObject.serverConnectionConfigId
    var deviceData = await this.getDeviceData()
    deviceData.serverConnectionConfigs = deviceData.serverConnectionConfigs.filter(ssc => ssc.id != serverConnectionConfigId)
    localStorage.setItem('device', JSON.stringify(deviceData))
  }

  async logout() {
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
      value: [{
        id: 'local_test',
        libraryItemId: 'test34',
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
      }]
    }
  }
  async getLocalLibraryItemsInFolder({ folderId }) {
    return this.getLocalLibraryItems()
  }
  async getLocalLibraryItem({ id }) {
    return this.getLocalLibraryItems().then((data) => data.value[0])
  }
  async getLocalLibraryItemByLLId({ libraryItemId }) {
    return this.getLocalLibraryItems().then((data) => data.value.find(lli => lli.libraryItemId == libraryItemId))
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
          finishedAt: null,
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

  async syncLocalMediaProgressWithServer() {
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
    var deviceData = await this.getDeviceData()
    deviceData.deviceSettings = payload
    localStorage.setItem('device', JSON.stringify(deviceData))
    return deviceData
  }
}

const AbsDatabase = registerPlugin('AbsDatabase', {
  web: () => new AbsDatabaseWeb()
})

export { AbsDatabase }