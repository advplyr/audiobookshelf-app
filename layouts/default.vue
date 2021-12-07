<template>
  <div class="w-full min-h-screen h-full bg-bg text-white">
    <app-appbar />
    <div id="content" class="overflow-hidden" :class="playerIsOpen ? 'playerOpen' : ''">
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
    currentLibraryId() {
      return this.$store.state.libraries.currentLibraryId
    },
    isSocketConnected() {
      return this.$store.state.socketConnected
    }
  },
  methods: {
    async connected(isConnected) {
      if (isConnected) {
        console.log('[Default] Connected socket sync user ab data')
        this.$store.dispatch('user/syncUserAudiobookData')

        this.initSocketListeners()

        // Load libraries
        this.$store.dispatch('libraries/load')
        this.$store.dispatch('libraries/fetch', this.currentLibraryId)
      } else {
        this.removeSocketListeners()
      }
    },
    socketConnectionFailed(err) {
      this.$toast.error('Socket connection error: ' + err.message)
    },
    currentUserAudiobookUpdate({ id, data }) {
      if (data) {
        console.log(`Current User Audiobook Updated ${id} ${JSON.stringify(data)}`)
        this.$sqlStore.setUserAudiobookData(data)
      } else {
        this.$sqlStore.removeUserAudiobookData(id)
      }
    },
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
    onDownloadProgress(data) {
      var progress = data.progress
      var audiobookId = data.audiobookId

      var downloadObj = this.$store.getters['downloads/getDownload'](audiobookId)
      if (downloadObj) {
        this.$toast.update(downloadObj.toastId, { content: `${progress}% Downloading ${downloadObj.audiobook.book.title}` })
      }
    },
    onDownloadFailed(data) {
      if (!data.audiobookId) {
        console.error('Download failed invalid audiobook id', data)
        return
      }
      var downloadObj = this.$store.getters['downloads/getDownload'](data.audiobookId)
      if (!downloadObj) {
        console.error('Failed to find download for audiobook', data.audiobookId)
        return
      }
      var message = data.error || 'Unknown Error'
      this.$toast.update(downloadObj.toastId, { content: `Failed. ${message}.`, options: { timeout: 5000, type: 'error' } }, true)
      this.$store.commit('downloads/removeDownload', downloadObj)
    },
    onDownloadComplete(data) {
      if (!data.audiobookId) {
        console.error('Download compelte invalid audiobook id', data)
        return
      }
      var downloadId = data.downloadId
      var contentUrl = data.contentUrl
      var folderUrl = data.folderUrl
      var folderName = data.folderName
      var storageId = data.storageId
      var storageType = data.storageType
      var simplePath = data.simplePath
      var filename = data.filename
      var audiobookId = data.audiobookId
      var size = data.size || 0
      var isCover = !!data.isCover

      console.log(`Download complete "${contentUrl}" | ${filename} | DlId: ${downloadId} | Is Cover? ${isCover}`)
      var downloadObj = this.$store.getters['downloads/getDownload'](audiobookId)
      if (!downloadObj) {
        console.error('Failed to find download for audiobook', audiobookId)
        return
      }

      if (!isCover) {
        // Notify server to remove prepared download
        if (this.$server.socket) {
          this.$server.socket.emit('remove_download', audiobookId)
        }

        this.$toast.update(downloadObj.toastId, { content: `Success! ${downloadObj.audiobook.book.title} downloaded.`, options: { timeout: 5000, type: 'success' } }, true)

        delete downloadObj.isDownloading
        delete downloadObj.isPreparing
        downloadObj.contentUrl = contentUrl
        downloadObj.simplePath = simplePath
        downloadObj.folderUrl = folderUrl
        downloadObj.folderName = folderName
        downloadObj.storageType = storageType
        downloadObj.storageId = storageId
        downloadObj.basePath = data.basePath || null
        downloadObj.size = size
        this.$store.commit('downloads/addUpdateDownload', downloadObj)
      } else {
        downloadObj.coverUrl = contentUrl
        downloadObj.cover = Capacitor.convertFileSrc(contentUrl)
        downloadObj.coverSize = size
        downloadObj.coverBasePath = data.basePath || null
        console.log('Updating download with cover', downloadObj.cover)
        this.$store.commit('downloads/addUpdateDownload', downloadObj)
      }
    },
    async checkLoadCurrent() {
      var currentObj = await this.$localStore.getCurrent()
      if (!currentObj) return

      console.log('Has Current playing', currentObj.audiobookId)
      var download = this.$store.getters['downloads/getDownload'](currentObj.audiobookId)
      if (download) {
        this.$store.commit('setPlayingDownload', download)
      } else {
        console.warn('Download not available for previous current playing', currentObj.audiobookId)
        this.$localStore.setCurrent(null)
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
    async syncDownloads(downloads, downloadFolder) {
      console.log('Syncing downloads ' + downloads.length)
      var mediaScanResults = await this.searchFolder(downloadFolder)

      this.$store.commit('downloads/setMediaScanResults', mediaScanResults)

      // Filter out media folders without any audio files
      var mediaFolders = mediaScanResults.folders.filter((sr) => {
        if (!sr.files) return false
        var audioFiles = sr.files.filter((mf) => !!mf.isAudio)
        return audioFiles.length
      })

      downloads.forEach((download) => {
        var mediaFolder = mediaFolders.find((mf) => mf.name === download.folderName)
        if (mediaFolder) {
          console.log('Found download ' + download.folderName)
          if (download.isMissing) {
            download.isMissing = false
            this.$store.commit('downloads/addUpdateDownload', download)
          }
        } else {
          console.error('Download not found ' + download.folderName)
          if (!download.isMissing) {
            download.isMissing = true
            this.$store.commit('downloads/addUpdateDownload', download)
          }
        }
      })

      // Match media scanned folders with books from server
      if (this.isSocketConnected) {
        await this.$store.dispatch('downloads/linkOrphanDownloads')
      }
    },
    async initMediaStore() {
      // Request and setup listeners for media files on native
      AudioDownloader.addListener('onDownloadComplete', (data) => {
        this.onDownloadComplete(data)
      })
      AudioDownloader.addListener('onDownloadFailed', (data) => {
        this.onDownloadFailed(data)
      })
      AudioDownloader.addListener('onDownloadProgress', (data) => {
        this.onDownloadProgress(data)
      })

      var downloads = await this.$store.dispatch('downloads/loadFromStorage')
      var downloadFolder = await this.$localStore.getDownloadFolder()

      if (downloadFolder) {
        await this.syncDownloads(downloads, downloadFolder)
      }
      this.$eventBus.$emit('downloads-loaded')

      var checkPermission = await StorageManager.checkStoragePermission()
      console.log('Storage Permission is' + checkPermission.value)
      if (!checkPermission.value) {
        console.log('Will require permissions')
      } else {
        console.log('Has Storage Permission')
        this.$store.commit('setHasStoragePermission', true)
      }
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

      console.log('Loading offline user audiobook data')
      await this.$store.dispatch('user/loadOfflineUserAudiobookData')
    },
    showErrorToast(message) {
      this.$toast.error(message)
    },
    showSuccessToast(message) {
      this.$toast.success(message)
    },
    async attemptConnection() {
      if (!this.$server) return
      if (!this.networkConnected) {
        console.warn('No network connection')
        return
      }

      var localServerUrl = await this.$localStore.getServerUrl()
      var localUserToken = await this.$localStore.getToken()
      if (localServerUrl) {
        // Server and Token are stored
        if (localUserToken) {
          var isSocketAlreadyEstablished = this.$server.socket
          var success = await this.$server.connect(localServerUrl, localUserToken)
          if (!success && !this.$server.url) {
            // Bad URL
          } else if (!success) {
            // Failed to connect
          } else if (isSocketAlreadyEstablished) {
            // No need to wait for connect event
          }
        }
      }
    },
    audiobookAdded(audiobook) {
      this.$store.commit('libraries/updateFilterDataWithAudiobook', audiobook)
    },
    audiobookUpdated(audiobook) {
      this.$store.commit('libraries/updateFilterDataWithAudiobook', audiobook)
    },
    audiobookRemoved(audiobook) {
      if (this.$route.name.startsWith('audiobook')) {
        if (this.$route.params.id === audiobook.id) {
          this.$router.replace(`/bookshelf`)
        }
      }
    },
    audiobooksAdded(audiobooks) {
      audiobooks.forEach((ab) => {
        this.audiobookAdded(ab)
      })
    },
    audiobooksUpdated(audiobooks) {
      audiobooks.forEach((ab) => {
        this.audiobookUpdated(ab)
      })
    },
    userLoggedOut() {
      // Only cancels stream if streamining not playing downloaded
      this.$eventBus.$emit('close_stream')
    },
    initSocketListeners() {
      if (this.$server.socket) {
        // Audiobook Listeners
        this.$server.socket.on('audiobook_updated', this.audiobookUpdated)
        this.$server.socket.on('audiobook_added', this.audiobookAdded)
        this.$server.socket.on('audiobook_removed', this.audiobookRemoved)
        this.$server.socket.on('audiobooks_updated', this.audiobooksUpdated)
        this.$server.socket.on('audiobooks_added', this.audiobooksAdded)
      }
    },
    removeSocketListeners() {
      if (this.$server.socket) {
        // Audiobook Listeners
        this.$server.socket.off('audiobook_updated', this.audiobookUpdated)
        this.$server.socket.off('audiobook_added', this.audiobookAdded)
        this.$server.socket.off('audiobook_removed', this.audiobookRemoved)
        this.$server.socket.off('audiobooks_updated', this.audiobooksUpdated)
        this.$server.socket.off('audiobooks_added', this.audiobooksAdded)
      }
    }
  },
  async mounted() {
    if (!this.$server) return console.error('No Server')
    // console.log(`Default Mounted set SOCKET listeners ${this.$server.connected}`)

    if (this.$server.connected) {
      console.log('Syncing on default mount')
      this.connected(true)
    }
    this.$server.on('logout', this.userLoggedOut)
    this.$server.on('connected', this.connected)
    this.$server.on('connectionFailed', this.socketConnectionFailed)
    this.$server.on('initialStream', this.initialStream)
    this.$server.on('currentUserAudiobookUpdate', this.currentUserAudiobookUpdate)
    this.$server.on('show_error_toast', this.showErrorToast)
    this.$server.on('show_success_toast', this.showSuccessToast)

    if (this.$store.state.isFirstLoad) {
      this.$store.commit('setIsFirstLoad', false)
      await this.$store.dispatch('setupNetworkListener')
      this.attemptConnection()
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
    this.removeSocketListeners()
    this.$server.off('logout', this.userLoggedOut)
    this.$server.off('connected', this.connected)
    this.$server.off('connectionFailed', this.socketConnectionFailed)
    this.$server.off('initialStream', this.initialStream)
    this.$server.off('show_error_toast', this.showErrorToast)
    this.$server.off('show_success_toast', this.showSuccessToast)
  }
}
</script>

<style>
#content {
  height: calc(100vh - 64px);
}
#content.playerOpen {
  height: calc(100vh - 164px);
}
</style>