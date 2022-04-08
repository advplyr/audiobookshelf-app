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
      localLibraryItemIdPlaying: null
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
      ssc.username = serverConnectionConfig.username
      localStorage.setItem('device', JSON.stringify(deviceData))
    } else {
      ssc = {
        id: encodeURIComponent(Buffer.from(`${serverConnectionConfig.address}@${serverConnectionConfig.username}`).toString('base64')),
        index: deviceData.serverConnectionConfigs.length,
        name: `${serverConnectionConfig.address} (${serverConnectionConfig.username})`,
        username: serverConnectionConfig.username,
        address: serverConnectionConfig.address,
        token: serverConnectionConfig.token
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
    deviceData.serverConnectionConfigs = deviceData.serverConnectionConfigs.filter(ssc => ssc.id == serverConnectionConfigId)
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
      folders: [
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
    return this.getLocalFolders().then((data) => data.folders[0])
  }
  async getLocalLibraryItems(payload) {
    return {
      localLibraryItems: [{
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
    return this.getLocalLibraryItems().then((data) => data.localLibraryItems[0])
  }
  async getLocalLibraryItemByLLId({ libraryItemId }) {
    return this.getLocalLibraryItems().then((data) => data.localLibraryItems.find(lli => lli.libraryItemId == libraryItemId))
  }
}

const AbsDatabase = registerPlugin('AbsDatabase', {
  web: () => new AbsDatabaseWeb()
})

export { AbsDatabase }