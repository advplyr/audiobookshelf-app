import EventEmitter from 'events'

const AUTH_TIMEOUT_MS = 8000
const SERVER_DISCONNECT_RETRY_MS = 1000

export class ServerSocket extends EventEmitter {
  constructor(store, socketFactory, options = {}) {
    super()

    this.$store = store
    this.socketFactory = socketFactory
    this.socket = null
    this.connected = false
    this.serverAddress = null
    this.fallbackToken = null
    this.isAuthenticated = false
    this.isIntentionalDisconnect = false
    this.lastReconnectAttemptTime = 0

    this.authTimeoutMs = options.authTimeoutMs || AUTH_TIMEOUT_MS
    this.serverDisconnectRetryMs = options.serverDisconnectRetryMs || SERVER_DISCONNECT_RETRY_MS
    this.setTimeoutFn = options.setTimeoutFn || setTimeout
    this.clearTimeoutFn = options.clearTimeoutFn || clearTimeout
    this.authWatchdogTimer = null
    this.authWatchdogStage = 0
    this.serverDisconnectTimer = null
  }

  $on(evt, callback) {
    if (this.socket) this.socket.on(evt, callback)
    else console.error('$on Socket not initialized')
  }

  $off(evt, callback) {
    if (this.socket) this.socket.off(evt, callback)
    else console.error('$off Socket not initialized')
  }

  connect(serverAddress, token = null) {
    if (!serverAddress) {
      console.warn('[SOCKET] Cannot connect without a server address')
      return false
    }

    if (this.socket && this.serverAddress === serverAddress) {
      if (token) this.fallbackToken = token
      return this.ensureConnected(serverAddress, 'connect-called')
    }

    this.disposeSocket()
    this.serverAddress = serverAddress
    this.fallbackToken = token
    this.isIntentionalDisconnect = false

    const serverUrl = new URL(serverAddress)
    const serverHost = `${serverUrl.protocol}//${serverUrl.host}`
    const serverPath = serverUrl.pathname === '/' ? '' : serverUrl.pathname.replace(/\/$/, '')

    console.log(`[SOCKET] Connecting to ${serverHost} with path ${serverPath}/socket.io`)

    const socketOptions = {
      transports: ['websocket'],
      upgrade: false,
      path: `${serverPath}/socket.io`,
      reconnection: true,
      reconnectionDelay: 1000,
      reconnectionDelayMax: 15000
    }
    this.setRecoveryState(true)
    this.socket = this.socketFactory(serverHost, socketOptions)
    this.setSocketListeners()
    return true
  }

  ensureConnected(serverAddress = null, reason = 'manual') {
    const address = serverAddress || this.serverAddress || this.$store.getters['user/getServerAddress']
    if (!address) {
      console.warn(`[SOCKET] Recovery skipped (${reason}): no server address`)
      return false
    }

    if (!this.socket || address !== this.serverAddress) {
      console.log(`[SOCKET] Creating socket during recovery (${reason})`)
      return this.connect(address)
    }

    if (this.socket.connected && this.isAuthenticated) {
      this.setRecoveryState(false)
      return false
    }

    console.log(`[SOCKET] Recovering connection (${reason})`)
    this.isIntentionalDisconnect = false
    this.setRecoveryState(true)

    if (this.socket.connected) {
      this.beginAuthentication()
    } else {
      this.socket.connect()
    }
    return true
  }

  logout() {
    this.isIntentionalDisconnect = true
    this.clearTimers()
    this.disposeSocket()
    this.serverAddress = null
    this.fallbackToken = null
    this.connected = false
    this.isAuthenticated = false
    this.commitConnectionState(false, false)
    this.setRecoveryState(false)
  }

  disposeSocket() {
    this.clearTimers()
    if (!this.socket) return

    this.removeListeners()
    this.socket.disconnect()
    this.socket = null
  }

  setSocketListeners() {
    this.socket.on('connect', this.onConnect.bind(this))
    this.socket.on('disconnect', this.onDisconnect.bind(this))
    this.socket.on('init', this.onInit.bind(this))
    this.socket.on('auth_failed', this.onAuthFailed.bind(this))
    this.socket.on('user_updated', this.onUserUpdated.bind(this))
    this.socket.on('user_item_progress_updated', this.onUserItemProgressUpdated.bind(this))
    this.socket.on('playlist_added', this.onPlaylistAdded.bind(this))
    this.socket.io.on('reconnect_attempt', this.onReconnectAttempt.bind(this))
    this.socket.io.on('reconnect_error', this.onReconnectError.bind(this))
    this.socket.io.on('reconnect_failed', this.onReconnectFailed.bind(this))
  }

  sendAuthenticate() {
    if (!this.socket?.connected) return false

    const token = this.$store.getters['user/getToken'] || this.fallbackToken
    if (!token) {
      console.warn('[SOCKET] Cannot authenticate without an access token')
      return false
    }

    this.socket.emit('auth', token)
    return true
  }

  beginAuthentication() {
    this.isAuthenticated = false
    this.$store.commit('setSocketAuthenticated', false)
    this.authWatchdogStage = 0
    this.clearAuthWatchdog()

    if (this.sendAuthenticate()) {
      this.armAuthWatchdog()
    } else {
      this.setRecoveryState(false, 'missing-access-token')
    }
  }

  armAuthWatchdog() {
    this.clearAuthWatchdog()
    this.authWatchdogTimer = this.setTimeoutFn(() => {
      this.authWatchdogTimer = null
      if (!this.socket?.connected || this.isAuthenticated) return

      if (this.authWatchdogStage === 0) {
        console.warn('[SOCKET] Authentication response timed out; retrying authentication')
        this.authWatchdogStage = 1
        this.sendAuthenticate()
        this.armAuthWatchdog()
        return
      }

      console.error('[SOCKET] Authentication timed out')
      this.setRecoveryState(false, 'authentication-timeout')
      this.emit('authentication-timeout')
    }, this.authTimeoutMs)
  }

  clearAuthWatchdog() {
    if (!this.authWatchdogTimer) return
    this.clearTimeoutFn(this.authWatchdogTimer)
    this.authWatchdogTimer = null
  }

  clearTimers() {
    this.clearAuthWatchdog()
    if (this.serverDisconnectTimer) {
      this.clearTimeoutFn(this.serverDisconnectTimer)
      this.serverDisconnectTimer = null
    }
  }

  removeListeners() {
    if (!this.socket) return
    this.socket.removeAllListeners()
    if (this.socket.io?.removeAllListeners) {
      this.socket.io.removeAllListeners()
    }
  }

  commitConnectionState(connected, authenticated) {
    this.$store.commit('setSocketConnected', connected)
    this.$store.commit('setSocketAuthenticated', authenticated)
  }

  setRecoveryState(recovering, error = null) {
    this.$store.commit('setSocketRecovering', recovering)
    this.$store.commit('setSocketConnectionError', error)
  }

  onConnect() {
    console.log('[SOCKET] Socket Connected ' + this.socket.id)
    this.connected = true
    this.isAuthenticated = false
    this.commitConnectionState(true, false)
    this.setRecoveryState(true)
    this.emit('connection-update', true)
    this.beginAuthentication()
  }

  onReconnectAttempt(attemptNumber) {
    const timeSinceLastReconnectAttempt = this.lastReconnectAttemptTime ? Date.now() - this.lastReconnectAttemptTime : 0
    this.lastReconnectAttemptTime = Date.now()
    this.setRecoveryState(true)
    console.log(`[SOCKET] Reconnect attempt ${attemptNumber} ${timeSinceLastReconnectAttempt > 0 ? `after ${timeSinceLastReconnectAttempt}ms` : ''}`)
  }

  onReconnectError(error) {
    console.log('[SOCKET] Reconnect error', error)
    this.$store.commit('setSocketConnectionError', error?.message || 'reconnect-error')
  }

  onReconnectFailed(error) {
    console.log('[SOCKET] Reconnect failed', error)
    this.setRecoveryState(false, error?.message || 'reconnect-failed')
  }

  onDisconnect(reason) {
    console.log('[SOCKET] Socket Disconnected: ' + reason)
    this.clearAuthWatchdog()
    this.connected = false
    this.isAuthenticated = false
    this.commitConnectionState(false, false)
    this.emit('connection-update', false)

    const shouldRecover = !this.isIntentionalDisconnect && reason !== 'io client disconnect'
    this.setRecoveryState(shouldRecover, shouldRecover ? reason : null)

    // Socket.IO does not automatically reconnect after a server-initiated disconnect.
    if (shouldRecover && reason === 'io server disconnect') {
      this.serverDisconnectTimer = this.setTimeoutFn(() => {
        this.serverDisconnectTimer = null
        this.ensureConnected(this.serverAddress, 'server-disconnect')
      }, this.serverDisconnectRetryMs)
    }
  }

  onInit(data) {
    console.log('[SOCKET] Initial socket data received', data)
    this.clearAuthWatchdog()
    this.isAuthenticated = true
    this.$store.commit('setSocketAuthenticated', true)
    this.setRecoveryState(false)
    this.emit('initialized', true)
  }

  onAuthFailed(data) {
    const message = data?.message || 'Unknown reason'
    console.log('[SOCKET] Auth failed: ' + message)
    this.clearAuthWatchdog()
    this.isAuthenticated = false
    this.$store.commit('setSocketAuthenticated', false)
    this.setRecoveryState(false, message)
    this.emit('authentication-failed', data)
  }

  onUserUpdated(data) {
    console.log('[SOCKET] User updated', data)
    this.emit('user_updated', data)
  }

  onUserItemProgressUpdated(payload) {
    console.log('[SOCKET] User Item Progress Updated', JSON.stringify(payload))
    this.$store.commit('user/updateUserMediaProgress', payload.data)
    this.emit('user_media_progress_updated', payload)
  }

  onPlaylistAdded() {
    // Currently numUserPlaylists is only used for showing the playlist tab or not. Precise number is not necessary
    if (!this.$store.state.libraries.numUserPlaylists) {
      this.$store.commit('libraries/setNumUserPlaylists', 1)
    }
  }
}
