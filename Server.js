import { io } from 'socket.io-client'
import axios from 'axios'
import EventEmitter from 'events'

class Server extends EventEmitter {
  constructor(store) {
    super()

    this.store = store

    this.url = null
    this.socket = null

    this.user = null
    this.connected = false

    this.stream = null
  }

  get token() {
    return this.user ? this.user.token : null
  }

  getAxiosConfig() {
    return { headers: { Authorization: `Bearer ${this.token}` } }
  }

  getServerUrl(url) {
    var urlObject = new URL(url)
    return `${urlObject.protocol}//${urlObject.hostname}:${urlObject.port}`
  }

  setUser(user) {
    this.user = user
    this.store.commit('user/setUser', user)
    if (user) {
      this.store.commit('user/setSettings', user.settings)
      localStorage.setItem('userToken', user.token)
    } else {
      localStorage.removeItem('userToken')
    }
  }

  setServerUrl(url) {
    this.url = url
    localStorage.setItem('serverUrl', url)
    this.store.commit('setServerUrl', url)
  }

  async connect(url, token) {
    var serverUrl = this.getServerUrl(url)
    var res = await this.ping(serverUrl)
    if (!res || !res.success) {
      this.url = null
      return false
    }
    var authRes = await this.authorize(serverUrl, token)
    if (!authRes || !authRes.user) {
      return false
    }

    this.setServerUrl(serverUrl)
    console.warn('Connect setting auth user', authRes)
    this.setUser(authRes.user)
    this.connectSocket()

    return true
  }

  async check(url) {
    var serverUrl = this.getServerUrl(url)
    var res = await this.ping(serverUrl)
    if (!res || !res.success) {
      return false
    }
    return serverUrl
  }

  async login(url, username, password) {
    var serverUrl = this.getServerUrl(url)
    var authUrl = serverUrl + '/login'
    return axios.post(authUrl, { username, password }).then((res) => {
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
      return {
        error: 'Request Failed'
      }
    })
  }

  logout() {
    this.setUser(null)
  }

  authorize(serverUrl, token) {
    var authUrl = serverUrl + '/api/authorize'
    return axios.post(authUrl, null, { headers: { Authorization: `Bearer ${token}` } }).then((res) => {
      return res.data
    }).catch(error => {
      console.error('[Server] Server auth failed', error)
      return false
    })
  }

  ping(url) {
    var pingUrl = url + '/ping'
    console.log('[Server] Check server', pingUrl)
    return axios.get(pingUrl).then((res) => {
      return res.data
    }).catch(error => {
      console.error('Server check failed', error)
      return false
    })
  }

  connectSocket() {
    console.log('[SERVER] Connect Socket', this.url)

    this.socket = io(this.url)
    this.socket.on('connect', () => {
      console.log('[Server] Socket Connected')

      // Authenticate socket with token
      this.socket.emit('auth', this.token)

      this.connected = true
      this.emit('connected', true)
    })
    this.socket.on('disconnect', () => {
      console.log('[Server] Socket Disconnected')
    })
    this.socket.on('init', (data) => {
      console.log('[Server] Initial socket data received', data)
      if (data.stream) {
        this.stream = data.stream
        this.store.commit('setStreamAudiobook', data.stream.audiobook)
        this.emit('initialStream', data.stream)
      }
    })
    this.socket.on('user_updated', (user) => {
      if (this.user && user.id === this.user.id) {
        this.setUser(user)
      }
    })
  }
}

export default Server