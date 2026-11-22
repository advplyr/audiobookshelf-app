import Vue from 'vue'
import { getIdentityKey, identityForItem, isIdentityForActiveAccount } from '@/plugins/bookmarks'

export const BOOKMARK_CONFLICT_POLICY = 'local-pending-wins'
export const namespaced = true

export const state = () => ({
  activeIdentity: null,
  cachedByIdentity: {},
  identitiesByKey: {},
  conflictPolicy: BOOKMARK_CONFLICT_POLICY,
  syncStatus: 'idle',
  syncError: null
})

export const getters = {
  getBookmarksForIdentity: (state) => (identity) => {
    const key = getIdentityKey(identity)
    return key ? state.cachedByIdentity[key]?.bookmarks || [] : []
  },
  hasPendingOperations: (state) => {
    return Object.values(state.cachedByIdentity).some((cache) => cache.pendingOperations?.length)
  },
  getConflictPolicy: (state) => state.conflictPolicy,
  getSyncStatus: (state) => state.syncStatus,
  getSyncError: (state) => state.syncError
}

function commitCache(commit, cache, identity) {
  commit('replaceLocalState', {
    libraryItemId: identity.libraryItemId,
    identity,
    bookmarks: cache.bookmarks,
    cache
  })
}

export const actions = {
  async loadForItem({ commit, dispatch, rootState }, { identity, serverBookmarks = null, sync = false } = {}) {
    if (!identity?.libraryItemId) return []
    const cache = await this.$bookmarks.load(identity, serverBookmarks)
    commitCache(commit, cache, identity)
    if (sync && rootState.networkConnected && isIdentityForActiveAccount(identity, this)) {
      dispatch('syncForIdentity', { identity }).catch((error) => console.error('[bookmarks] Background sync failed', error))
    }
    return cache.bookmarks
  },

  async createBookmark({ dispatch, state, rootState }, { identity: requestedIdentity, bookmark } = {}) {
    const identity = requestedIdentity || state.activeIdentity
    if (!identity || identity.libraryItemId !== bookmark?.libraryItemId) throw new Error('No active bookmark item')
    const cache = await this.$bookmarks.apply(identity, 'create', bookmark)
    this.commit('bookmarks/replaceLocalState', { libraryItemId: identity.libraryItemId, identity, bookmarks: cache.bookmarks, cache })
    if (rootState.networkConnected && isIdentityForActiveAccount(identity, this)) {
      dispatch('syncForIdentity', { identity }).catch((error) => console.error('[bookmarks] Create sync failed', error))
    }
    return cache.bookmarks
  },

  async updateBookmark({ dispatch, state, rootState }, { identity: requestedIdentity, bookmark } = {}) {
    const identity = requestedIdentity || state.activeIdentity
    if (!identity || identity.libraryItemId !== bookmark?.libraryItemId) throw new Error('No active bookmark item')
    const cache = await this.$bookmarks.apply(identity, 'update', bookmark)
    this.commit('bookmarks/replaceLocalState', { libraryItemId: identity.libraryItemId, identity, bookmarks: cache.bookmarks, cache })
    if (rootState.networkConnected && isIdentityForActiveAccount(identity, this)) {
      dispatch('syncForIdentity', { identity }).catch((error) => console.error('[bookmarks] Update sync failed', error))
    }
    return cache.bookmarks
  },

  async deleteBookmark({ dispatch, state, rootState }, { identity: requestedIdentity, libraryItemId, time }) {
    const identity = requestedIdentity || state.activeIdentity
    if (!identity || identity.libraryItemId !== libraryItemId) throw new Error('No active bookmark item')
    const cache = await this.$bookmarks.apply(identity, 'delete', time)
    this.commit('bookmarks/replaceLocalState', { libraryItemId, identity, bookmarks: cache.bookmarks, cache })
    if (rootState.networkConnected && isIdentityForActiveAccount(identity, this)) {
      dispatch('syncForIdentity', { identity }).catch((error) => console.error('[bookmarks] Delete sync failed', error))
    }
    return cache.bookmarks
  },

  async syncForIdentity({ commit }, { identity, serverBookmarks = null } = {}) {
    if (!identity?.libraryItemId) return []
    commit('setSyncStatus', { status: 'syncing', error: null })
    const result = await this.$bookmarks.sync(identity, { serverBookmarks })
    commitCache(commit, result.cache, identity)
    commit('setSyncStatus', { status: result.error ? 'error' : 'idle', error: result.error ? result.error.message : null })
    return result.cache.bookmarks
  },

  async syncActiveAccount({ state, dispatch, rootState }) {
    const activeConfig = rootState.user.serverConnectionConfig
    const activeUser = rootState.user.user
    if (!activeConfig?.id || !activeUser?.id || !rootState.networkConnected) return

    const persistedIdentities = await this.$localStore.getBookmarkIdentities()
    const identitiesByKey = new Map(Object.values(state.identitiesByKey).map((identity) => [getIdentityKey(identity), identity]))
    for (const identity of persistedIdentities) {
      const key = getIdentityKey(identity)
      if (key) identitiesByKey.set(key, { ...identity, serverConnectionConfig: activeConfig })
    }
    const identities = Array.from(identitiesByKey.values()).filter((identity) => isIdentityForActiveAccount(identity, this))
    if (!identities.length) return

    let serverBookmarks = null
    try {
      const response = await this.$nativeHttp.get('/api/me', {
        connectTimeout: 7000,
        readTimeout: 7000,
        serverConnectionConfig: activeConfig
      })
      const user = response?.user || response
      serverBookmarks = user?.bookmarks || response?.bookmarks || []
    } catch (error) {
      console.warn('[bookmarks] Failed to prefetch server bookmarks; pending operations will still be attempted', error)
    }

    // Conflict policy: an explicit local pending operation represents the newest
    // user intent on this device and overlays fetched server state until the
    // operation is acknowledged. Once the queue is empty, server state wins.
    for (const identity of identities) {
      await dispatch('syncForIdentity', { identity, serverBookmarks })
    }
  },

  async importServerBookmarks({ state, dispatch, rootState }, bookmarks) {
    if (!rootState.networkConnected) return
    const identities = Object.values(state.identitiesByKey).filter((identity) => isIdentityForActiveAccount(identity, this))
    for (const identity of identities) {
      await dispatch('loadForItem', { identity, serverBookmarks: bookmarks, sync: false })
    }
  },

  resolveIdentity({ rootState }, { libraryItemId, playbackSession } = {}) {
    return identityForItem(libraryItemId, playbackSession, this)
  }
}

export const mutations = {
  replaceLocalState(state, { identity, cache }) {
    const key = getIdentityKey(identity)
    if (!key) return
    Vue.set(state.cachedByIdentity, key, cache)
    Vue.set(state.identitiesByKey, key, identity)
    state.activeIdentity = identity
  },
  setSyncStatus(state, { status, error }) {
    state.syncStatus = status
    state.syncError = error || null
  }
}
