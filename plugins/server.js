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

    console.log('[SOCKET] Connect Socket', this.serverAddress)

    const socketOptions = {
      transports: ['websocket'],
      upgrade: false,
      // reconnectionAttempts: 3
    }
    this.socket = io(this.serverAddress, socketOptions)
    this.setSocketListeners()
  }

  logout() {
    if (this.socket) this.socket.disconnect()
  }

  setSocketListeners() {
    this.socket.on('connect', this.onConnect.bind(this))
    this.socket.on('disconnect', this.onDisconnect.bind(this))
    this.socket.on('init', this.onInit.bind(this))
    this.socket.on('user_updated', this.onUserUpdated.bind(this))
    this.socket.on('user_item_progress_updated', this.onUserItemProgressUpdated.bind(this))

    // Good for testing socket requests
    // this.socket.onAny((evt, args) => {
    //   console.log(`[SOCKET] onAny: ${this.socket.id}: ${evt} ${JSON.stringify(args)}`)
    // })
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

    this.socket.removeAllListeners()
    if (this.socket.io && this.socket.io.removeAllListeners) {
      this.socket.io.removeAllListeners()
    }
  }

  onInit(data) {
    console.log('[SOCKET] Initial socket data received', data)
    if (data.serverSettings) {
      this.$store.commit('setServerSettings', data.serverSettings)
    }
    this.emit('initialized', true)
  }

  onUserUpdated(data) {
    console.log('[SOCKET] User updated', data)
    this.emit('user_updated', data)
  }

  onUserItemProgressUpdated(data) {
    console.log('[SOCKET] User Item Progress Updated', JSON.stringify(data))
    var progress = data.data
    this.$store.commit('user/updateUserMediaProgress', progress)
  }
}

export default ({ app, store }, inject) => {
  inject('socket', new ServerSocket(store))
}
