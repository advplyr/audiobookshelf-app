import { registerPlugin } from '@capacitor/core';

const DbManager = registerPlugin('DbManager');

class DbService {
  constructor() { }

  save(db, key, value) {
    return DbManager.saveFromWebview({ db, key, value }).then(() => {
      console.log('Saved data', db, key, JSON.stringify(value))
    }).catch((error) => {
      console.error('Failed to save data', error)
    })
  }

  load(db, key) {
    return DbManager.loadFromWebview({ db, key }).then((data) => {
      console.log('Loaded data', db, key, JSON.stringify(data))
      return data
    }).catch((error) => {
      console.error('Failed to load', error)
      return null
    })
  }

  loadFolders() {
    return DbManager.getLocalFolders_WV().then((data) => {
      console.log('Loaded local folders', JSON.stringify(data))
      if (data.folders && typeof data.folders == 'string') {
        return JSON.parse(data.folders)
      }
      return data.folders
    }).catch((error) => {
      console.error('Failed to load', error)
      return null
    })
  }

  getLocalFolder(folderId) {
    return DbManager.getLocalFolder_WV({ folderId }).then((data) => {
      console.log('Got local folder', JSON.stringify(data))
      return data
    })
  }

  getLocalMediaItemsInFolder(folderId) {
    return DbManager.getLocalMediaItemsInFolder_WV({ folderId }).then((data) => {
      console.log('Loaded local media items in folder', JSON.stringify(data))
      if (data.localMediaItems && typeof data.localMediaItems == 'string') {
        return JSON.parse(data.localMediaItems)
      }
      return data.localMediaItems
    })
  }
}

export default ({ app, store }, inject) => {
  inject('db', new DbService())
}