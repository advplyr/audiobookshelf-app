<template>
  <div class="w-full max-w-md mx-auto px-2 sm:px-4 lg:px-8 z-10">
    <div v-show="!loggedIn" class="mt-8 bg-primary overflow-hidden shadow rounded-lg px-4 py-6 w-full">
      <template v-if="!showForm">
        <div v-for="config in serverConnectionConfigs" :key="config.id" class="flex items-center py-4 my-1 border-b border-white border-opacity-10 relative" @click="connectToServer(config)">
          <span class="material-icons-outlined text-xl text-gray-300">dns</span>
          <p class="pl-3 pr-6 text-base text-gray-200">{{ config.name }}</p>

          <div class="absolute top-0 right-0 h-full px-4 flex items-center" @click.stop="editServerConfig(config)">
            <span class="material-icons text-lg text-gray-300">more_vert</span>
          </div>
        </div>
        <div class="my-1 py-4 w-full">
          <ui-btn class="w-full" @click="newServerConfigClick">{{ $strings.ButtonAddNewServer }}</ui-btn>
        </div>
      </template>
      <div v-else class="w-full">
        <form v-show="!showAuth" @submit.prevent="submit" novalidate class="w-full">
          <div v-if="serverConnectionConfigs.length" class="flex items-center mb-4" @click="showServerList">
            <span class="material-icons text-gray-300">arrow_back</span>
          </div>
          <h2 class="text-lg leading-7 mb-2">{{ $strings.LabelServeraddress }}</h2>
          <ui-text-input v-model="serverConfig.address" :disabled="processing || !networkConnected || !!serverConfig.id" placeholder="http://55.55.55.55:13378" type="url" class="w-full h-10" />
          <div class="flex justify-end items-center mt-6">
            <!-- <div class="relative flex">
              <button class="outline-none uppercase tracking-wide font-semibold text-xs text-gray-300" type="button" @click="addCustomHeaders">Add Custom Headers</button>
              <div v-if="numCustomHeaders" class="rounded-full h-5 w-5 flex items-center justify-center text-xs bg-success bg-opacity-40 leading-3 font-semibold font-mono ml-1">{{ numCustomHeaders }}</div>
            </div> -->

            <ui-btn :disabled="processing || !networkConnected" type="submit" :padding-x="3" class="h-10">{{ networkConnected ? $strings.ButtonSubmit : $strings.ButtonNoInternet }}</ui-btn>
          </div>
        </form>
        <template v-if="showAuth">
          <div v-if="serverConfig.id" class="flex items-center mb-4" @click="showServerList">
            <span class="material-icons text-gray-300">arrow_back</span>
          </div>

          <div class="flex items-center">
            <p class="text-gray-300">{{ serverConfig.address }}</p>
            <div class="flex-grow" />
            <span v-if="!serverConfig.id" class="material-icons" style="font-size: 1.1rem" @click="editServerAddress">edit</span>
          </div>
          <div class="w-full h-px bg-white bg-opacity-10 my-2" />
          <form v-if="isLocalAuthEnabled" @submit.prevent="submitAuth" class="pt-3">
            <ui-text-input v-model="serverConfig.username" :disabled="processing" :placeholder="$strings.PlaceholderUsername" class="w-full mb-2 text-lg" />
            <ui-text-input v-model="password" type="password" :disabled="processing" :placeholder="$strings.PlaceholderPassword" class="w-full mb-2 text-lg" />

            <div class="flex items-center pt-2">
              <ui-icon-btn v-if="serverConfig.id" small bg-color="error" icon="delete" @click="removeServerConfigClick" />
              <div class="flex-grow" />
              <ui-btn :disabled="processing || !networkConnected" type="submit" class="mt-1 h-10">{{ networkConnected ? $strings.ButtonSubmit : $strings.ButtonNoInternet }}</ui-btn>
            </div>
          </form>
          <div v-if="isLocalAuthEnabled && isOpenIDAuthEnabled" class="w-full h-px bg-white bg-opacity-10 my-2" />
          <ui-btn v-if="isOpenIDAuthEnabled" :disabled="processing" class="mt-1 h-10" @click="clickLoginWithOpenId">Login with OpenId</ui-btn>
        </template>
      </div>

      <div v-show="error" class="w-full rounded-lg bg-red-600 bg-opacity-10 border border-error border-opacity-50 py-3 px-2 flex items-center mt-4">
        <span class="material-icons mr-2 text-error" style="font-size: 1.1rem">warning</span>
        <p class="text-error">{{ error }}</p>
      </div>
    </div>

    <div :class="processing ? 'opacity-100' : 'opacity-0 pointer-events-none'" class="fixed w-full h-full top-0 left-0 bg-black bg-opacity-75 flex items-center justify-center z-30 transition-opacity duration-500">
      <div>
        <div class="absolute top-0 left-0 w-full p-6 flex items-center flex-col justify-center z-0 short:hidden">
          <img src="/Logo.png" class="h-20 w-20 mb-2" />
        </div>
        <svg class="animate-spin w-16 h-16" viewBox="0 0 24 24">
          <path fill="currentColor" d="M12,4V2A10,10 0 0,0 2,12H4A8,8 0 0,1 12,4Z" />
        </svg>
      </div>
    </div>

    <modals-custom-headers-modal v-model="showAddCustomHeaders" :custom-headers.sync="serverConfig.customHeaders" />
  </div>
</template>

<script>
import { OAuth2Client } from '@byteowls/capacitor-oauth2'
import { CapacitorHttp } from '@capacitor/core'
import { Dialog } from '@capacitor/dialog'

export default {
  data() {
    return {
      loggedIn: false,
      showAuth: false,
      processing: false,
      serverConfig: {
        address: null,
        username: null,
        customHeaders: null
      },
      password: null,
      error: null,
      showForm: false,
      showAddCustomHeaders: false,
      authMethods: []
    }
  },
  computed: {
    deviceData() {
      return this.$store.state.deviceData || {}
    },
    networkConnected() {
      return this.$store.state.networkConnected
    },
    serverConnectionConfigs() {
      return this.deviceData ? this.deviceData.serverConnectionConfigs || [] : []
    },
    lastServerConnectionConfigId() {
      return this.deviceData ? this.deviceData.lastServerConnectionConfigId : null
    },
    lastServerConnectionConfig() {
      if (!this.lastServerConnectionConfigId || !this.serverConnectionConfigs.length) return null
      return this.serverConnectionConfigs.find((s) => s.id == this.lastServerConnectionConfigId)
    },
    numCustomHeaders() {
      if (!this.serverConfig.customHeaders) return 0
      return Object.keys(this.serverConfig.customHeaders).length
    },
    isLocalAuthEnabled() {
      return this.authMethods.includes('local') || !this.authMethods.length
    },
    isOpenIDAuthEnabled() {
      return this.authMethods.includes('openid')
    }
  },
  methods: {
    async clickLoginWithOpenId() {
      this.error = ''
      const options = {
        authorizationBaseUrl: `${this.serverConfig.address}/auth/openid`,
        logsEnabled: true,
        web: {
          appId: 'com.audiobookshelf.web',
          responseType: 'token',
          redirectUrl: location.origin
        },
        android: {
          appId: 'com.audiobookshelf.app',
          responseType: 'code',
          redirectUrl: 'com.audiobookshelf.app:/'
        }
      }
      OAuth2Client.authenticate(options)
        .then(async (response) => {
          const token = response.authorization_response?.additional_parameters?.setToken || response.authorization_response?.setToken
          if (token) {
            this.serverConfig.token = token
            const payload = await this.authenticateToken()
            if (payload) {
              this.setUserAndConnection(payload)
            } else {
              this.showAuth = true
            }
          } else {
            this.error = 'Invalid response: No token'
          }
        })
        .catch((error) => {
          console.error('OAuth rejected', error)
          this.error = error.toString?.() || error.message
        })
    },
    addCustomHeaders() {
      this.showAddCustomHeaders = true
    },
    showServerList() {
      this.showForm = false
      this.showAuth = false
      this.error = null
      this.serverConfig = {
        address: null,
        userId: null,
        username: null
      }
    },
    async connectToServer(config) {
      await this.$hapticsImpact()
      console.log('[ServerConnectForm] connectToServer', config.address)
      this.processing = true
      this.serverConfig = {
        ...config
      }
      this.showForm = true
      var success = await this.pingServerAddress(config.address)
      this.processing = false
      console.log(`[ServerConnectForm] pingServer result ${success}`)
      if (!success) {
        this.showForm = false
        this.showAuth = false
        console.log(`[ServerConnectForm] showForm ${this.showForm}`)
        return
      }

      this.error = null
      var payload = await this.authenticateToken()

      if (payload) {
        this.setUserAndConnection(payload)
      } else {
        this.showAuth = true
      }
    },
    async removeServerConfigClick() {
      if (!this.serverConfig.id) return
      await this.$hapticsImpact()

      const { value } = await Dialog.confirm({
        title: this.$strings.LabelConfirm,
        message: this.$strings.MessageRemoveThisServerConfig
      })
      if (value) {
        this.processing = true
        await this.$db.removeServerConnectionConfig(this.serverConfig.id)
        const updatedDeviceData = { ...this.deviceData }
        updatedDeviceData.serverConnectionConfigs = this.deviceData.serverConnectionConfigs.filter((scc) => scc.id != this.serverConfig.id)
        this.$store.commit('setDeviceData', updatedDeviceData)

        this.serverConfig = {
          address: null,
          userId: null,
          username: null
        }
        this.password = null
        this.processing = false
        this.showAuth = false
        this.showForm = !this.serverConnectionConfigs.length
      }
    },
    editServerConfig(serverConfig) {
      this.serverConfig = {
        ...serverConfig
      }
      this.showForm = true
      this.showAuth = true
    },
    async newServerConfigClick() {
      await this.$hapticsImpact()
      this.serverConfig = {
        address: '',
        userId: '',
        username: ''
      }
      this.showForm = true
      this.showAuth = false
      this.error = null
    },
    editServerAddress() {
      this.error = null
      this.showAuth = false
    },
    validateServerUrl(url) {
      try {
        var urlObject = new URL(url)
        var address = `${urlObject.protocol}//${urlObject.hostname}`
        if (urlObject.port) address += ':' + urlObject.port
        return address
      } catch (error) {
        console.error('Invalid URL', error)
        return null
      }
    },
    async getRequest(url, headers, connectTimeout = 6000) {
      const options = {
        url,
        headers,
        connectTimeout
      }
      const response = await CapacitorHttp.get(options)
      console.log('[ServerConnectForm] GET request response', response)
      if (response.status >= 400) {
        throw new Error(response.data)
      } else {
        return response.data
      }
    },
    async postRequest(url, data, headers, connectTimeout = 6000) {
      if (!headers) headers = {}
      if (!headers['Content-Type'] && data) {
        headers['Content-Type'] = 'application/json'
      }
      const options = {
        url,
        headers,
        data,
        connectTimeout
      }
      const response = await CapacitorHttp.post(options)
      console.log('[ServerConnectForm] POST request response', response)
      if (response.status >= 400) {
        throw new Error(response.data)
      } else {
        return response.data
      }
    },
    /**
     * Get request to server /status api endpoint
     *
     * @param {string} address
     * @returns {Promise<{isInit:boolean, language:string, authMethods:string[]}>}
     */
    getServerAddressStatus(address) {
      return this.getRequest(`${address}/status`).catch((error) => {
        console.error('Failed to get server status', error)
        const errorMsg = error.message || error
        this.error = 'Failed to ping server'
        if (typeof errorMsg === 'string') {
          this.error += ` (${errorMsg})`
        }
        return null
      })
    },
    pingServerAddress(address, customHeaders) {
      return this.getRequest(`${address}/ping`, customHeaders)
        .then((data) => {
          return data.success
        })
        .catch((error) => {
          console.error('Server ping failed', error)
          const errorMsg = error.message || error
          this.error = 'Failed to ping server'
          if (typeof errorMsg === 'string') {
            this.error += ` (${errorMsg})`
          }

          return false
        })
    },
    requestServerLogin() {
      return this.postRequest(`${this.serverConfig.address}/login`, { username: this.serverConfig.username, password: this.password }, this.serverConfig.customHeaders, 20000)
        .then((data) => {
          if (!data.user) {
            console.error(data.error)
            this.error = data.error || 'Unknown Error'
            return false
          }
          return data
        })
        .catch((error) => {
          console.error('Server auth failed', error)
          const errorMsg = error.message || error
          this.error = 'Failed to login'
          if (typeof errorMsg === 'string') {
            this.error += ` (${errorMsg})`
          }
          return false
        })
    },
    async submit() {
      if (!this.networkConnected) return
      if (!this.serverConfig.address) return
      if (!this.serverConfig.address.startsWith('http')) {
        this.serverConfig.address = 'http://' + this.serverConfig.address
      }
      var validServerAddress = this.validateServerUrl(this.serverConfig.address)
      if (!validServerAddress) {
        this.error = 'Invalid server address'
        return
      }

      this.serverConfig.address = validServerAddress
      this.processing = true
      this.error = null
      this.authMethods = []

      const statusData = await this.getServerAddressStatus(this.serverConfig.address)
      this.processing = false
      if (statusData) {
        if (!statusData.isInit) {
          this.error = 'Server is not initialized'
        } else {
          this.showAuth = true
          this.authMethods = statusData.authMethods || []
        }
      }
    },
    async submitAuth() {
      if (!this.networkConnected) return
      if (!this.serverConfig.username) {
        this.error = 'Invalid username'
        return
      }

      const duplicateConfig = this.serverConnectionConfigs.find((scc) => scc.address === this.serverConfig.address && scc.username === this.serverConfig.username && this.serverConfig.id !== scc.id)
      if (duplicateConfig) {
        this.error = 'Config already exists for this address and username'
        return
      }

      this.error = null
      this.processing = true

      var payload = await this.requestServerLogin()
      this.processing = false
      if (payload) {
        this.setUserAndConnection(payload)
      }
    },
    async setUserAndConnection({ user, userDefaultLibraryId, serverSettings, ereaderDevices }) {
      if (!user) return

      console.log('Successfully logged in', JSON.stringify(user))

      this.$store.commit('setServerSettings', serverSettings)
      this.$store.commit('libraries/setEReaderDevices', ereaderDevices)

      // Set library - Use last library if set and available fallback to default user library
      var lastLibraryId = await this.$localStore.getLastLibraryId()
      if (lastLibraryId && (!user.librariesAccessible.length || user.librariesAccessible.includes(lastLibraryId))) {
        this.$store.commit('libraries/setCurrentLibrary', lastLibraryId)
      } else if (userDefaultLibraryId) {
        this.$store.commit('libraries/setCurrentLibrary', userDefaultLibraryId)
      }

      this.serverConfig.userId = user.id
      this.serverConfig.token = user.token
      this.serverConfig.username = user.username

      var serverConnectionConfig = await this.$db.setServerConnectionConfig(this.serverConfig)

      this.$store.commit('user/setUser', user)
      this.$store.commit('user/setServerConnectionConfig', serverConnectionConfig)

      this.$socket.connect(this.serverConfig.address, this.serverConfig.token)
      this.$router.replace('/bookshelf')
    },
    async authenticateToken() {
      if (!this.networkConnected) return
      if (!this.serverConfig.token) {
        this.error = 'No token'
        return
      }

      this.error = null
      this.processing = true

      const authRes = await this.postRequest(`${this.serverConfig.address}/api/authorize`, null, { Authorization: `Bearer ${this.serverConfig.token}` }).catch((error) => {
        console.error('[ServerConnectForm] Server auth failed', error)
        const errorMsg = error.message || error
        this.error = 'Failed to authorize'
        if (typeof errorMsg === 'string') {
          this.error += ` (${errorMsg})`
        }
        return false
      })

      console.log('[ServerConnectForm] authRes=', authRes)

      this.processing = false
      return authRes
    },
    async init() {
      if (this.lastServerConnectionConfig) {
        this.connectToServer(this.lastServerConnectionConfig)
      } else {
        this.showForm = !this.serverConnectionConfigs.length
      }
    }
  },
  mounted() {
    this.init()
  }
}
</script>
