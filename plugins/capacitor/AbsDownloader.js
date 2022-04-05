import { registerPlugin, WebPlugin } from '@capacitor/core';

class AbsDownloaderWeb extends WebPlugin {
  constructor() {
    super()
  }
}

const AbsDownloader = registerPlugin('AbsDownloader', {
  web: () => new AbsDownloaderWeb()
})

export { AbsDownloader }