import { Capacitor } from '@capacitor/core';
import { CapacitorDataStorageSqlite } from 'capacitor-data-storage-sqlite';
import { Storage } from '@capacitor/storage'

class StoreService {
  store
  isService = false
  platform
  isOpen = false
  tableName = 'downloads'

  constructor() {
    this.init()
  }

  /**
   * Plugin Initialization
   */
  init() {
    this.platform = Capacitor.getPlatform()
    this.store = CapacitorDataStorageSqlite
    this.isService = true
    console.log('in init ', this.platform, this.isService)
  }

  /**
   * Open a Store
   * @param _dbName string optional
   * @param _table string optional
   * @param _encrypted boolean optional 
   * @param _mode string optional
   */
  async openStore(_dbName, _table, _encrypted, _mode) {
    if (this.isService && this.store != null) {
      const database = _dbName ? _dbName : "storage"
      const table = _table ? _table : "storage_table"
      const encrypted = _encrypted ? _encrypted : false
      const mode = _mode ? _mode : "no-encryption"

      this.isOpen = false
      try {
        await this.store.openStore({ database, table, encrypted, mode })
        // return Promise.resolve()
        this.isOpen = true
        return true
      } catch (err) {
        // return Promise.reject(err)
        return false
      }
    } else {
      // return Promise.reject(new Error("openStore: Store not opened"))
      return false
    }
  }

  /**
   * Close a store
   * @param dbName 
   * @returns 
   */
  async closeStore(dbName) {
    if (this.isService && this.store != null) {
      try {
        await this.store.closeStore({ database: dbName })
        return Promise.resolve()
      } catch (err) {
        return Promise.reject(err)
      }
    } else {
      return Promise.reject(new Error("close: Store not opened"))
    }
  }

  /**
   * Check if a store is opened
   * @param dbName 
   * @returns 
   */
  async isStoreOpen(dbName) {
    if (this.isService && this.store != null) {
      try {
        const ret = await this.store.isStoreOpen({ database: dbName })
        return Promise.resolve(ret)
      } catch (err) {
        return Promise.reject(err)
      }
    } else {
      return Promise.reject(new Error("isStoreOpen: Store not opened"))
    }
  }
  /**
   * Check if a store already exists
   * @param dbName
   * @returns 
   */
  async isStoreExists(dbName) {
    if (this.isService && this.store != null) {
      try {
        const ret = await this.store.isStoreExists({ database: dbName })
        return Promise.resolve(ret)
      } catch (err) {
        return Promise.reject(err)
      }
    } else {
      return Promise.reject(new Error("isStoreExists: Store not opened"))
    }
  }

  /**
   * Create/Set a Table
   * @param table string
   */
  async setTable(table) {
    if (this.isService && this.store != null) {
      try {
        await this.store.setTable({ table })
        return Promise.resolve()
      } catch (err) {
        return Promise.reject(err)
      }
    } else {
      return Promise.reject(new Error("setTable: Store not opened"))
    }
  }

  /**
   * Set of Key
   * @param key string 
   * @param value string
   */
  async setItem(key, value) {
    if (this.isService && this.store != null) {
      if (key.length > 0) {
        try {
          await this.store.set({ key, value });
          return Promise.resolve();
        } catch (err) {
          return Promise.reject(err);
        }
      } else {
        return Promise.reject(new Error("setItem: Must give a key"));
      }
    } else {
      return Promise.reject(new Error("setItem: Store not opened"));
    }
  }
  /**
   * Get the Value for a given Key
   * @param key string 
   */
  async getItem(key) {
    if (this.isService && this.store != null) {
      if (key.length > 0) {
        try {
          const { value } = await this.store.get({ key });
          console.log("in getItem value ", value)
          return Promise.resolve(value);
        } catch (err) {
          console.error(`in getItem key: ${key} err: ${JSON.stringify(err)}`)
          return Promise.reject(err);
        }
      } else {
        return Promise.reject(new Error("getItem: Must give a key"));
      }
    } else {
      return Promise.reject(new Error("getItem: Store not opened"));
    }

  }
  async isKey(key) {
    if (this.isService && this.store != null) {
      if (key.length > 0) {
        try {
          const { result } = await this.store.iskey({ key });
          return Promise.resolve(result);
        } catch (err) {
          return Promise.reject(err);
        }
      } else {
        return Promise.reject(new Error("isKey: Must give a key"));
      }
    } else {
      return Promise.reject(new Error("isKey: Store not opened"));
    }

  }

  async getAllKeys() {
    if (this.isService && this.store != null) {
      try {
        const { keys } = await this.store.keys();
        return Promise.resolve(keys);
      } catch (err) {
        return Promise.reject(err);
      }
    } else {
      return Promise.reject(new Error("getAllKeys: Store not opened"));
    }
  }
  async getAllValues() {
    if (this.isService && this.store != null) {
      try {
        const { values } = await this.store.values();
        return Promise.resolve(values);
      } catch (err) {
        return Promise.reject(err);
      }
    } else {
      return Promise.reject(new Error("getAllValues: Store not opened"));
    }
  }
  async getFilterValues(filter) {
    if (this.isService && this.store != null) {
      try {
        const { values } = await this.store.filtervalues({ filter });
        return Promise.resolve(values);
      } catch (err) {
        return Promise.reject(err);
      }
    } else {
      return Promise.reject(new Error("getFilterValues: Store not opened"));
    }
  }
  async getAllKeysValues() {
    if (this.isService && this.store != null) {
      try {
        const { keysvalues } = await this.store.keysvalues();
        return Promise.resolve(keysvalues);
      } catch (err) {
        return Promise.reject(err);
      }
    } else {
      return Promise.reject(new Error("getAllKeysValues: Store not opened"));
    }
  }

  async removeItem(key) {
    if (this.isService && this.store != null) {
      if (key.length > 0) {
        try {
          await this.store.remove({ key });
          return Promise.resolve();
        } catch (err) {
          return Promise.reject(err);
        }
      } else {
        return Promise.reject(new Error("removeItem: Must give a key"));
      }
    } else {
      return Promise.reject(new Error("removeItem: Store not opened"));
    }
  }

  async clear() {
    if (this.isService && this.store != null) {
      try {
        await this.store.clear();
        return Promise.resolve();
      } catch (err) {
        return Promise.reject(err.message);
      }
    } else {
      return Promise.reject(new Error("clear: Store not opened"));
    }
  }

  async deleteStore(_dbName) {
    const database = _dbName ? _dbName : "storage"

    if (this.isService && this.store != null) {
      try {
        await this.store.deleteStore({ database })
        return Promise.resolve();
      } catch (err) {
        return Promise.reject(err.message)
      }
    } else {
      return Promise.reject(new Error("deleteStore: Store not opened"));
    }
  }
  async isTable(table) {
    if (this.isService && this.store != null) {
      if (table.length > 0) {
        try {
          const { result } = await this.store.isTable({ table });
          return Promise.resolve(result);
        } catch (err) {
          return Promise.reject(err);
        }
      } else {
        return Promise.reject(new Error("isTable: Must give a table"));
      }
    } else {
      return Promise.reject(new Error("isTable: Store not opened"));
    }
  }
  async getAllTables() {
    if (this.isService && this.store != null) {
      try {
        const { tables } = await this.store.tables();
        return Promise.resolve(tables);
      } catch (err) {
        return Promise.reject(err);
      }
    } else {
      return Promise.reject(new Error("getAllTables: Store not opened"));
    }
  }
  async deleteTable(table) {
    if (this.isService && this.store != null) {
      if (table.length > 0) {
        try {
          await this.store.deleteTable({ table });
          return Promise.resolve();
        } catch (err) {
          return Promise.reject(err);
        }
      } else {
        return Promise.reject(new Error("deleteTable: Must give a table"));
      }
    } else {
      return Promise.reject(new Error("deleteTable: Store not opened"));
    }
  }

  async getDownload(id) {
    if (!this.isOpen) {
      var success = await this.openStore('storage', this.tableName)
      if (!success) {
        console.error('Store failed to open')
        return null
      }
    }
    try {
      var value = await this.getItem(id)
      return JSON.parse(value)
    } catch (error) {
      console.error('Failed to get download from store', error)
      return null
    }
  }

  async setDownload(download) {
    if (!this.isOpen) {
      var success = await this.openStore('storage', this.tableName)
      if (!success) {
        console.error('Store failed to open')
        return false
      }
    }

    try {
      await this.setItem(download.id, JSON.stringify(download))
      console.log(`[STORE] Set Download ${download.id}`)
      return true
    } catch (error) {
      console.error('Failed to set download in store', error)
      return false
    }
  }

  async removeDownload(id) {
    if (!this.isOpen) {
      var success = await this.openStore('storage', this.tableName)
      if (!success) {
        console.error('Store failed to open')
        return false
      }
    }

    try {
      await this.removeItem(id)
      console.log(`[STORE] Removed download ${id}`)
      return true
    } catch (error) {
      console.error('Failed to remove download in store', error)
      return false
    }
  }

  async getAllDownloads() {
    if (!this.isOpen) {
      var success = await this.openStore('storage', this.tableName)
      if (!success) {
        console.error('Store failed to open')
        return []
      }
    }
    var keysvalues = await this.getAllKeysValues()
    var downloads = []

    for (let i = 0; i < keysvalues.length; i++) {
      try {
        var download = JSON.parse(keysvalues[i].value)
        downloads.push(download)
      } catch (error) {
        console.error('Failed to parse download', error)
        await this.removeItem(keysvalues[i].key)
      }
    }

    return downloads
  }
}

class LocalStorage {
  constructor(vuexStore) {
    this.vuexStore = vuexStore

    this.userAudiobooksLoaded = false
    this.userAudiobooks = {}
  }

  async getMostRecentUserAudiobook(audiobookId) {
    if (!this.userAudiobooksLoaded) {
      await this.loadUserAudiobooks()
    }
    var local = this.getUserAudiobook(audiobookId)
    var server = this.vuexStore.getters['user/getUserAudiobook'](audiobookId)

    if (local && server) {
      if (local.lastUpdate > server.lastUpdate) {
        console.log('[LocalStorage] Most recent user audiobook is from LOCAL')
        return local
      }
      console.log('[LocalStorage] Most recent user audiobook is from SERVER')
      return server
    } else if (local) {
      console.log('[LocalStorage] Most recent user audiobook is from LOCAL')
      return local
    } else if (server) {
      console.log('[LocalStorage] Most recent user audiobook is from SERVER')
      return server
    }
    return null
  }

  async loadUserAudiobooks() {
    try {
      var val = (await Storage.get({ key: 'userAudiobooks' }) || {}).value || null
      this.userAudiobooks = val ? JSON.parse(val) : {}
      this.userAudiobooksLoaded = true
      this.vuexStore.commit('user/setLocalUserAudiobooks', this.userAudiobooks)
      console.log('[LocalStorage] Loaded Local USER Audiobooks ' + JSON.stringify(this.userAudiobooks))
    } catch (error) {
      console.error('[LocalStorage] Failed to load user audiobooks', error)
    }
  }

  async saveUserAudiobooks() {
    try {
      await Storage.set({ key: 'userAudiobooks', value: JSON.stringify(this.userAudiobooks) })
    } catch (error) {
      console.error('[LocalStorage] Failed to set user audiobooks', error)
    }
  }

  async setAllAudiobookProgress(progresses) {
    this.userAudiobooks = progresses
    await this.saveUserAudiobooks()
    this.vuexStore.commit('user/setLocalUserAudiobooks', this.userAudiobooks)
  }

  async updateUserAudiobookProgress(progressPayload) {
    this.userAudiobooks[progressPayload.audiobookId] = {
      ...progressPayload
    }
    console.log('[LocalStorage] Updated User Audiobook Progress ' + progressPayload.audiobookId)
    await this.saveUserAudiobooks()
    this.vuexStore.commit('user/setLocalUserAudiobooks', this.userAudiobooks)
  }

  async removeAudiobookProgress(audiobookId) {
    if (!this.userAudiobooks[audiobookId]) return
    delete this.userAudiobooks[audiobookId]
    await this.saveUserAudiobooks()
    this.vuexStore.commit('user/setLocalUserAudiobooks', this.userAudiobooks)
  }

  getUserAudiobook(audiobookId) {
    return this.userAudiobooks[audiobookId] || null
  }

  async setToken(token) {
    try {
      if (token) {
        await Storage.set({ key: 'token', value: token })
      } else {
        await Storage.remove({ key: 'token' })
      }
    } catch (error) {
      console.error('[LocalStorage] Failed to set token', error)
    }
  }

  async getToken() {
    try {
      return (await Storage.get({ key: 'token' }) || {}).value || null
    } catch (error) {
      console.error('[LocalStorage] Failed to get token', error)
      return null
    }
  }

  async getServerUrl() {
    try {
      return (await Storage.get({ key: 'serverUrl' }) || {}).value || null
    } catch (error) {
      console.error('[LocalStorage] Failed to get serverUrl', error)
      return null
    }
  }

  async setUserSettings(settings) {
    try {
      await Storage.set({ key: 'userSettings', value: JSON.stringify(settings) })
    } catch (error) {
      console.error('[LocalStorage] Failed to update user settings', error)
    }
  }

  async getUserSettings() {
    try {
      var settingsObj = await Storage.get({ key: 'userSettings' }) || {}
      return settingsObj.value ? JSON.parse(settingsObj.value) : null
    } catch (error) {
      console.error('[LocalStorage] Failed to get user settings', error)
      return null
    }
  }

  async setCurrent(current) {
    try {
      if (current) {
        await Storage.set({ key: 'current', value: JSON.stringify(current) })
      } else {
        await Storage.remove({ key: 'current' })
      }
    } catch (error) {
      console.error('[LocalStorage] Failed to set current', error)
    }
  }

  async getCurrent() {
    try {
      var currentObj = await Storage.get({ key: 'current' }) || {}
      return currentObj.value ? JSON.parse(currentObj.value) : null
    } catch (error) {
      console.error('[LocalStorage] Failed to get current', error)
      return null
    }
  }
}

export default ({ app, store }, inject) => {
  inject('sqlStore', new StoreService())
  inject('localStore', new LocalStorage(store))
}