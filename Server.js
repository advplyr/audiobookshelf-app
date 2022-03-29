import { io } from 'socket.io-client'
import { Storage } from '@capacitor/storage'
import EventEmitter from 'events'

class Server extends EventEmitter {
  constructor(store, $axios) {
    super()

    this.store = store
    this.$axios = $axios

    this.url = null
    this.socket = null

    this.user = null
    this.connected = false
    this.initialized = false

    this.stream = null

    this.isConnectingSocket = false
  }

  get token() {
    return this.user ? this.user.token : null
  }

  getAxiosConfig() {
    return { headers: { Authorization: `Bearer ${this.token}` } }
  }

  getServerUrl(url) {
    if (!url) return null
    try {
      var urlObject = new URL(url)
      return `${urlObject.protocol}//${urlObject.hostname}:${urlObject.port}`
    } catch (error) {
      console.error('Invalid URL', error)
      return null
    }
  }

  setUser(user) {
    this.user = user
    this.store.commit('user/setUser', user)
    if (user) {
      // this.store.commit('user/setSettings', user.settings)
      Storage.set({ key: 'token', value: user.token })
    } else {
      Storage.remove({ key: 'token' })
    }
  }

  setServerUrl(url) {
    this.url = url
    this.store.commit('setServerUrl', url)

    if (url) {
      Storage.set({ key: 'serverUrl', value: url })
    } else {
      Storage.remove({ key: 'serverUrl' })
    }
  }

  async connect(url, token) {
    if (this.connected) {
      console.warn('[SOCKET] Connection already established for ' + this.url)
      return { success: true }
    }
    if (!url) {
      console.error('Invalid url to connect')
      return {
        error: 'Invalid URL'
      }
    }

    var serverUrl = this.getServerUrl(url)
    var res = await this.ping(serverUrl)

    if (!res || !res.success) {
      return {
        error: res ? res.error : 'Unknown Error'
      }
    }
    var authRes = await this.authorize(serverUrl, token)
    if (!authRes || authRes.error) {
      return {
        error: authRes ? authRes.error : 'Authorization Error'
      }
    }

    this.setServerUrl(serverUrl)

    this.setUser(authRes.user)
    this.connectSocket()

    return { success: true }
  }

  async check(url) {
    var serverUrl = this.getServerUrl(url)
    if (!serverUrl) {
      return {
        error: 'Invalid server url'
      }
    }
    var res = await this.ping(serverUrl)
    if (!res || res.error) {
      return {
        error: res ? res.error : 'Ping Failed'
      }
    }
    return {
      success: true,
      serverUrl
    }
  }

  async login(url, username, password) {
    var serverUrl = this.getServerUrl(url)
    var authUrl = serverUrl + '/login'
    return this.$axios.post(authUrl, { username, password }).then((res) => {
      if (!res.data || !res.data.user) {
        console.error(res.data.error)
        return {
          error: res.data.error || 'Unknown Error'
        }
      }

      this.setServerUrl(serverUrl)
      this.setUser(res.data.user)
      this.connectSocket()
      return {
        user: res.data.user
      }
    }).catch(error => {
      console.error('[Server] Server auth failed', error)
      var errorMsg = null
      if (error.response) {
        errorMsg = error.response.data || 'Unknown Error'
      } else if (error.request) {
        errorMsg = 'Server did not respond'
      } else {
        errorMsg = 'Failed to send request'
      }
      return {
        error: errorMsg
      }
    })
  }

  logout() {
    this.setUser(null)
    this.stream = null
    if (this.socket) {
      this.socket.disconnect()
    }
    this.emit('logout')
  }

  authorize(serverUrl, token) {
    var authUrl = serverUrl + '/api/authorize'
    return this.$axios.post(authUrl, null, { headers: { Authorization: `Bearer ${token}` } }).then((res) => {
      return res.data
    }).catch(error => {
      console.error('[Server] Server auth failed', error)
      var errorMsg = null
      if (error.response) {
        errorMsg = error.response.data || 'Unknown Error'
      } else if (error.request) {
        errorMsg = 'Server did not respond'
      } else {
        errorMsg = 'Failed to send request'
      }
      return {
        error: errorMsg
      }
    })
  }

  ping(url) {
    var pingUrl = url + '/ping'
    console.log('[Server] Check server', pingUrl)
    return this.$axios.get(pingUrl, { timeout: 1000 }).then((res) => {
      return res.data
    }).catch(error => {
      console.error('Server check failed', error)
      var errorMsg = null
      if (error.response) {
        errorMsg = error.response.data || 'Unknown Error'
      } else if (error.request) {
        errorMsg = 'Server did not respond'
      } else {
        errorMsg = 'Failed to send request'
      }
      return {
        success: false,
        error: errorMsg
      }
    })
  }

  connectSocket() {
    if (this.socket && !this.connected) {
      this.socket.connect()
      console.log('[SOCKET] Submitting connect')
      return
    }
    if (this.connected || this.socket) {
      if (this.socket) console.error('[SOCKET] Socket already established', this.url)
      else console.error('[SOCKET] Already connected to socket', this.url)
      return
    }

    console.log('[SOCKET] Connect Socket', this.url)

    const socketOptions = {
      transports: ['websocket'],
      upgrade: false,
      // reconnectionAttempts: 3
    }
    this.socket = io(this.url, socketOptions)
    this.socket.on('connect', () => {
      console.log('[SOCKET] Socket Connected ' + this.socket.id)

      // Authenticate socket with token
      this.socket.emit('auth', this.token)
      this.connected = true
      this.emit('connected', true)
      this.store.commit('setSocketConnected', true)
    })
    this.socket.on('disconnect', (reason) => {
      console.log('[SOCKET] Socket Disconnected: ' + reason)
      this.connected = false
      this.emit('connected', false)
      this.emit('initialized', false)
      this.initialized = false
      this.store.commit('setSocketConnected', false)

      // this.socket.removeAllListeners()
      // if (this.socket.io && this.socket.io.removeAllListeners) {
      //   console.log(`[SOCKET] Removing ALL IO listeners`)
      //   this.socket.io.removeAllListeners()
      // }
    })
    this.socket.on('init', (data) => {
      console.log('[SOCKET] Initial socket data received', data)
      if (data.stream) {
        this.stream = data.stream
        this.store.commit('setStreamAudiobook', data.stream.audiobook)
        this.emit('initialStream', data.stream)
      }
      if (data.serverSettings) {
        this.store.commit('setServerSettings', data.serverSettings)
      }
      this.initialized = true
      this.emit('initialized', true)
    })

    this.socket.on('user_updated', (user) => {
      if (this.user && user.id === this.user.id) {
        this.setUser(user)
      }
    })

    this.socket.on('current_user_audiobook_update', (payload) => {
      this.emit('currentUserAudiobookUpdate', payload)
    })

    this.socket.on('show_error_toast', (payload) => {
      this.emit('show_error_toast', payload)
    })
    this.socket.on('show_success_toast', (payload) => {
      this.emit('show_success_toast', payload)
    })

    this.socket.onAny((evt, args) => {
      console.log(`[SOCKET] ${this.socket.id}: ${evt} ${JSON.stringify(args)}`)
    })

    this.socket.on('connect_error', (err) => {
      console.error('[SOCKET] connection failed', err)
      this.emit('socketConnectionFailed', err)
    })

    this.socket.io.on("reconnect_attempt", (attempt) => {
      console.log(`[SOCKET] Reconnect Attempt ${this.socket.id}: ${attempt}`)
    })

    this.socket.io.on("reconnect_error", (err) => {
      console.log(`[SOCKET] Reconnect Error ${this.socket.id}: ${err}`)
    })

    this.socket.io.on("reconnect_failed", () => {
      console.log(`[SOCKET] Reconnect Failed ${this.socket.id}`)
    })

    this.socket.io.on("reconnect", () => {
      console.log(`[SOCKET] Reconnect Success ${this.socket.id}`)
    })
  }
}

export default Server