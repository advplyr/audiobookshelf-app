const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const rules = require('../utils/offlineBookmarks')

const source = fs.readFileSync(path.join(__dirname, '../store/bookmarks.js'), 'utf8')
  .replace(/^import .*$/gm, '')
  .replace(/export const /g, 'const ') + '\nmodule.exports = { state, getters, mutations, actions }'

function harness(storage = new Map()) {
  const server = { bookmarks: [] }
  const requests = []
  const preferences = {
    async get({ key }) { return { value: storage.get(key) || null } },
    async set({ key, value }) { storage.set(key, value) }
  }
  let failAfterCreate = false
  let failAfterDelete = false
  let failNextSave = false
  let holdNextPost = false
  let releasePost = null
  preferences.set = async ({ key, value }) => {
    if (failNextSave) { failNextSave = false; throw new Error('Storage unavailable') }
    storage.set(key, value)
  }
  const http = {
    async get(url) {
      requests.push(['GET', url])
      return JSON.parse(JSON.stringify(server))
    },
    async post(url, payload) {
      requests.push(['POST', url, payload])
      const bookmark = { libraryItemId: url.split('/')[4], ...payload, createdAt: Date.now() }
      server.bookmarks.push(bookmark)
      if (holdNextPost) {
        holdNextPost = false
        await new Promise((resolve) => { releasePost = resolve })
      }
      if (failAfterCreate) {
        failAfterCreate = false
        throw new Error('Response lost')
      }
      return bookmark
    },
    async patch(url, payload) {
      requests.push(['PATCH', url, payload])
      const bookmark = server.bookmarks.find((entry) => entry.libraryItemId === url.split('/')[4] && entry.time === payload.time)
      if (!bookmark) throw new Error('HTTP 404')
      bookmark.title = payload.title
      return bookmark
    },
    async delete(url) {
      requests.push(['DELETE', url])
      const parts = url.split('/')
      server.bookmarks = server.bookmarks.filter((entry) => !(entry.libraryItemId === parts[4] && entry.time === Number(parts[6])))
      if (failAfterDelete) { failAfterDelete = false; throw new Error('Response lost') }
    }
  }
  const sandbox = { module: { exports: {} }, Preferences: preferences, bookmarkRules: rules, console: { warn() {} }, setTimeout() { return 1 } }
  vm.runInNewContext(source, sandbox, { filename: 'store/bookmarks.js' })
  const mod = sandbox.module.exports
  const state = mod.state()
  const rootState = { networkConnected: false, user: { user: null, serverConnectionConfig: null } }
  const app = { $nativeHttp: http }
  const context = {
    state, rootState,
    commit(name, payload) { mod.mutations[name](state, payload) },
    dispatch(name, payload) { return Promise.resolve(mod.actions[name].call(app, context, payload)) }
  }
  return {
    state, rootState, server, requests, storage, context,
    dispatch: context.dispatch,
    visible: (id = 'book') => mod.getters.forItem(state)(id),
    match: (item) => mod.getters.accountMatches(state)(item),
    loseCreateResponse() { failAfterCreate = true },
    loseDeleteResponse() { failAfterDelete = true },
    failSave() { failNextSave = true },
    holdPost() { holdNextPost = true },
    releasePost() { if (releasePost) releasePost() },
    postIsHeld() { return !!releasePost },
    async activate(config = { id: 'server-a', address: 'https://a', userId: 'alice' }) {
      await context.dispatch('activate', config)
      rootState.user.serverConnectionConfig = config
    },
    connect() { rootState.networkConnected = true; rootState.user.user = { id: rootState.user.serverConnectionConfig.userId } },
    disconnect() { rootState.networkConnected = false; rootState.user.user = null }
  }
}

function bookmark(time, title = 'Chapter', description = '') {
  return { libraryItemId: 'book', time, title, description }
}

test('offline create is visible immediately and survives restart', async () => {
  const app = harness()
  await app.activate()
  await app.dispatch('change', { type: 'create', bookmark: bookmark(20.7, 'Intro', 'Remember this') })
  assert.equal(app.visible()[0].time, 20)
  assert.equal(app.visible()[0].description, 'Remember this')
  assert.equal(app.requests.length, 0)
  const restarted = harness(app.storage)
  await restarted.activate()
  assert.equal(restarted.visible()[0].title, 'Intro')
  assert.equal(restarted.visible()[0].description, 'Remember this')
  assert.equal(restarted.state.pending.length, 1)
})

test('offline create and edits coalesce; deleting an unsent create cancels it', async () => {
  const app = harness()
  await app.activate()
  await app.dispatch('change', { type: 'create', bookmark: bookmark(20) })
  await app.dispatch('change', { type: 'update', bookmark: bookmark(20, 'New title') })
  assert.equal(app.state.pending.length, 1)
  assert.equal(app.state.pending[0].type, 'create')
  await app.dispatch('change', { type: 'delete', bookmark: bookmark(20, 'New title') })
  assert.equal(app.state.pending.length, 0)
  assert.equal(app.visible().length, 0)
})

test('reconnect sends a queued create and keeps description locally', async () => {
  const app = harness()
  await app.activate()
  await app.dispatch('change', { type: 'create', bookmark: bookmark(30, 'Quote', 'Local note') })
  app.connect()
  await app.dispatch('sync')
  assert.equal(app.server.bookmarks.length, 1)
  assert.equal(app.state.pending.length, 0)
  assert.equal(app.visible()[0].description, 'Local note')
  assert.deepEqual(Object.keys(app.requests.find((request) => request[0] === 'POST')[2]).sort(), ['time', 'title'])
})

test('lost create response is reconciled without a second POST', async () => {
  const app = harness()
  await app.activate()
  await app.dispatch('change', { type: 'create', bookmark: bookmark(30) })
  app.connect()
  app.loseCreateResponse()
  await app.dispatch('sync')
  assert.equal(app.state.pending.length, 1)
  await app.dispatch('sync')
  assert.equal(app.server.bookmarks.length, 1)
  assert.equal(app.requests.filter((request) => request[0] === 'POST').length, 1)
  assert.equal(app.state.pending.length, 0)
})

test('nearby same-content bookmark is flagged; different titles remain legitimate', async () => {
  const app = harness()
  await app.activate()
  app.server.bookmarks.push({ libraryItemId: 'book', time: 31, title: 'Chapter' })
  await app.dispatch('change', { type: 'create', bookmark: bookmark(35) })
  app.connect()
  await app.dispatch('sync')
  assert.equal(app.state.pending[0].conflict, 'possible-duplicate')
  assert.equal(app.requests.filter((request) => request[0] === 'POST').length, 0)
  await app.dispatch('resolveConflict', { id: app.state.pending[0].id, choice: 'local' })
  assert.equal(app.server.bookmarks.length, 2)
  app.disconnect()
  await app.dispatch('change', { type: 'create', bookmark: bookmark(37, 'Other') })
  app.connect()
  await app.dispatch('sync')
  assert.equal(app.server.bookmarks.length, 3)
})

test('offline edit and delete sync; remote edits become explicit conflicts', async () => {
  const app = harness()
  await app.activate()
  app.server.bookmarks.push({ libraryItemId: 'book', time: 5, title: 'Old' })
  await app.dispatch('receiveServer', app.server.bookmarks)
  await app.dispatch('change', { type: 'update', bookmark: bookmark(5, 'Mine') })
  app.server.bookmarks[0].title = 'Other device'
  app.connect()
  await app.dispatch('sync')
  assert.equal(app.state.pending[0].conflict, 'remote-changed')
  assert.equal(app.visible()[0].title, 'Mine')
  await app.dispatch('resolveConflict', { id: app.state.pending[0].id, choice: 'local' })
  assert.equal(app.server.bookmarks[0].title, 'Mine')
  app.disconnect()
  await app.dispatch('change', { type: 'delete', bookmark: app.visible()[0] })
  assert.equal(app.visible().length, 0)
  app.connect()
  await app.dispatch('sync')
  assert.equal(app.server.bookmarks.length, 0)
})

test('server deletion during an offline edit retains the local change', async () => {
  const app = harness()
  await app.activate()
  app.server.bookmarks.push({ libraryItemId: 'book', time: 5, title: 'Old' })
  await app.dispatch('receiveServer', app.server.bookmarks)
  await app.dispatch('change', { type: 'update', bookmark: bookmark(5, 'Mine') })
  app.server.bookmarks = []
  app.connect()
  await app.dispatch('sync')
  assert.equal(app.state.pending[0].conflict, 'remote-deleted')
  assert.equal(app.visible()[0].title, 'Mine')
})

test('account and server records remain isolated across offline startup', async () => {
  const app = harness()
  await app.activate()
  await app.dispatch('change', { type: 'create', bookmark: bookmark(10) })
  await app.activate({ id: 'server-a', address: 'https://a', userId: 'bob' })
  assert.equal(app.visible().length, 0)
  assert.equal(app.match({ libraryItemId: 'book', serverAddress: 'https://a', serverUserId: 'alice' }), false)
  await app.activate({ id: 'server-b', address: 'https://b', userId: 'alice' })
  assert.equal(app.visible().length, 0)
  await app.activate()
  assert.equal(app.visible().length, 1)
  assert.equal(app.match({ libraryItemId: 'book', serverAddress: 'https://a', serverUserId: 'alice' }), true)
  assert.equal(app.match({ libraryItemId: 'book', serverAddress: 'https://a', serverUserId: 'bob', serverConnectionConfigId: 'server-a' }), false)
  await app.dispatch('rememberUserAlias', 'old-alice')
  assert.equal(app.match({ libraryItemId: 'book', serverAddress: 'https://a', serverUserId: 'old-alice' }), true)
  const restarted = harness(app.storage)
  await restarted.activate()
  assert.equal(restarted.match({ libraryItemId: 'book', serverAddress: 'https://a', serverUserId: 'old-alice' }), true)
})

test('online create works through the same queue and confirms before clearing', async () => {
  const app = harness()
  await app.activate()
  app.connect()
  await app.dispatch('change', { type: 'create', bookmark: bookmark(11) })
  await app.dispatch('sync')
  assert.equal(app.server.bookmarks.length, 1)
  assert.equal(app.state.pending.length, 0)
})

test('failed persistence does not show a bookmark or lose the queue', async () => {
  const app = harness()
  await app.activate()
  app.failSave()
  await assert.rejects(app.dispatch('change', { type: 'create', bookmark: bookmark(9) }), /Storage unavailable/)
  assert.equal(app.visible().length, 0)
  assert.equal(app.state.pending.length, 0)
})

test('server refresh is overlaid with pending local changes', async () => {
  const app = harness()
  await app.activate()
  await app.dispatch('change', { type: 'create', bookmark: bookmark(9, 'Local') })
  await app.dispatch('receiveServer', [{ libraryItemId: 'book', time: 12, title: 'Remote' }])
  assert.deepEqual(app.visible().map((entry) => entry.title), ['Local', 'Remote'])
  assert.equal(app.state.pending.length, 1)
})

test('multiple offline edits collapse to one update', async () => {
  const app = harness()
  await app.activate()
  app.server.bookmarks.push({ libraryItemId: 'book', time: 10, title: 'Old' })
  await app.dispatch('receiveServer', app.server.bookmarks)
  await app.dispatch('change', { type: 'update', bookmark: bookmark(10, 'First') })
  await app.dispatch('change', { type: 'update', bookmark: bookmark(10, 'Final') })
  app.connect()
  await app.dispatch('sync')
  assert.equal(app.server.bookmarks[0].title, 'Final')
  assert.equal(app.requests.filter((request) => request[0] === 'PATCH').length, 1)
})

test('lost delete response is reconciled without a second DELETE', async () => {
  const app = harness()
  await app.activate()
  app.server.bookmarks.push({ libraryItemId: 'book', time: 10, title: 'Old' })
  await app.dispatch('receiveServer', app.server.bookmarks)
  await app.dispatch('change', { type: 'delete', bookmark: bookmark(10, 'Old') })
  app.connect()
  app.loseDeleteResponse()
  await app.dispatch('sync')
  assert.equal(app.state.pending.length, 1)
  await app.dispatch('sync')
  assert.equal(app.state.pending.length, 0)
  assert.equal(app.requests.filter((request) => request[0] === 'DELETE').length, 1)
})

test('exact time collision stays queued until explicit resolution', async () => {
  const app = harness()
  await app.activate()
  await app.dispatch('change', { type: 'create', bookmark: bookmark(20, 'Mine') })
  app.server.bookmarks.push({ libraryItemId: 'book', time: 20, title: 'Other device' })
  app.connect()
  await app.dispatch('sync')
  assert.equal(app.state.pending[0].conflict, 'collision')
  assert.equal(app.server.bookmarks[0].title, 'Other device')
  await app.dispatch('resolveConflict', { id: app.state.pending[0].id, choice: 'local' })
  assert.equal(app.server.bookmarks[0].title, 'Mine')
})

test('six seconds apart is outside duplicate tolerance', () => {
  const operation = { type: 'create', bookmark: bookmark(20, 'Same') }
  assert.equal(rules.classify(operation, [bookmark(26, 'Same')]), 'send')
  assert.equal(rules.classify(operation, [bookmark(25, 'Same')]), 'possible-duplicate')
})

test('an edit made during an in-flight create is preserved and sent next', async () => {
  const app = harness()
  await app.activate()
  await app.dispatch('change', { type: 'create', bookmark: bookmark(40, 'First') })
  app.connect()
  app.holdPost()
  const syncing = app.dispatch('sync')
  for (let i = 0; i < 20 && !app.postIsHeld(); i++) await new Promise((resolve) => setImmediate(resolve))
  assert.equal(app.postIsHeld(), true)
  await app.dispatch('change', { type: 'update', bookmark: bookmark(40, 'Final') })
  app.releasePost()
  await syncing
  await app.dispatch('sync')
  assert.equal(app.server.bookmarks[0].title, 'Final')
  assert.equal(app.state.pending.length, 0)
  assert.equal(app.requests.filter((request) => request[0] === 'POST').length, 1)
  assert.equal(app.requests.filter((request) => request[0] === 'PATCH').length, 1)
})
