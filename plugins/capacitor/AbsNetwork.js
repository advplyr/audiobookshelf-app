import { registerPlugin, WebPlugin } from '@capacitor/core'

class AbsNetworkWeb extends WebPlugin {
  async getCurrentWifiSsid() {
    return { ssid: null }
  }
}

const AbsNetwork = registerPlugin('AbsNetwork', {
  web: () => new AbsNetworkWeb()
})

export { AbsNetwork }
