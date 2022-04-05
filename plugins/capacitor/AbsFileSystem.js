import { registerPlugin, WebPlugin } from '@capacitor/core';

class AbsFileSystemWeb extends WebPlugin {
  constructor() {
    super()
  }
}

const AbsFileSystem = registerPlugin('AbsFileSystem', {
  web: () => new AbsFileSystemWeb()
})

export { AbsFileSystem }