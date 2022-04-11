import { registerPlugin, WebPlugin } from '@capacitor/core';

class AbsFileSystemWeb extends WebPlugin {
  constructor() {
    super()
  }

  async selectFolder() { }
}

const AbsFileSystem = registerPlugin('AbsFileSystem', {
  web: () => new AbsFileSystemWeb()
})

export { AbsFileSystem }