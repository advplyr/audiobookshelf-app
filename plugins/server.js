import Vue from 'vue'
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
    if (!this.socket) return
    this.socket.on('connect', this.onConnect.bind(this))
    this.socket.on('disconnect', this.onDisconnect.bind(this))
    this.socket.on('init', this.onInit.bind(this))

    this.socket.onAny((evt, args) => {
      console.log(`[SOCKET] ${this.socket.id}: ${evt} ${JSON.stringify(args)}`)
    })

  }

  onConnect() {
    console.log('[SOCKET] Socket Connected ' + this.socket.id)
    this.connected = true
    this.$store.commit('setSocketConnected', true)
    this.emit('connection-update', true)
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
}

export default ({ app, store }, inject) => {
  console.log('Check event bus', this, Vue.prototype.$eventBus)
  inject('socket', new ServerSocket(store))
}
