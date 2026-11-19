const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const rules = require('../utils/offlineBookmarks')
const autoBookmarkRules = require('../utils/androidAutoBookmarks')

const source = fs.readFileSync(path.join(__dirname, '../store/bookmarks.js'), 'utf8')
  .replace(/^import .*$/gm, '')
  .replace(/export const /g, 'const ') + '\nmodule.exports = { state, getters, mutations, actions }'

function harness(storage = new Map(), nativeQueue = []) {
  const server = { bookmarks: [] }
  const requests = []
  const preferences = {
    async get({ key }) { return { value: storage.get(key) || null } },
    async set({ key, value }) { storage.set(key, value) }
  }
  let failAfterCreate = false
  let failAfterDelete = false
  let failNextSave = false
  let failNextReadback = false
  let holdNextPost = false
  let releasePost = null
  let platform = 'web'
  let failNextAck = false
  const nativePlayer = {
    async getPendingAndroidAutoBookmarks() { return { value: nativeQueue.map((entry) => ({ ...entry })) } },
    async acknowledgeAndroidAutoBookmark({ id }) {
      if (failNextAck) { failNextAck = false; throw new Error('Acknowledgement interrupted') }
      const index = nativeQueue.findIndex((entry) => entry.id === id)
      if (index >= 0) nativeQueue.splice(index, 1)
    },
    async refreshAndroidAutoBookmarks() {}
  }
  preferences.set = async ({ key, value }) => {
    if (failNextSave) { failNextSave = false; throw new Error('Storage unavailable') }
    storage.set(key, value)
  }
  preferences.get = async ({ key }) => {
    if (failNextReadback) { failNextReadback = false; return { value: null } }
    return { value: storage.get(key) || null }
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
  const sandbox = { module: { exports: {} }, Preferences: preferences, bookmarkRules: rules, Capacitor: { getPlatform: () => platform }, AbsAudioPlayer: nativePlayer, AbsLogger: { async info() {}, async error() {} }, console: { warn() {}, info() {} }, setTimeout() { return 1 } }
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
    state, rootState, server, requests, storage, context, nativeQueue, nativePlayer,
    dispatch: context.dispatch,
    visible: (id = 'book') => mod.getters.forItem(state)(id),
    match: (item) => mod.getters.accountMatches(state)(item),
    loseCreateResponse() { failAfterCreate = true },
    loseDeleteResponse() { failAfterDelete = true },
    failSave() { failNextSave = true },
    failReadback() { failNextReadback = true },
    failAck() { failNextAck = true },
    useAndroid() { platform = 'android' },
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

test('Android Auto request is durably imported into the existing queue and synced', async () => {
  const app = harness()
  app.useAndroid()
  await app.activate()
  app.nativeQueue.push({ id: 'car-1', serverConnectionConfigId: 'server-a', serverAddress: 'https://a', userId: 'alice', libraryItemId: 'book', time: 42, title: 'Bookmark 00:00:42' })
  await app.dispatch('importAndroidAuto')
  assert.equal(app.nativeQueue.length, 0)
  assert.equal(app.visible()[0].time, 42)
  assert.equal(app.state.pending.length, 1)
  const restarted = harness(app.storage)
  await restarted.activate()
  assert.equal(restarted.state.pending.length, 1)
  restarted.connect()
  await restarted.dispatch('sync')
  assert.equal(restarted.server.bookmarks.length, 1)
})

test('interrupted Android Auto acknowledgement does not duplicate the imported change', async () => {
  const app = harness()
  app.useAndroid()
  await app.activate()
  app.nativeQueue.push({ id: 'car-2', serverConnectionConfigId: 'server-a', serverAddress: 'https://a', userId: 'alice', libraryItemId: 'book', time: 55, title: 'Bookmark 00:00:55' })
  app.failAck()
  await assert.rejects(app.dispatch('importAndroidAuto'), /Acknowledgement interrupted/)
  assert.equal(app.state.pending.length, 1)
  assert.equal(app.state.nativeReceipts.length, 1)
  await app.dispatch('importAndroidAuto')
  assert.equal(app.nativeQueue.length, 0)
  assert.equal(app.state.pending.length, 1)
})

test('Android Auto request remains native until its account is active', async () => {
  const app = harness()
  app.useAndroid()
  await app.activate({ id: 'server-a', address: 'https://a', userId: 'bob' })
  app.nativeQueue.push({ id: 'car-3', serverConnectionConfigId: 'server-a', serverAddress: 'https://a', userId: 'alice', libraryItemId: 'book', time: 60, title: 'Bookmark 00:01:00' })
  await app.dispatch('importAndroidAuto')
  assert.equal(app.nativeQueue.length, 1)
  assert.equal(app.state.pending.length, 0)
  await app.activate()
  await app.dispatch('importAndroidAuto')
  assert.equal(app.nativeQueue.length, 0)
  assert.equal(app.state.pending.length, 1)
})

test('account switch during Android Auto import does not acknowledge or misfile the request', async () => {
  const app = harness()
  app.useAndroid()
  await app.activate()
  const request = { id: 'car-4', serverConnectionConfigId: 'server-a', serverAddress: 'https://a', userId: 'alice', libraryItemId: 'book', time: 75, title: 'Bookmark 00:01:15' }
  app.nativeQueue.push(request)
  let releaseRead
  const originalRead = app.nativePlayer.getPendingAndroidAutoBookmarks
  app.nativePlayer.getPendingAndroidAutoBookmarks = () => new Promise((resolve) => {
    releaseRead = () => resolve({ value: [request] })
  })
  const importing = app.dispatch('importAndroidAuto')
  await app.activate({ id: 'server-a', address: 'https://a', userId: 'bob' })
  releaseRead()
  await importing
  app.nativePlayer.getPendingAndroidAutoBookmarks = originalRead
  assert.equal(app.nativeQueue.length, 1)
  assert.equal(app.state.pending.length, 0)
  await app.activate()
  await app.dispatch('importAndroidAuto')
  assert.equal(app.state.pending.length, 1)
  assert.equal(app.nativeQueue.length, 0)
})

test('a user edit at the same position wins over the generated Android Auto title', async () => {
  const app = harness()
  app.useAndroid()
  await app.activate()
  await app.dispatch('change', { type: 'create', bookmark: bookmark(90, 'My edited title') })
  app.nativeQueue.push({ id: 'car-5', serverConnectionConfigId: 'server-a', serverAddress: 'https://a', userId: 'alice', libraryItemId: 'book', time: 90, title: 'Bookmark 00:01:30' })
  await app.dispatch('importAndroidAuto')
  assert.equal(app.nativeQueue.length, 0)
  assert.equal(app.state.pending.length, 1)
  assert.equal(app.visible()[0].title, 'My edited title')
})

test('duplicate Android Auto deliveries at one position produce one server bookmark', async () => {
  const app = harness()
  app.useAndroid()
  await app.activate()
  for (const id of ['car-6a', 'car-6b']) {
    app.nativeQueue.push({ id, serverConnectionConfigId: 'server-a', serverAddress: 'https://a', userId: 'alice', libraryItemId: 'book', time: 105, title: 'Bookmark 00:01:45' })
  }
  await app.dispatch('importAndroidAuto')
  assert.equal(app.nativeQueue.length, 0)
  assert.equal(app.state.pending.length, 1)
  app.connect()
  await app.dispatch('sync')
  assert.equal(app.server.bookmarks.length, 1)
  assert.equal(app.requests.filter((request) => request[0] === 'POST').length, 1)
})

test('failed Android Auto handoff leaves native request for retry', async () => {
  const app = harness()
  app.useAndroid()
  await app.activate()
  app.nativeQueue.push({ id: 'car-7', serverConnectionConfigId: 'server-a', serverAddress: 'https://a', userId: 'alice', libraryItemId: 'book', time: 120, title: 'Bookmark 00:02:00' })
  app.failSave()
  await assert.rejects(app.dispatch('importAndroidAuto'), /Storage unavailable/)
  assert.equal(app.nativeQueue.length, 1)
  assert.equal(app.state.pending.length, 0)
  await app.dispatch('importAndroidAuto')
  assert.equal(app.nativeQueue.length, 0)
  assert.equal(app.state.pending.length, 1)
})

test('Android Auto requests can be imported after the bookmark account loads', async () => {
  const app = harness()
  app.useAndroid()
  app.nativeQueue.push({ id: 'car-8', serverConnectionConfigId: 'server-a', serverAddress: 'https://a', userId: 'alice', libraryItemId: 'book', time: 135, title: 'Bookmark 00:02:15' })
  await app.dispatch('importAndroidAuto')
  assert.equal(app.nativeQueue.length, 1)
  await app.activate()
  await app.dispatch('importAndroidAuto')
  assert.equal(app.nativeQueue.length, 0)
  assert.equal(app.visible()[0].time, 135)
})

test('Android Auto request from a known old user ID imports into the current account', async () => {
  const app = harness()
  app.useAndroid()
  await app.activate()
  await app.dispatch('rememberUserAlias', 'old-alice')
  app.nativeQueue.push({ id: 'car-9', serverConnectionConfigId: 'server-a', serverAddress: 'https://a', userId: 'old-alice', libraryItemId: 'book', time: 150, title: 'Bookmark 00:02:30' })
  await app.dispatch('importAndroidAuto')
  assert.equal(app.nativeQueue.length, 0)
  assert.equal(app.visible()[0].time, 150)
  assert.equal(app.state.pending.length, 1)
})

test('pending deletion is not replaced or acknowledged by a native create at the same position', async () => {
  const app = harness()
  app.useAndroid()
  await app.activate()
  app.server.bookmarks.push(bookmark(165, 'Old'))
  await app.dispatch('receiveServer', app.server.bookmarks)
  await app.dispatch('change', { type: 'delete', bookmark: bookmark(165, 'Old') })
  app.nativeQueue.push({ id: 'car-10', serverConnectionConfigId: 'server-a', serverAddress: 'https://a', userId: 'alice', libraryItemId: 'book', time: 165, title: 'Bookmark 00:02:45' })
  await app.dispatch('importAndroidAuto')
  assert.equal(app.nativeQueue.length, 1)
  assert.equal(app.state.pending[0].type, 'delete')
  assert.equal(app.visible().length, 0)
})

test('offline startup restores the sole bookmark account without a last server ID', () => {
  const config = { id: 'server-a', address: 'https://a', userId: 'alice' }
  assert.equal(autoBookmarkRules.startupBookmarkConfig({ lastServerConnectionConfigId: null, serverConnectionConfigs: [config] }), config)
  assert.equal(autoBookmarkRules.startupBookmarkConfig({ lastServerConnectionConfigId: null, serverConnectionConfigs: [config, { id: 'server-b' }] }), null)
  assert.equal(autoBookmarkRules.startupBookmarkConfig({ lastServerConnectionConfigId: 'server-a', serverConnectionConfigs: [config, { id: 'server-b' }] }), config)
  assert.equal(autoBookmarkRules.startupBookmarkConfig({ lastServerConnectionConfigId: 'unknown', serverConnectionConfigs: [config] }), null)
})

test('Android Auto requests left by an earlier app process import after restart', async () => {
  const storage = new Map()
  const nativeQueue = [{ id: 'car-11', serverConnectionConfigId: 'server-a', serverAddress: 'https://a', userId: 'alice', libraryItemId: 'book', time: 1138, title: 'Bookmark 00:18:58' }]
  const restarted = harness(storage, nativeQueue)
  restarted.useAndroid()
  await restarted.activate()
  await restarted.dispatch('importAndroidAuto')
  assert.equal(nativeQueue.length, 0)
  assert.equal(restarted.visible()[0].time, 1138)
  const nextRestart = harness(storage, nativeQueue)
  await nextRestart.activate()
  assert.equal(nextRestart.visible()[0].time, 1138)
})

test('native request is acknowledged only after Preferences readback confirms its receipt', async () => {
  const app = harness()
  app.useAndroid()
  await app.activate()
  app.nativeQueue.push({ id: 'car-12', serverConnectionConfigId: 'server-a', serverAddress: 'https://a', userId: 'alice', libraryItemId: 'book', time: 180, title: 'Bookmark 00:03:00' })
  app.failReadback()
  await assert.rejects(app.dispatch('importAndroidAuto'), /could not be verified/)
  assert.equal(app.nativeQueue.length, 1)
  assert.equal(app.state.pending.length, 1)
  await app.dispatch('importAndroidAuto')
  assert.equal(app.nativeQueue.length, 0)
  assert.equal(app.state.pending.length, 1)
})
