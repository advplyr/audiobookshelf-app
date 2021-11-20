import { Capacitor } from '@capacitor/core';
import { CapacitorDataStorageSqlite } from 'capacitor-data-storage-sqlite';

class StoreService {
  store
  platform
  isOpen = false

  constructor(vuexStore) {
    this.vuexStore = vuexStore
    this.currentTable = null
    this.init()
  }

  /**
   * Plugin Initialization
   */
  init() {
    this.platform = Capacitor.getPlatform()
    this.store = CapacitorDataStorageSqlite
    console.log('in init ', this.platform)
  }

  /**
   * Open a Store
   * @param _dbName string optional
   * @param _table string optional
   * @param _encrypted boolean optional 
   * @param _mode string optional
   */
  async openStore(_dbName, _table, _encrypted, _mode) {
    if (this.store != null) {
      const database = _dbName ? _dbName : "storage"
      const table = _table ? _table : "storage_table"
      const encrypted = _encrypted ? _encrypted : false
      const mode = _mode ? _mode : "no-encryption"

      this.isOpen = false
      try {
        await this.store.openStore({ database, table, encrypted, mode })
        // return Promise.resolve()
        this.currentTable = table
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
    if (this.store != null) {
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
    if (this.store != null) {
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
    if (this.store != null) {
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
    if (this.store != null) {
      try {
        await this.store.setTable({ table })
        this.currentTable = table
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
    if (this.store != null) {
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
    if (this.store != null) {
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
    if (this.store != null) {
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

  async getAllKeysValues() {
    if (this.store != null) {
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
    if (this.store != null) {
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
    if (this.store != null) {
      try {
        await this.store.clear()
        return true
      } catch (err) {
        console.error('[SqlStore] Failed to clear table', err.message)
        return false
      }
    } else {
      console.error('[SqlStore] Clear: Store not opened')
      return false
    }
  }
  async deleteStore(_dbName) {
    const database = _dbName ? _dbName : "storage"

    if (this.store != null) {
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
    if (this.store != null) {
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
    if (this.store != null) {
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

  async ensureTable(tablename) {
    if (!this.isOpen) {
      var success = await this.openStore('storage', tablename)
      if (!success) {
        console.error('Store failed to open')
        return false
      }
    }
    try {
      await this.setTable(tablename)
      console.log('[SqlStore] Set Table ' + this.currentTable)
      return true
    } catch (error) {
      console.error('Failed to set table', error)
      return false
    }
  }

  async setDownload(download) {
    if (!download) return false
    if (!(await this.ensureTable('downloads'))) {
      return false
    }
    if (!download.id) {
      console.error(`[SqlStore] set download invalid download ${download ? JSON.stringify(download) : 'null'}`)
      return false
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
    if (!id) return false
    if (!(await this.ensureTable('downloads'))) {
      return false
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
    if (!(await this.ensureTable('downloads'))) {
      return false
    }

    var keysvalues = await this.getAllKeysValues()
    var downloads = []

    for (let i = 0; i < keysvalues.length; i++) {
      try {
        var download = JSON.parse(keysvalues[i].value)
        if (!download.id) {
          console.error('[SqlStore] Removing invalid download')
          await this.removeItem(keysvalues[i].key)
        } else {
          downloads.push(download)
        }
      } catch (error) {
        console.error('Failed to parse download', error)
        await this.removeItem(keysvalues[i].key)
      }
    }

    return downloads
  }

  async setUserAudiobookData(userAudiobookData) {
    if (!(await this.ensureTable('userAudiobookData'))) {
      return false
    }

    try {
      await this.setItem(userAudiobookData.audiobookId, JSON.stringify(userAudiobookData))
      this.vuexStore.commit('user/setUserAudiobookData', userAudiobookData)

      console.log(`[STORE] Set UserAudiobookData ${userAudiobookData.audiobookId}`)
      return true
    } catch (error) {
      console.error('Failed to set UserAudiobookData in store', error)
      return false
    }
  }

  async removeUserAudiobookData(audiobookId) {
    if (!(await this.ensureTable('userAudiobookData'))) {
      return false
    }

    try {
      await this.removeItem(audiobookId)
      this.vuexStore.commit('user/removeUserAudiobookData', audiobookId)

      console.log(`[STORE] Removed userAudiobookData ${id}`)
      return true
    } catch (error) {
      console.error('Failed to remove userAudiobookData in store', error)
      return false
    }
  }

  async getAllUserAudiobookData() {
    if (!(await this.ensureTable('userAudiobookData'))) {
      return false
    }

    var keysvalues = await this.getAllKeysValues()
    var data = []

    for (let i = 0; i < keysvalues.length; i++) {
      try {
        var abdata = JSON.parse(keysvalues[i].value)
        if (!abdata.audiobookId) {
          console.error('[SqlStore] Removing invalid user audiobook data')
          await this.removeItem(keysvalues[i].key)
        } else {
          data.push(abdata)
        }
      } catch (error) {
        console.error('Failed to parse userAudiobookData', error)
        await this.removeItem(keysvalues[i].key)
      }
    }
    return data
  }

  async setAllUserAudiobookData(userAbData) {
    if (!(await this.ensureTable('userAudiobookData'))) {
      return false
    }

    console.log('[SqlStore] Setting all user audiobook data ' + userAbData.length)

    var success = await this.clear()
    if (!success) {
      console.error('[SqlStore] Did not clear old user ab data, overwriting')
    }

    for (let i = 0; i < userAbData.length; i++) {
      try {
        var abdata = userAbData[i]
        await this.setItem(abdata.audiobookId, JSON.stringify(abdata))
      } catch (error) {
        console.error('[SqlStore] Failed to set userAudiobookData', error)
      }
    }

    this.vuexStore.commit('user/setAllUserAudiobookData', userAbData)
  }
}

export default ({ app, store }, inject) => {
  inject('sqlStore', new StoreService(store))
}