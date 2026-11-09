import { Preferences } from '@capacitor/preferences'
import bookmarkRules from '@/utils/offlineBookmarks'

const { key, position, overlay, coalesce, classify, sameContent, afterConfirmed } = bookmarkRules
let writeChain = Promise.resolve()
let syncFlight = null
const inFlight = new Set()
let retryTimer = null
let retryDelay = 5000
let resyncRequested = false

function storageKey(context) {
  return `offlineBookmarks:v1:${encodeURIComponent(context.id)}:${encodeURIComponent(context.userId)}`
}

function flightKey(context, bookmark) {
  return `${storageKey(context)}:${key(bookmark)}`
}

function validContext(config) {
  return config?.id && config?.userId && config?.address
}

function record(state) {
  return { version: 1, address: state.context.address, snapshot: state.snapshot, pending: state.pending, notes: state.notes, aliases: state.aliases }
}

function retainedNotes(current, snapshot) {
  const present = new Set(snapshot.map(key))
  for (const operation of current.pending) {
    if (operation.type !== 'delete') present.add(key(operation.bookmark))
  }
  return Object.fromEntries(Object.entries(current.notes).filter(([id]) => present.has(id)))
}

// Every change to snapshot and queue is one persisted write. A failed write
// leaves the in-memory state unchanged, so it cannot appear to be saved.
function persist(store, update, expectedAccount = null) {
  const task = writeChain.then(async () => {
    if (!store.state.context) throw new Error('No bookmark account selected')
    if (expectedAccount && storageKey(store.state.context) !== expectedAccount) throw new Error('Bookmark account changed')
    const next = { ...record(store.state), ...update(store.state) }
    await Preferences.set({ key: storageKey(store.state.context), value: JSON.stringify({ version: 1, address: store.state.context.address, ...next }) })
    store.commit('setRecord', next)
  })
  writeChain = task.catch(() => {})
  return task
}

function operationId() {
  return `local_${Date.now()}_${Math.random().toString(36).slice(2)}`
}

export const state = () => ({
  context: null,
  snapshot: [],
  pending: [],
  notes: {},
  aliases: [],
  loaded: false
})

export const getters = {
  forItem: (state) => (libraryItemId) => overlay(state.snapshot.map((bookmark) => ({ ...bookmark, description: state.notes[key(bookmark)] || '' })), state.pending).filter((bookmark) => bookmark.libraryItemId === libraryItemId),
  accountMatches: (state) => (item) => !!(state.loaded && state.context && item?.libraryItemId &&
    item.serverAddress === state.context.address &&
    [state.context.userId, ...state.aliases].includes(item.serverUserId))
}

export const mutations = {
  setContext(state, context) {
    state.context = context
    state.snapshot = []
    state.pending = []
    state.notes = {}
    state.aliases = []
    state.loaded = false
  },
  setRecord(state, data) {
    state.snapshot = data.snapshot
    state.pending = data.pending
    state.notes = data.notes || {}
    state.aliases = data.aliases || []
    state.loaded = true
  },
  clear(state) {
    state.context = null
    state.snapshot = []
    state.pending = []
    state.notes = {}
    state.aliases = []
    state.loaded = false
  }
}

export const actions = {
  async activate({ state, commit }, config) {
    if (!validContext(config)) {
      commit('clear')
      return
    }
    if (state.loaded && state.context?.id === config.id && state.context?.userId === config.userId && state.context?.address === config.address) return
    await writeChain
    const context = { id: config.id, userId: config.userId, address: config.address }
    if (syncFlight) resyncRequested = true
    commit('setContext', context)
    const saved = await Preferences.get({ key: storageKey(context) })
    if (!saved.value) {
      commit('setRecord', { snapshot: [], pending: [], notes: {}, aliases: [] })
      return
    }
    const parsed = JSON.parse(saved.value)
    if (parsed.version !== 1 || parsed.address !== context.address || !Array.isArray(parsed.snapshot) || !Array.isArray(parsed.pending)) {
      throw new Error('Invalid stored bookmark data')
    }
    commit('setRecord', parsed)
  },
  receiveServer({ state, commit }, bookmarks) {
    if (!state.loaded || !Array.isArray(bookmarks)) return
    const snapshot = bookmarks.map((bookmark) => ({ ...bookmark }))
    return persist({ state, commit }, (current) => ({ snapshot, pending: current.pending, notes: retainedNotes(current, snapshot) }))
  },
  rememberUserAlias({ state, commit }, oldUserId) {
    if (!state.loaded || !oldUserId || oldUserId === state.context.userId || state.aliases.includes(oldUserId)) return
    return persist({ state, commit }, (current) => ({ aliases: [...current.aliases, oldUserId] }))
  },
  change({ state, commit, dispatch }, { type, bookmark }) {
    if (!state.loaded) return Promise.reject(new Error('Bookmark account is not ready'))
    if (!['create', 'update', 'delete'].includes(type) || !bookmark?.libraryItemId) return Promise.reject(new Error('Invalid bookmark change'))
    const normalized = { ...bookmark, time: position(bookmark.time) }
    const task = persist({ state, commit }, (current) => {
      const base = current.snapshot.find((saved) => key(saved) === key(normalized)) || null
      if (type === 'create' && (base || current.pending.some((op) => key(op.bookmark) === key(normalized) && op.type !== 'delete'))) {
        throw new Error('A bookmark already exists at this position')
      }
      const notes = { ...current.notes }
      if (type === 'delete') delete notes[key(normalized)]
      else if (normalized.description !== undefined) notes[key(normalized)] = normalized.description
      const pending = coalesce(current.pending, type, normalized, base, operationId(), inFlight.has(flightKey(current.context, normalized)))
      const operation = pending.find((entry) => key(entry.bookmark) === key(normalized))
      if (operation && operation.baseDescription === undefined) operation.baseDescription = current.notes[key(normalized)]
      return { snapshot: current.snapshot, pending, notes }
    })
    return task.then(() => {
      if (syncFlight) resyncRequested = true
      dispatch('sync')
      return true
    })
  },
  resolveConflict({ state, commit, dispatch }, { id, choice }) {
    const task = persist({ state, commit }, (current) => {
      const op = current.pending.find((entry) => entry.id === id)
      if (!op?.conflict) throw new Error('Bookmark conflict not found')
      const pending = choice === 'server' ? current.pending.filter((entry) => entry.id !== id) : current.pending.map((entry) => entry.id === id ? { ...entry, conflict: null, force: true } : entry)
      const notes = { ...current.notes }
      if (choice === 'server') {
        if (!current.snapshot.some((bookmark) => key(bookmark) === key(op.bookmark)) || op.baseDescription === undefined) delete notes[key(op.bookmark)]
        else notes[key(op.bookmark)] = op.baseDescription
      }
      if (choice !== 'server' && choice !== 'local') throw new Error('Invalid conflict choice')
      return { snapshot: current.snapshot, pending, notes }
    })
    return task.then(() => {
      if (syncFlight) resyncRequested = true
      return dispatch('sync')
    })
  },
  sync({ state, rootState, commit, dispatch }) {
    if (syncFlight) return syncFlight
    const config = rootState.user.serverConnectionConfig
    if (!state.loaded || !state.pending.length || !rootState.networkConnected || !rootState.user.user || !config ||
      config.id !== state.context?.id || config.userId !== state.context?.userId || config.address !== state.context?.address) return Promise.resolve()
    const account = storageKey(state.context)
    syncFlight = (async () => {
      try {
        // A fresh server read is required after an interrupted write. Socket
        // state alone cannot tell whether a timed-out POST was accepted.
        const requestOptions = { connectTimeout: 7000, readTimeout: 7000 }
        let serverUser = await this.$nativeHttp.get('/api/me', requestOptions)
        if (!Array.isArray(serverUser?.bookmarks)) throw new Error('Server did not return bookmarks')
        await persist({ state, commit }, (current) => ({ snapshot: serverUser.bookmarks, pending: current.pending, notes: retainedNotes(current, serverUser.bookmarks) }), account)
        for (const submitted of [...state.pending]) {
          if (storageKey(state.context) !== account) break
          const current = state.pending.find((entry) => entry.id === submitted.id)
          if (!current || current.conflict) continue
          const decision = classify(current, serverUser.bookmarks)
          if (decision !== 'send' && decision !== 'done') {
            await persist({ state, commit }, (latest) => ({ snapshot: latest.snapshot, pending: latest.pending.map((entry) => entry.id === current.id ? { ...entry, conflict: decision } : entry) }), account)
            continue
          }
          if (decision === 'done') {
            const exact = serverUser.bookmarks.find((bm) => key(bm) === key(current.bookmark))
            await persist({ state, commit }, (latest) => ({ snapshot: serverUser.bookmarks, pending: afterConfirmed(latest.pending, current, exact), notes: retainedNotes(latest, serverUser.bookmarks) }), account)
            continue
          }
          inFlight.add(flightKey(state.context, current.bookmark))
          try {
            if (decision === 'send') {
              const path = `/api/me/item/${encodeURIComponent(current.bookmark.libraryItemId)}/bookmark`
              const payload = { time: current.bookmark.time, title: current.bookmark.title }
              if (current.type === 'create') {
                // An explicit "keep mine" on a same-second collision updates
                // that server bookmark; the user has chosen this replacement.
                const exact = serverUser.bookmarks.find((bm) => key(bm) === key(current.bookmark))
                if (current.force && exact) await this.$nativeHttp.patch(path, payload, requestOptions)
                else await this.$nativeHttp.post(path, payload, requestOptions)
              } else if (current.type === 'update') {
                if (current.force && !serverUser.bookmarks.some((bm) => key(bm) === key(current.bookmark))) await this.$nativeHttp.post(path, payload, requestOptions)
                else await this.$nativeHttp.patch(path, payload, requestOptions)
              } else await this.$nativeHttp.delete(`${path}/${current.bookmark.time}`, requestOptions)
            }
            // Confirm server state before removing a queued operation. If this
            // read fails, retain it and reconcile on the next sync attempt.
            serverUser = await this.$nativeHttp.get('/api/me', requestOptions)
            if (!Array.isArray(serverUser?.bookmarks)) throw new Error('Server did not return bookmarks')
            const exact = serverUser.bookmarks.find((bm) => key(bm) === key(current.bookmark))
            if (current.type === 'delete' ? !!exact : !exact || !sameContent(exact, current.bookmark)) throw new Error('Bookmark write not confirmed')
            await persist({ state, commit }, (latest) => ({ snapshot: serverUser.bookmarks, pending: afterConfirmed(latest.pending, current, exact), notes: retainedNotes(latest, serverUser.bookmarks) }), account)
          } catch (error) {
            if (error.status >= 400 && error.status < 500 && error.status !== 408 && error.status !== 429) {
              await persist({ state, commit }, (latest) => ({ pending: latest.pending.map((entry) => entry.id === current.id ? { ...entry, conflict: 'server-rejected' } : entry) }), account)
            } else throw error
          } finally {
            inFlight.delete(`${account}:${key(current.bookmark)}`)
          }
        }
      } catch (error) {
        // Network and authentication failures leave the queue intact. The
        // next connection, resume, or local edit will trigger another attempt.
        console.warn('[bookmarks] Sync postponed', error)
        if (!retryTimer && state.pending.length && state.context && storageKey(state.context) === account) {
          resyncRequested = false
          retryTimer = setTimeout(() => {
            retryTimer = null
            dispatch('sync')
          }, retryDelay)
          retryDelay = Math.min(retryDelay * 2, 60000)
        }
      }
      if (!state.pending.length) retryDelay = 5000
    })().finally(() => {
      syncFlight = null
      if (resyncRequested && !retryTimer) {
        resyncRequested = false
        dispatch('sync')
      }
    })
    return syncFlight
  }
}
