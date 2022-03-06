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
}

export default ({ app, store }, inject) => {
  inject('db', new DbService())
}