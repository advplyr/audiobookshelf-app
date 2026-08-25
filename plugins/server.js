import { io } from 'socket.io-client'
import EventEmitter from 'events'

class ServerSocket extends EventEmitter {
  constructor(store) {
    super()

    this.$store = store
    this.socket = null
    this.connected = false
    this.serverAddress = null
    this.isAuthenticated = false

    this.lastReconnectAttemptTime = 0
  }

  normalizeAddress(address) {
    if (!address) return null
    try {
      return new URL(address).href.replace(/\/$/, '')
    } catch (error) {
      return address
    }
  }

  $on(evt, callback) {
    if (this.socket) this.socket.on(evt, callback)
    else console.error('$on Socket not initialized')
  }

  $off(evt, callback) {
    if (this.socket) this.socket.off(evt, callback)
    else console.error('$off Socket not initialized')
  }

  connect(serverAddress, token) {
    const normalizedServerAddress = this.normalizeAddress(serverAddress)
    if (this.socket && this.normalizeAddress(this.serverAddress) === normalizedServerAddress) {
      if (this.connected && !this.isAuthenticated) {
        this.sendAuthenticate()
      }
      return
    }

    this.closeCurrentSocket()
    this.serverAddress = normalizedServerAddress
    this.connected = false
    this.isAuthenticated = false
    this.$store.commit('setSocketConnected', false)

    const serverUrl = new URL(normalizedServerAddress)
    const serverHost = `${serverUrl.protocol}//${serverUrl.host}`
    const serverPath = serverUrl.pathname === '/' ? '' : serverUrl.pathname

    console.log(`[SOCKET] Connecting to ${serverHost} with path ${serverPath}/socket.io`)

    const socketOptions = {
      transports: ['websocket'],
      upgrade: false,
      path: `${serverPath}/socket.io`,
      reconnectionDelayMax: 15000
    }
    this.socket = io(serverHost, socketOptions)
    this.setSocketListeners(this.socket)
  }

  logout() {
    this.closeCurrentSocket()
    this.serverAddress = null
  }

  isConnectedTo(serverAddress) {
    return this.connected && this.normalizeAddress(this.serverAddress) === this.normalizeAddress(serverAddress)
  }

  setSocketListeners(socket) {
    socket.on('connect', () => this.onConnect(socket))
    socket.on('disconnect', (reason) => this.onDisconnect(socket, reason))
    socket.on('init', (data) => this.onInit(socket, data))
    socket.on('auth_failed', (data) => this.onAuthFailed(socket, data))
    socket.on('user_updated', (data) => this.onUserUpdated(socket, data))
    socket.on('user_item_progress_updated', (payload) => this.onUserItemProgressUpdated(socket, payload))
    socket.on('playlist_added', () => this.onPlaylistAdded(socket))
    socket.io.on('reconnect_attempt', (attemptNumber) => this.onReconnectAttempt(socket, attemptNumber))
    socket.io.on('reconnect_error', (error) => this.onReconnectError(socket, error))
    socket.io.on('reconnect_failed', (error) => this.onReconnectFailed(socket, error))
  }

  sendAuthenticate() {
    // Required to connect a socket to a user
    if (!this.socket) return
    this.socket.emit('auth', this.$store.getters['user/getToken'])
  }

  closeCurrentSocket() {
    const socket = this.socket
    if (!socket) return

    socket.removeAllListeners()
    if (socket.io && socket.io.removeAllListeners) {
      socket.io.removeAllListeners()
    }
    socket.disconnect()
    this.socket = null
    this.connected = false
    this.isAuthenticated = false
    this.$store.commit('setSocketConnected', false)
    this.emit('connection-update', false)
  }

  isCurrentSocket(socket) {
    return socket && socket === this.socket
  }

  onConnect(socket) {
    if (!this.isCurrentSocket(socket)) return
    console.log('[SOCKET] Socket Connected ' + socket.id)
    this.connected = true
    this.$store.commit('setSocketConnected', true)
    this.emit('connection-update', true)
    this.sendAuthenticate()
  }

  onReconnectAttempt(socket, attemptNumber) {
    if (!this.isCurrentSocket(socket)) return
    const timeSinceLastReconnectAttempt = this.lastReconnectAttemptTime ? Date.now() - this.lastReconnectAttemptTime : 0
    this.lastReconnectAttemptTime = Date.now()
    console.log(`[SOCKET] Reconnect attempt ${attemptNumber} ${timeSinceLastReconnectAttempt > 0 ? `after ${timeSinceLastReconnectAttempt}ms` : ''}`)
  }

  onReconnectError(socket, error) {
    if (!this.isCurrentSocket(socket)) return
    console.log('[SOCKET] Reconnect error', error)
  }

  onReconnectFailed(socket, error) {
    if (!this.isCurrentSocket(socket)) return
    console.log('[SOCKET] Reconnect failed', error)
  }

  onDisconnect(socket, reason) {
    if (!this.isCurrentSocket(socket)) return
    console.log('[SOCKET] Socket Disconnected: ' + reason)
    this.connected = false
    this.isAuthenticated = false
    this.$store.commit('setSocketConnected', false)
    this.emit('connection-update', false)
  }

  onInit(socket, data) {
    if (!this.isCurrentSocket(socket)) return
    console.log('[SOCKET] Initial socket data received', data)
    this.emit('initialized', true)
    this.isAuthenticated = true
  }

  onAuthFailed(socket, data) {
    if (!this.isCurrentSocket(socket)) return
    console.log('[SOCKET] Auth failed: ' + (data?.message || 'Unknown reason'))
    this.isAuthenticated = false
  }

  onUserUpdated(socket, data) {
    if (!this.isCurrentSocket(socket)) return
    console.log('[SOCKET] User updated', data)
    this.emit('user_updated', data)
  }

  onUserItemProgressUpdated(socket, payload) {
    if (!this.isCurrentSocket(socket)) return
    console.log('[SOCKET] User Item Progress Updated', JSON.stringify(payload))
    this.$store.commit('user/updateUserMediaProgress', payload.data)
    this.emit('user_media_progress_updated', payload)
  }

  onPlaylistAdded(socket) {
    if (!this.isCurrentSocket(socket)) return
    // Currently numUserPlaylists is only used for showing the playlist tab or not. Precise number is not necessary
    if (!this.$store.state.libraries.numUserPlaylists) {
      this.$store.commit('libraries/setNumUserPlaylists', 1)
    }
  }
}

export default ({ app, store }, inject) => {
  inject('socket', new ServerSocket(store))
}
