import { createEmptyBookmarkCache, normalizeBookmarkCache } from '@/plugins/localStore'

const MAX_SYNC_ATTEMPTS = 8
const RETRY_BASE_DELAY_MS = 30000
const RETRY_MAX_DELAY_MS = 6 * 60 * 60 * 1000

export function normalizeTime(time) {
  const number = Number(time)
  return Number.isFinite(number) ? Math.floor(number) : null
}

export function bookmarkKey(time) {
  const normalizedTime = normalizeTime(time)
  return normalizedTime === null ? null : String(normalizedTime)
}

export function normalizeIdentity(identity = {}) {
  const localItem = identity.localLibraryItem || identity.localItem || {}
  const serverConfig = identity.serverConnectionConfig || {}
  return {
    serverConnectionConfigId: identity.serverConnectionConfigId || identity.serverConfigId || localItem.serverConnectionConfigId || serverConfig.id || null,
    serverUserId: identity.serverUserId || identity.userId || localItem.serverUserId || identity.user?.id || null,
    libraryItemId: identity.libraryItemId || identity.serverLibraryItemId || localItem.libraryItemId || null,
    serverConnectionConfig: identity.serverConnectionConfig || null
  }
}

export function getIdentityKey(identity) {
  const normalized = normalizeIdentity(identity)
  if (!normalized.serverConnectionConfigId || !normalized.serverUserId || !normalized.libraryItemId) return null
  return [normalized.serverConnectionConfigId, normalized.serverUserId, normalized.libraryItemId].join(':')
}

export function identityForItem(libraryItemId, playbackSession, store) {
  const session = playbackSession || {}
  const localItem = session.localLibraryItem || {}
  const storeUser = store?.state?.user?.user
  const storeConfig = store?.state?.user?.serverConnectionConfig
  const identity = normalizeIdentity({
    libraryItemId: libraryItemId || session.libraryItemId || localItem.libraryItemId,
    serverConnectionConfigId: session.serverConnectionConfigId || localItem.serverConnectionConfigId || storeConfig?.id,
    serverUserId: session.userId || session.serverUserId || localItem.serverUserId || storeUser?.id,
    serverConnectionConfig: storeConfig
  })
  if (!identity.libraryItemId || !identity.serverConnectionConfigId || !identity.serverUserId) return null
  return identity
}

export function isIdentityForActiveAccount(identity, store) {
  const normalized = normalizeIdentity(identity)
  const activeConfig = store?.state?.user?.serverConnectionConfig
  const activeUser = store?.state?.user?.user
  return !!activeConfig?.id && !!activeUser?.id && normalized.serverConnectionConfigId === activeConfig.id && normalized.serverUserId === activeUser.id
}

export function normalizeBookmark(bookmark, libraryItemId, localUpdatedAt = 0) {
  const time = normalizeTime(bookmark?.time)
  if (time === null) return null
  return {
    ...bookmark,
    libraryItemId: bookmark.libraryItemId || libraryItemId,
    time,
    title: bookmark.title == null ? '' : String(bookmark.title),
    ...(localUpdatedAt ? { localUpdatedAt } : {})
  }
}

export function normalizeServerBookmarks(bookmarks, libraryItemId) {
  const byKey = new Map()
  for (const bookmark of Array.isArray(bookmarks) ? bookmarks : []) {
    if (bookmark?.libraryItemId && bookmark.libraryItemId !== libraryItemId) continue
    const normalized = normalizeBookmark(bookmark, libraryItemId)
    if (normalized) byKey.set(bookmarkKey(normalized.time), normalized)
  }
  return Array.from(byKey.values()).sort((a, b) => a.time - b.time)
}

function operationPriority(operation) {
  return { delete: 0, create: 1, update: 2 }[operation.type] ?? 3
}

export function mergeServerBookmarks(cache, serverBookmarks, identity) {
  const normalizedIdentity = normalizeIdentity(identity)
  const normalizedCache = normalizeBookmarkCache(cache, normalizedIdentity)
  const byKey = new Map(normalizeServerBookmarks(serverBookmarks, normalizedIdentity.libraryItemId).map((bookmark) => [bookmarkKey(bookmark.time), bookmark]))
  const operations = [...normalizedCache.pendingOperations].sort((a, b) => operationPriority(a) - operationPriority(b))

  for (const operation of operations) {
    const key = String(operation.bookmarkKey)
    if (operation.type === 'delete') byKey.delete(key)
    else if (operation.bookmark) {
      const bookmark = normalizeBookmark(operation.bookmark, normalizedIdentity.libraryItemId, operation.updatedAt || operation.createdAt || 0)
      if (bookmark) byKey.set(key, bookmark)
    }
  }

  for (const key of Object.keys(normalizedCache.tombstones)) byKey.delete(key)

  return {
    ...normalizedCache,
    serverConnectionConfigId: normalizedIdentity.serverConnectionConfigId,
    serverUserId: normalizedIdentity.serverUserId,
    libraryItemId: normalizedIdentity.libraryItemId,
    bookmarks: Array.from(byKey.values()).sort((a, b) => a.time - b.time)
  }
}

function createOperation(type, bookmarkKeyValue, bookmark, now) {
  return {
    id: 'bookmark-' + now + '-' + Math.random().toString(36).slice(2, 10),
    type,
    bookmarkKey: String(bookmarkKeyValue),
    ...(bookmark ? { bookmark } : {}),
    createdAt: now,
    updatedAt: now,
    attempts: 0,
    nextAttemptAt: 0,
    permanentFailure: false,
    lastError: null
  }
}

function replaceOperation(cache, operation) {
  const pendingOperations = cache.pendingOperations.filter((candidate) => candidate.bookmarkKey !== operation.bookmarkKey)
  pendingOperations.push(operation)
  return { ...cache, pendingOperations }
}

function bookmarksEquivalent(serverBookmark, pendingBookmark, libraryItemId) {
  const server = normalizeBookmark(serverBookmark, libraryItemId)
  const pending = normalizeBookmark(pendingBookmark, libraryItemId)
  return !!server && !!pending && server.libraryItemId === pending.libraryItemId && server.time === pending.time && server.title === pending.title
}

function errorStatus(error) {
  return Number(error?.status || error?.response?.status) || null
}

function isPermanentFailure(error) {
  const status = errorStatus(error)
  return status >= 400 && status < 500 && ![408, 409, 425, 429].includes(status)
}

function retryDelay(attempts) {
  return Math.min(RETRY_BASE_DELAY_MS * (2 ** Math.max(0, attempts - 1)), RETRY_MAX_DELAY_MS)
}

function canAttempt(operation, now = Date.now()) {
  return !operation.permanentFailure && (Number(operation.attempts) || 0) < MAX_SYNC_ATTEMPTS && (Number(operation.nextAttemptAt) || 0) <= now
}

export class BookmarkService {
  constructor({ localStore, nativeHttp, store }) {
    this.localStore = localStore
    this.nativeHttp = nativeHttp
    this.store = store
    this.operationLocks = new Map()
  }

  _identity(identity) {
    return normalizeIdentity(identity)
  }

  _options(identity) {
    const options = { connectTimeout: 7000, readTimeout: 7000 }
    if (identity.serverConnectionConfig) options.serverConnectionConfig = identity.serverConnectionConfig
    return options
  }

  async getCache(identity) {
    return this.localStore.getBookmarkCache(this._identity(identity))
  }

  async load(identity, serverBookmarks = null) {
    const normalizedIdentity = this._identity(identity)
    let cache = await this.getCache(normalizedIdentity)
    if (Array.isArray(serverBookmarks)) {
      cache = mergeServerBookmarks(cache, serverBookmarks, normalizedIdentity)
      await this.localStore.setBookmarkCache(normalizedIdentity, cache)
    }
    return cache
  }

  async _applyUnlocked(identity, type, bookmarkOrTime) {
    const normalizedIdentity = this._identity(identity)
    const now = Date.now()
    let cache = await this.getCache(normalizedIdentity)

    if (type === 'create' || type === 'update') {
      const bookmark = normalizeBookmark(bookmarkOrTime, normalizedIdentity.libraryItemId, now)
      if (!bookmark) throw new Error('Cannot ' + type + ' a bookmark without a valid time')
      const key = bookmarkKey(bookmark.time)
      cache = { ...cache, bookmarks: [...cache.bookmarks.filter((candidate) => bookmarkKey(candidate.time) !== key), bookmark], tombstones: { ...cache.tombstones } }
      delete cache.tombstones[key]
      const existing = cache.pendingOperations.find((operation) => operation.bookmarkKey === key)
      if (type === 'create' && (existing?.type === 'create' || existing?.type === 'update')) {
        cache = replaceOperation(cache, { ...existing, type: 'create', bookmark, updatedAt: now, attempts: 0, nextAttemptAt: 0, permanentFailure: false, lastError: null })
      } else if (type === 'update' && existing?.type === 'create') {
        cache = replaceOperation(cache, { ...existing, bookmark: { ...existing.bookmark, ...bookmark }, updatedAt: now, attempts: 0, nextAttemptAt: 0, permanentFailure: false, lastError: null })
      } else {
        cache = replaceOperation(cache, existing?.type === type
          ? { ...existing, bookmark, updatedAt: now, attempts: 0, nextAttemptAt: 0, permanentFailure: false, lastError: null }
          : createOperation(type, key, bookmark, now))
      }
    } else if (type === 'delete') {
      const key = bookmarkKey(bookmarkOrTime?.time ?? bookmarkOrTime)
      if (key === null) throw new Error('Cannot delete a bookmark without a valid time')
      const existing = cache.pendingOperations.find((operation) => operation.bookmarkKey === key)
      cache = { ...cache, bookmarks: cache.bookmarks.filter((bookmark) => bookmarkKey(bookmark.time) !== key), tombstones: { ...cache.tombstones, [key]: { deletedAt: now } } }
      if (existing?.type === 'create' && existing.attempts === 0) {
        cache = { ...cache, pendingOperations: cache.pendingOperations.filter((operation) => operation.bookmarkKey !== key) }
        delete cache.tombstones[key]
      } else cache = replaceOperation(cache, createOperation('delete', key, null, now))
    } else throw new Error('Unsupported bookmark operation: ' + type)

    return this.localStore.setBookmarkCache(normalizedIdentity, cache)
  }

  apply(identity, type, bookmarkOrTime) {
    const normalizedIdentity = this._identity(identity)
    return this._enqueue(normalizedIdentity, () => this._applyUnlocked(normalizedIdentity, type, bookmarkOrTime))
  }

  async fetchServerBookmarks(identity) {
    if (!isIdentityForActiveAccount(identity, this.store)) throw new Error('Bookmark account is not active')
    const response = await this.nativeHttp.get('/api/me', this._options(identity))
    const user = response?.user || response
    return normalizeServerBookmarks(user?.bookmarks || response?.bookmarks || [], identity.libraryItemId)
  }

  _isNotFound(error) {
    return error?.status === 404 || /(?:404|not[ -]?found)/i.test(error?.message || '')
  }

  _acknowledgeOperation(cache, operation) {
    const pendingOperations = cache.pendingOperations.filter((candidate) => candidate.id !== operation.id)
    const tombstones = { ...cache.tombstones }
    if (operation.type === 'delete') delete tombstones[operation.bookmarkKey]
    return { ...cache, pendingOperations, tombstones }
  }

  _recordFailure(cache, operation, error) {
    const attempts = (Number(operation.attempts) || 0) + 1
    const permanentFailure = isPermanentFailure(error) || attempts >= MAX_SYNC_ATTEMPTS
    return replaceOperation(cache, {
      ...operation,
      attempts,
      updatedAt: Date.now(),
      nextAttemptAt: permanentFailure ? 0 : Date.now() + retryDelay(attempts),
      permanentFailure,
      lastError: error?.message || String(error)
    })
  }

  async _syncUnlocked(identity, { refresh = true, serverBookmarks = null } = {}) {
    const normalizedIdentity = this._identity(identity)
    let cache = await this.getCache(normalizedIdentity)
    let latestServerBookmarks = Array.isArray(serverBookmarks) ? normalizeServerBookmarks(serverBookmarks, normalizedIdentity.libraryItemId) : null
    let lastError = null
    const operations = [...cache.pendingOperations].sort((a, b) => operationPriority(a) - operationPriority(b))

    if (!isIdentityForActiveAccount(normalizedIdentity, this.store)) return { cache, error: null }

    for (const operation of operations) {
      const currentOperation = cache.pendingOperations.find((candidate) => candidate.id === operation.id)
      if (!currentOperation || !canAttempt(currentOperation)) continue
      try {
        const url = '/api/me/item/' + normalizedIdentity.libraryItemId + '/bookmark'
        if (currentOperation.type === 'delete') await this.nativeHttp.delete(url + '/' + currentOperation.bookmarkKey, this._options(normalizedIdentity))
        else if (currentOperation.type === 'create') await this.nativeHttp.post(url, currentOperation.bookmark, this._options(normalizedIdentity))
        else await this.nativeHttp.patch(url, currentOperation.bookmark, this._options(normalizedIdentity))
        cache = this._acknowledgeOperation(cache, currentOperation)
        await this.localStore.setBookmarkCache(normalizedIdentity, cache)
      } catch (error) {
        if (currentOperation.type === 'delete' && this._isNotFound(error)) cache = this._acknowledgeOperation(cache, currentOperation)
        else if (currentOperation.type === 'create') {
          try {
            latestServerBookmarks = await this.fetchServerBookmarks(normalizedIdentity)
            const acknowledged = latestServerBookmarks.some((bookmark) => bookmarksEquivalent(bookmark, currentOperation.bookmark, normalizedIdentity.libraryItemId))
            if (acknowledged) cache = this._acknowledgeOperation(cache, currentOperation)
            else {
              cache = this._recordFailure(cache, currentOperation, error)
              lastError = error
            }
          } catch (refreshError) {
            cache = this._recordFailure(cache, currentOperation, error)
            lastError = refreshError
          }
        } else {
          cache = this._recordFailure(cache, currentOperation, error)
          lastError = error
        }
        await this.localStore.setBookmarkCache(normalizedIdentity, cache)
      }
    }

    if (refresh && latestServerBookmarks === null) {
      try {
        latestServerBookmarks = await this.fetchServerBookmarks(normalizedIdentity)
      } catch (error) {
        lastError = lastError || error
      }
    }
    if (latestServerBookmarks !== null) {
      cache = mergeServerBookmarks(cache, latestServerBookmarks, normalizedIdentity)
      await this.localStore.setBookmarkCache(normalizedIdentity, cache)
    }
    return { cache, error: lastError }
  }

  sync(identity, options = {}) {
    const normalizedIdentity = this._identity(identity)
    const key = getIdentityKey(normalizedIdentity)
    if (!key) return Promise.resolve({ cache: createEmptyBookmarkCache(normalizedIdentity), error: new Error('Incomplete bookmark identity') })
    return this._enqueue(normalizedIdentity, () => this._syncUnlocked(normalizedIdentity, options))
  }

  _enqueue(identity, operation) {
    const key = getIdentityKey(identity)
    if (!key) return operation()
    const previous = this.operationLocks.get(key) || Promise.resolve()
    const current = previous.catch(() => {}).then(operation)
    this.operationLocks.set(key, current)
    current.then(() => {
      if (this.operationLocks.get(key) === current) this.operationLocks.delete(key)
    }, () => {
      if (this.operationLocks.get(key) === current) this.operationLocks.delete(key)
    })
    return current
  }
}

export default ({ app, store }, inject) => {
  inject('bookmarks', new BookmarkService({ localStore: app.$localStore, nativeHttp: app.$nativeHttp, store }))
}
