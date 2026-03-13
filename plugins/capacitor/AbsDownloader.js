import { registerPlugin, WebPlugin } from '@capacitor/core'

class AbsDownloaderWeb extends WebPlugin {
  constructor() {
    super()
  }

  async cancelAllDownloads() {
    console.log('[AbsDownloaderWeb] cancelAllDownloads called')
    return Promise.resolve()
  }

  async retryDownloadQueue() {
    console.log('[AbsDownloaderWeb] retryDownloadQueue called')
    return Promise.resolve()
  }

  async clearIncompleteDownloads() {
    console.log('[AbsDownloaderWeb] clearIncompleteDownloads called')
    return Promise.resolve({ bytesFreed: 0, foldersDeleted: 0 })
  }

  async onServerConnected() {
    console.log('[AbsDownloaderWeb] onServerConnected called')
    return Promise.resolve()
  }
}

const AbsDownloader = registerPlugin('AbsDownloader', {
  web: () => new AbsDownloaderWeb()
})

export { AbsDownloader }
