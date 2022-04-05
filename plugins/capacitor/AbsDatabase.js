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
}

const AbsDatabase = registerPlugin('AbsDatabase', {
  web: () => new AbsDatabaseWeb()
})

export { AbsDatabase }