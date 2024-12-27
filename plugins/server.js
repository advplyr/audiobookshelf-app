import { io } from 'socket.io-client'
import EventEmitter from 'events'

class ServerSocket extends EventEmitter {
  constructor(store) {
    super()

    this.$store = store
    this.socket = null
    this.connected = false
    this.serverAddress = null
    this.token = null
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
    this.serverAddress = serverAddress
    this.token = token

    const serverUrl = new URL(serverAddress)
    const serverHost = `${serverUrl.protocol}//${serverUrl.host}`
    const serverPath = serverUrl.pathname === '/' ? '' : serverUrl.pathname

    console.log(`[SOCKET] Connecting to ${serverHost} with path ${serverPath}/socket.io`)

    const socketOptions = {
      transports: ['websocket'],
      upgrade: false,
      path: `${serverPath}/socket.io`
      // reconnectionAttempts: 3
    }
    this.socket = io(serverHost, socketOptions)
    this.setSocketListeners()
  }

  logout() {
    if (this.socket) this.socket.disconnect()
    this.removeListeners()
  }

  setSocketListeners() {
    this.socket.on('connect', this.onConnect.bind(this))
    this.socket.on('disconnect', this.onDisconnect.bind(this))
    this.socket.on('init', this.onInit.bind(this))
    this.socket.on('user_updated', this.onUserUpdated.bind(this))
    this.socket.on('user_item_progress_updated', this.onUserItemProgressUpdated.bind(this))
    this.socket.on('playlist_added', this.onPlaylistAdded.bind(this))
  }

  removeListeners() {
    if (!this.socket) return
    this.socket.removeAllListeners()
    if (this.socket.io && this.socket.io.removeAllListeners) {
      this.socket.io.removeAllListeners()
    }
  }

  onConnect() {
    console.log('[SOCKET] Socket Connected ' + this.socket.id)
    this.connected = true
    this.$store.commit('setSocketConnected', true)
    this.emit('connection-update', true)
    this.socket.emit('auth', this.token) // Required to connect a user with their socket
  }

  onDisconnect(reason) {
    console.log('[SOCKET] Socket Disconnected: ' + reason)
    this.connected = false
    this.$store.commit('setSocketConnected', false)
    this.emit('connection-update', false)
  }

  onInit(data) {
    console.log('[SOCKET] Initial socket data received', data)
    this.emit('initialized', true)
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

export default ({ app, store }, inject) => {
  inject('socket', new ServerSocket(store))
}
