import { registerPlugin, Capacitor, WebPlugin } from '@capacitor/core';

class DbWeb extends WebPlugin {
  constructor() {
    super()
  }

  async getDeviceData_WV() {
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

  async setCurrentServerConnectionConfig_WV(serverConnectionConfig) {
    var deviceData = await this.getDeviceData_WV()

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

  async removeServerConnectionConfig_WV(serverConnectionConfigCallObject) {
    var serverConnectionConfigId = serverConnectionConfigCallObject.serverConnectionConfigId
    var deviceData = await this.getDeviceData_WV()
    deviceData.serverConnectionConfigs = deviceData.serverConnectionConfigs.filter(ssc => ssc.id == serverConnectionConfigId)
    localStorage.setItem('device', JSON.stringify(deviceData))
  }

  logout_WV() {
    // Nothing to do on web
  }
}

const DbManager = registerPlugin('DbManager', {
  web: () => new DbWeb()
})

export { DbManager }