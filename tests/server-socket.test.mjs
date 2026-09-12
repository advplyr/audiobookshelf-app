import test from 'node:test'
import assert from 'node:assert/strict'
import EventEmitter from 'events'

import { ServerSocket } from '../plugins/server-socket.mjs'

class FakeSocket extends EventEmitter {
  constructor() {
    super()
    this.io = new EventEmitter()
    this.connected = false
    this.id = 'fake-socket'
    this.connectCalls = 0
    this.disconnectCalls = 0
  }

  connect() {
    this.connectCalls++
    return this
  }

  disconnect() {
    this.disconnectCalls++
    this.connected = false
    return this
  }
}

function createStore() {
  const commits = []
  const store = {
    getters: {
      'user/getToken': 'access-token',
      'user/getServerAddress': 'https://example.test/audiobookshelf/'
    },
    state: {
      libraries: {
        numUserPlaylists: 0
      }
    },
    commit(type, payload) {
      commits.push({ type, payload })
    }
  }
  return { store, commits }
}

function createTimers() {
  let nextId = 1
  const callbacks = new Map()
  return {
    setTimeoutFn(callback) {
      const id = nextId++
      callbacks.set(id, callback)
      return id
    },
    clearTimeoutFn(id) {
      callbacks.delete(id)
    },
    runNext() {
      const entry = callbacks.entries().next().value
      assert.ok(entry, 'expected a pending timer')
      const [id, callback] = entry
      callbacks.delete(id)
      callback()
    },
    get size() {
      return callbacks.size
    }
  }
}

function createHarness(options = {}) {
  const { store, commits } = createStore()
  const sockets = []
  const factoryCalls = []
  const socketFactory = (host, socketOptions) => {
    factoryCalls.push({ host, socketOptions })
    const socket = new FakeSocket()
    sockets.push(socket)
    return socket
  }
  const serverSocket = new ServerSocket(store, socketFactory, options)
  return { serverSocket, commits, sockets, factoryCalls }
}

test('connect preserves a reverse-proxy path and enables bounded reconnection delay', () => {
  const { serverSocket, sockets, factoryCalls } = createHarness()

  assert.equal(serverSocket.connect('https://example.test/audiobookshelf/', 'fallback-token'), true)
  assert.equal(sockets.length, 1)
  assert.deepEqual(factoryCalls[0], {
    host: 'https://example.test',
    socketOptions: {
      transports: ['websocket'],
      upgrade: false,
      path: '/audiobookshelf/socket.io',
      reconnection: true,
      reconnectionDelay: 1000,
      reconnectionDelayMax: 15000
    }
  })
})

test('transport connection authenticates and init marks the socket healthy', () => {
  const timers = createTimers()
  const { serverSocket, commits, sockets } = createHarness(timers)
  serverSocket.connect('https://example.test')
  const socket = sockets[0]
  const authTokens = []
  socket.on('auth', (token) => authTokens.push(token))

  socket.connected = true
  socket.emit('connect')

  assert.deepEqual(authTokens, ['access-token'])
  assert.equal(serverSocket.connected, true)
  assert.equal(serverSocket.isAuthenticated, false)
  assert.equal(timers.size, 1)

  socket.emit('init', { userId: 'user-1' })

  assert.equal(serverSocket.isAuthenticated, true)
  assert.equal(timers.size, 0)
  assert.ok(commits.some((commit) => commit.type === 'setSocketAuthenticated' && commit.payload === true))
  assert.ok(commits.some((commit) => commit.type === 'setSocketRecovering' && commit.payload === false))
})

test('authentication watchdog retries once and then reports a timeout', () => {
  const timers = createTimers()
  const { serverSocket, sockets } = createHarness(timers)
  serverSocket.connect('https://example.test')
  const socket = sockets[0]
  const authTokens = []
  let timedOut = false
  serverSocket.on('authentication-timeout', () => {
    timedOut = true
  })
  socket.on('auth', (token) => authTokens.push(token))

  socket.connected = true
  socket.emit('connect')
  timers.runNext()

  assert.deepEqual(authTokens, ['access-token', 'access-token'])
  assert.equal(timedOut, false)

  timers.runNext()
  assert.equal(timedOut, true)
})

test('ensureConnected wakes a disconnected socket and reauthenticates a live transport', () => {
  const timers = createTimers()
  const { serverSocket, sockets } = createHarness(timers)
  serverSocket.connect('https://example.test')
  const socket = sockets[0]

  assert.equal(serverSocket.ensureConnected(null, 'network-restored'), true)
  assert.equal(socket.connectCalls, 1)

  const authTokens = []
  socket.on('auth', (token) => authTokens.push(token))
  socket.connected = true
  assert.equal(serverSocket.ensureConnected(null, 'app-active'), true)
  assert.deepEqual(authTokens, ['access-token'])
})

test('server-initiated disconnect gets the manual reconnect Socket.IO requires', () => {
  const timers = createTimers()
  const { serverSocket, sockets } = createHarness(timers)
  serverSocket.connect('https://example.test')
  const socket = sockets[0]

  socket.emit('disconnect', 'io server disconnect')
  assert.equal(timers.size, 1)

  timers.runNext()
  assert.equal(socket.connectCalls, 1)
})

test('logout cancels recovery and clears connection state', () => {
  const timers = createTimers()
  const { serverSocket, commits, sockets } = createHarness(timers)
  serverSocket.connect('https://example.test')
  const socket = sockets[0]
  socket.emit('disconnect', 'io server disconnect')

  serverSocket.logout()

  assert.equal(timers.size, 0)
  assert.equal(socket.disconnectCalls, 1)
  assert.equal(serverSocket.socket, null)
  assert.equal(serverSocket.serverAddress, null)
  assert.ok(commits.some((commit) => commit.type === 'setSocketConnected' && commit.payload === false))
})
