<template>
  <div class="w-full layout-wrapper bg-bg text-white">
    <app-appbar />
    <div id="content" class="overflow-hidden relative" :class="playerIsOpen ? 'playerOpen' : ''">
      <Nuxt />
    </div>
    <app-audio-player-container ref="streamContainer" />
    <modals-libraries-modal />
    <app-side-drawer />
    <readers-reader />
  </div>
</template>

<script>
import { Capacitor } from '@capacitor/core'
import { AppUpdate } from '@robingenz/capacitor-app-update'
import AudioDownloader from '@/plugins/audio-downloader'
import StorageManager from '@/plugins/storage-manager'

export default {
  data() {
    return {}
  },
  watch: {
    networkConnected: {
      handler(newVal) {
        if (newVal) {
          this.attemptConnection()
        }
      }
    }
  },
  computed: {
    playerIsOpen() {
      return this.$store.getters['playerIsOpen']
    },
    routeName() {
      return this.$route.name
    },
    networkConnected() {
      return this.$store.state.networkConnected
    },
    user() {
      return this.$store.state.user.user
    },
    currentLibraryId() {
      return this.$store.state.libraries.currentLibraryId
    },
    isSocketConnected() {
      return this.$store.state.socketConnected
    }
  },
  methods: {
    initialStream(stream) {
      if (this.$refs.streamContainer && this.$refs.streamContainer.audioPlayerReady) {
        this.$refs.streamContainer.streamOpen(stream)
      }
    },
    async clickUpdateToast() {
      var immediateUpdateAllowed = this.$store.state.appUpdateInfo.immediateUpdateAllowed
      if (immediateUpdateAllowed) {
        await AppUpdate.performImmediateUpdate()
      } else {
        await AppUpdate.openAppStore()
      }
    },
    async checkForUpdate() {
      if (this.$platform == 'web') return
      console.log('Checking for app update')
      const result = await AppUpdate.getAppUpdateInfo()
      if (!result) {
        console.error('Invalid version check')
        return
      }
      console.log('App Update Info', JSON.stringify(result))
      this.$store.commit('setAppUpdateInfo', result)
      if (result.updateAvailability === 2) {
        setTimeout(() => {
          this.$toast.info(`Update is available! Click to update.`, {
            draggable: false,
            hideProgressBar: false,
            timeout: 20000,
            closeButton: true,
            onClick: this.clickUpdateToast
          })
        }, 5000)
      }
    },
    async searchFolder(downloadFolder) {
      try {
        var response = await StorageManager.searchFolder({ folderUrl: downloadFolder.uri })
        var searchResults = response
        searchResults.folders = JSON.parse(searchResults.folders)
        searchResults.files = JSON.parse(searchResults.files)

        console.log('Search folders results length', searchResults.folders.length)
        searchResults.folders = searchResults.folders.map((sr) => {
          if (sr.files) {
            sr.files = JSON.parse(sr.files)
          }
          return sr
        })

        return searchResults
      } catch (error) {
        console.error('Failed', error)
        this.$toast.error('Failed to search downloads folder')
        return {}
      }
    },
    // async syncDownloads(downloads, downloadFolder) {
    //   console.log('Syncing downloads ' + downloads.length)
    //   var mediaScanResults = await this.searchFolder(downloadFolder)

    //   this.$store.commit('downloads/setMediaScanResults', mediaScanResults)

    //   // Filter out media folders without any audio files
    //   var mediaFolders = mediaScanResults.folders.filter((sr) => {
    //     if (!sr.files) return false
    //     var audioFiles = sr.files.filter((mf) => !!mf.isAudio)
    //     return audioFiles.length
    //   })

    //   downloads.forEach((download) => {
    //     var mediaFolder = mediaFolders.find((mf) => mf.name === download.folderName)
    //     if (mediaFolder) {
    //       console.log('Found download ' + download.folderName)
    //       if (download.isMissing) {
    //         download.isMissing = false
    //         this.$store.commit('downloads/addUpdateDownload', download)
    //       }
    //     } else {
    //       console.error('Download not found ' + download.folderName)
    //       if (!download.isMissing) {
    //         download.isMissing = true
    //         this.$store.commit('downloads/addUpdateDownload', download)
    //       }
    //     }
    //   })
    // },
    onItemDownloadUpdate(data) {
      console.log('ON ITEM DOWNLOAD UPDATE', JSON.stringify(data))
    },
    onItemDownloadComplete(data) {
      console.log('ON ITEM DOWNLOAD COMPLETE', JSON.stringify(data))
    },
    async initMediaStore() {
      // Request and setup listeners for media files on native
      AudioDownloader.addListener('onItemDownloadUpdate', (data) => {
        this.onItemDownloadUpdate(data)
      })
      AudioDownloader.addListener('onItemDownloadComplete', (data) => {
        this.onItemDownloadComplete(data)
      })
    },
    async loadSavedSettings() {
      var userSavedServerSettings = await this.$localStore.getServerSettings()
      if (userSavedServerSettings) {
        this.$store.commit('setServerSettings', userSavedServerSettings)
      }

      var userSavedSettings = await this.$localStore.getUserSettings()
      if (userSavedSettings) {
        this.$store.commit('user/setSettings', userSavedSettings)
      }
    },
    async attemptConnection() {
      if (!this.networkConnected) {
        console.warn('No network connection')
        return
      }

      var deviceData = await this.$db.getDeviceData()
      var serverConfig = null
      if (deviceData && deviceData.lastServerConnectionConfigId && deviceData.serverConnectionConfigs.length) {
        serverConfig = deviceData.serverConnectionConfigs.find((scc) => scc.id == deviceData.lastServerConnectionConfigId)
      }
      if (!serverConfig) {
        // No last server config set
        return
      }

      var authRes = await this.$axios.$post(`${serverConfig.address}/api/authorize`, null, { headers: { Authorization: `Bearer ${serverConfig.token}` } }).catch((error) => {
        console.error('[Server] Server auth failed', error)
        var errorMsg = error.response ? error.response.data || 'Unknown Error' : 'Unknown Error'
        this.error = errorMsg
        return false
      })
      if (!authRes) return

      const { user, userDefaultLibraryId } = authRes
      if (userDefaultLibraryId) {
        this.$store.commit('libraries/setCurrentLibrary', userDefaultLibraryId)
      }
      var serverConnectionConfig = await this.$db.setServerConnectionConfig(serverConfig)

      this.$store.commit('user/setUser', user)
      this.$store.commit('user/setServerConnectionConfig', serverConnectionConfig)

      this.$socket.connect(serverConnectionConfig.address, serverConnectionConfig.token)

      console.log('Successful connection on last saved connection config', JSON.stringify(serverConnectionConfig))
      await this.initLibraries()
    },
    itemRemoved(libraryItem) {
      if (this.$route.name.startsWith('item')) {
        if (this.$route.params.id === libraryItem.id) {
          this.$router.replace(`/bookshelf`)
        }
      }
    },
    userLoggedOut() {
      // Only cancels stream if streamining not playing downloaded
      this.$eventBus.$emit('close-stream')
    },
    socketConnectionUpdate(isConnected) {
      console.log('Socket connection update', isConnected)
    },
    socketConnectionFailed(err) {
      this.$toast.error('Socket connection error: ' + err.message)
    },
    socketInit(data) {},
    async initLibraries() {
      await this.$store.dispatch('libraries/load')
      this.$eventBus.$emit('library-changed')
      this.$store.dispatch('libraries/fetch', this.currentLibraryId)
    }
  },
  async mounted() {
    // this.$server.on('logout', this.userLoggedOut)
    // this.$server.on('connected', this.connected)
    // this.$server.on('connectionFailed', this.socketConnectionFailed)
    // this.$server.on('initialStream', this.initialStream)
    // this.$server.on('show_error_toast', this.showErrorToast)
    // this.$server.on('show_success_toast', this.showSuccessToast)

    this.$socket.on('connection-update', this.socketConnectionUpdate)
    this.$socket.on('initialized', this.socketInit)

    if (this.$store.state.isFirstLoad) {
      this.$store.commit('setIsFirstLoad', false)
      await this.$store.dispatch('setupNetworkListener')

      if (this.$store.state.user.serverConnectionConfig) {
        await this.initLibraries()
      } else {
        await this.attemptConnection()
      }

      this.checkForUpdate()
      this.loadSavedSettings()
      this.initMediaStore()
    }
  },
  beforeDestroy() {
    if (!this.$server) {
      console.error('No Server beforeDestroy')
      return
    }

    this.$socket.off('connection-update', this.socketConnectionUpdate)
    this.$socket.off('initialized', this.socketInit)
  }
}
</script>
