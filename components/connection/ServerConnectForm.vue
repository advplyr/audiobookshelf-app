<template>
  <div class="w-full max-w-md mx-auto px-4 sm:px-6 lg:px-8 z-10">
    <div v-show="!loggedIn" class="mt-8 bg-primary overflow-hidden shadow rounded-lg p-6 w-full">
      <template v-if="!showForm">
        <div v-for="config in serverConnectionConfigs" :key="config.id" class="flex items-center py-4 my-1 border-b border-white border-opacity-10 relative" @click="connectToServer(config)">
          <span class="material-icons-outlined text-xl text-gray-300">dns</span>
          <p class="pl-3 pr-6 text-base text-gray-200">{{ config.name }}</p>

          <div class="absolute top-0 right-0 h-full px-4 flex items-center" @click.stop="editServerConfig(config)">
            <span class="material-icons text-lg text-gray-300">more_vert</span>
          </div>
        </div>
        <div class="my-1 py-4 w-full">
          <ui-btn class="w-full" @click="newServerConfigClick">Add New Server</ui-btn>
        </div>
      </template>
      <div v-else class="w-full">
        <form v-show="!showAuth" @submit.prevent="submit" novalidate class="w-full">
          <h2 class="text-lg leading-7 mb-2">Server address</h2>
          <ui-text-input v-model="serverConfig.address" :disabled="processing || !networkConnected || serverConfig.id" placeholder="http://55.55.55.55:13378" type="url" class="w-full sm:w-72 h-10" />
          <div class="flex justify-end">
            <ui-btn :disabled="processing || !networkConnected" type="submit" :padding-x="3" class="h-10 mt-4">{{ networkConnected ? 'Submit' : 'No Internet' }}</ui-btn>
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
          <form @submit.prevent="submitAuth" class="pt-3">
            <ui-text-input v-model="serverConfig.username" :disabled="processing" placeholder="username" class="w-full mb-2 text-lg" />
            <ui-text-input v-model="password" type="password" :disabled="processing" placeholder="password" class="w-full mb-2 text-lg" />

            <div class="flex items-center pt-2">
              <ui-icon-btn v-if="serverConfig.id" small bg-color="error" icon="delete" @click="removeServerConfigClick" />
              <div class="flex-grow" />
              <ui-btn :disabled="processing || !networkConnected" type="submit" class="mt-1 h-10">{{ networkConnected ? 'Submit' : 'No Internet' }}</ui-btn>
            </div>
          </form>
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
  </div>
</template>

<script>
import { Dialog } from '@capacitor/dialog'

export default {
  props: {},
  data() {
    return {
      deviceData: null,
      loggedIn: false,
      showAuth: false,
      processing: false,
      serverConfig: {
        address: null,
        username: null
      },
      password: null,
      error: null,
      showForm: false
    }
  },
  computed: {
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
    }
  },
  methods: {
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
        this.setUserAndConnection(payload.user, payload.userDefaultLibraryId)
      } else {
        this.showAuth = true
      }
    },
    async removeServerConfigClick() {
      if (!this.serverConfig.id) return

      const { value } = await Dialog.confirm({
        title: 'Confirm',
        message: `Remove this server config?`
      })
      if (value) {
        this.processing = true
        await this.$db.removeServerConnectionConfig(this.serverConfig.id)
        this.deviceData.serverConnectionConfigs = this.deviceData.serverConnectionConfigs.filter((scc) => scc.id != this.serverConfig.id)
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
      console.log('Edit server config', serverConfig)
    },
    newServerConfigClick() {
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
    pingServerAddress(address) {
      return this.$axios
        .$get(`${address}/ping`, { timeout: 1000 })
        .then((data) => data.success)
        .catch((error) => {
          console.error('Server check failed', error)
          this.error = 'Failed to ping server'
          return false
        })
    },
    requestServerLogin() {
      return this.$axios
        .$post(`${this.serverConfig.address}/login`, { username: this.serverConfig.username, password: this.password })
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
          var errorMsg = error.response ? error.response.data || 'Unknown Error' : 'Unknown Error'
          this.error = errorMsg
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

      var success = await this.pingServerAddress(this.serverConfig.address)
      this.processing = false
      if (success) this.showAuth = true
    },
    async submitAuth() {
      if (!this.networkConnected) return
      if (!this.serverConfig.username) {
        this.error = 'Invalid username'
        return
      }
      this.error = null
      this.processing = true

      var payload = await this.requestServerLogin()
      this.processing = false
      if (payload) {
        this.setUserAndConnection(payload.user, payload.userDefaultLibraryId)
      }
    },
    async setUserAndConnection(user, userDefaultLibraryId) {
      if (!user) return

      console.log('Successfully logged in', JSON.stringify(user))

      // Set library - Use last library if set and available fallback to default user library
      var lastLibraryId = await this.$localStore.getLastLibraryId()
      if (lastLibraryId && (!user.librariesAccessible.length || user.librariesAccessible.includes(lastLibraryId))) {
        this.$store.commit('libraries/setCurrentLibrary', lastLibraryId)
      } else if (userDefaultLibraryId) {
        this.$store.commit('libraries/setCurrentLibrary', userDefaultLibraryId)
      }

      this.serverConfig.userId = user.id
      this.serverConfig.token = user.token

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
      var authRes = await this.$axios.$post(`${this.serverConfig.address}/api/authorize`, null, { headers: { Authorization: `Bearer ${this.serverConfig.token}` } }).catch((error) => {
        console.error('[Server] Server auth failed', error)
        var errorMsg = error.response ? error.response.data || 'Unknown Error' : 'Unknown Error'
        this.error = errorMsg
        return false
      })
      this.processing = false
      return authRes
    },
    async init() {
      this.deviceData = await this.$db.getDeviceData()

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