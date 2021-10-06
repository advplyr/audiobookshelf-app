<template>
  <div class="w-full min-h-screen h-full bg-bg text-white">
    <app-appbar />
    <div id="content" class="overflow-hidden" :class="playerIsOpen ? 'playerOpen' : ''">
      <Nuxt />
    </div>
    <app-stream-container ref="streamContainer" />
    <modals-downloads-modal ref="downloadsModal" @selectDownload="selectDownload" @deleteDownload="deleteDownload" />
    <modals-libraries-modal />
  </div>
</template>

<script>
import { Capacitor } from '@capacitor/core'
import { Network } from '@capacitor/network'
import { AppUpdate } from '@robingenz/capacitor-app-update'
import AudioDownloader from '@/plugins/audio-downloader'
import MyNativeAudio from '@/plugins/my-native-audio'

export default {
  data() {
    return {}
  },
  computed: {
    playerIsOpen() {
      return this.$store.getters['playerIsOpen']
    },
    routeName() {
      return this.$route.name
    }
  },
  methods: {
    async connected(isConnected) {
      if (this.$route.name === 'connect') {
        if (isConnected) {
          this.$router.push('/')
        }
      }
      this.syncUserProgress()

      // Load libraries
      this.$store.dispatch('libraries/load')
    },
    updateAudiobookProgressOnServer(audiobookProgress) {
      if (this.$server.socket) {
        this.$server.socket.emit('progress_update', audiobookProgress)
      }
    },
    syncUserProgress() {
      if (!this.$store.state.user.user) return

      var userAudiobooks = this.$store.state.user.user.audiobooks
      var localAudiobooks = this.$store.state.user.localUserAudiobooks
      var localHasUpdates = false

      var newestLocal = { ...localAudiobooks }
      for (const audiobookId in userAudiobooks) {
        if (localAudiobooks[audiobookId]) {
          if (localAudiobooks[audiobookId].lastUpdate > userAudiobooks[audiobookId].lastUpdate) {
            // Local progress is more recent than user progress
            this.updateAudiobookProgressOnServer(localAudiobooks[audiobookId])
          } else if (localAudiobooks[audiobookId].lastUpdate < userAudiobooks[audiobookId].lastUpdate) {
            // Server is more recent than local
            newestLocal[audiobookId] = userAudiobooks[audiobookId]
            console.log('SYNCUSERPROGRESS Server IS MORE RECENT for', audiobookId)
            localHasUpdates = true
          }
        } else {
          // Not on local yet - store on local
          newestLocal[audiobookId] = userAudiobooks[audiobookId]
          console.log('SYNCUSERPROGRESS LOCAL Is NOT Stored YET for', audiobookId, JSON.stringify(newestLocal[audiobookId]))
          localHasUpdates = true
        }
      }

      for (const audiobookId in localAudiobooks) {
        if (!userAudiobooks[audiobookId]) {
          // Local progress is not on server
          this.updateAudiobookProgressOnServer(localAudiobooks[audiobookId])
        }
      }

      if (localHasUpdates) {
        console.log('Local audiobook progress has updates from server')
        this.$localStore.setAllAudiobookProgress(newestLocal)
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
    // showUpdateToast(availableVersion, immediateUpdateAllowed) {
    //   var toastText = immediateUpdateAllowed ? `Click here to update` : `Click here to open app store`
    //   this.$toast.info(`Update is available for v${availableVersion}! ${toastText}`, {
    //     draggable: false,
    //     hideProgressBar: false,
    //     timeout: 10000,
    //     closeButton: false,
    //     onClick: this.clickUpdateToast()
    //   })
    // },
    async checkForUpdate() {
      const result = await AppUpdate.getAppUpdateInfo()
      if (!result) {
        console.error('Invalid version check')
        return
      }
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
        if (this.$refs.downloadsModal) {
          this.$refs.downloadsModal.updateDownloadProgress({ audiobookId, progress })
        }
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

      console.log('Download complete', filename, downloadId, contentUrl, 'AudiobookId:', audiobookId, 'Is Cover:', isCover)
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
    async onMediaLoaded(items) {
      var jsitems = JSON.parse(items)
      jsitems = jsitems.map((item) => {
        return {
          filename: item.name,
          size: item.size,
          contentUrl: item.uri,
          coverUrl: item.coverUrl || null
        }
      })
      console.log('onMediaLoaded Items', JSON.stringify(jsitems))

      var downloads = await this.$sqlStore.getAllDownloads()
      for (let i = 0; i < downloads.length; i++) {
        var download = downloads[i]
        var jsitem = jsitems.find((item) => item.contentUrl === download.contentUrl)
        if (!jsitem) {
          console.error('Removing download was not found', JSON.stringify(download))
          await this.$sqlStore.removeDownload(download.id)
        } else if (download.coverUrl && !jsitem.coverUrl) {
          console.error('Removing cover for download was not found')
          download.cover = null
          download.coverUrl = null
          download.size = jsitem.size || 0
          this.$store.commit('downloads/addUpdateDownload', download)
          this.$store.commit('audiobooks/addUpdate', download.audiobook)
        } else {
          download.size = jsitem.size || 0
          this.$store.commit('downloads/addUpdateDownload', download)
          this.$store.commit('audiobooks/addUpdate', download.audiobook)
        }
      }

      this.checkLoadCurrent()
      this.$store.dispatch('audiobooks/setNativeAudiobooks')
    },
    selectDownload(download) {
      this.$store.commit('setPlayOnLoad', true)
      this.$store.commit('setPlayingDownload', download)
    },
    async deleteDownload(download) {
      console.log('Delete download', download.filename)

      if (this.$store.state.playingDownload && this.$store.state.playingDownload.id === download.id) {
        console.warn('Deleting download when currently playing download - terminate play')
        if (this.$refs.streamContainer) {
          this.$refs.streamContainer.cancelStream()
        }
      }
      if (download.contentUrl) {
        await AudioDownloader.delete(download)
      }
      this.$store.commit('downloads/removeDownload', download)
    },
    async onPermissionUpdate(data) {
      var val = data.value
      console.log('Permission Update', val)
      if (val === 'required') {
        console.log('Permission Required - Making Request')
        this.$store.commit('setHasStoragePermission', false)
      } else if (val === 'canceled' || val === 'denied') {
        console.error('Permission denied by user')
        this.$store.commit('setHasStoragePermission', false)
      } else if (val === 'granted') {
        console.log('User Granted permission')
        this.$store.commit('setHasStoragePermission', true)

        var folder = data
        delete folder.value
        console.log('Setting folder', JSON.stringify(folder))
        await this.$localStore.setDownloadFolder(folder)
      } else {
        console.warn('Other permisiso update', val)
      }
    },
    async initMediaStore() {
      // Request and setup listeners for media files on native
      console.log('Permissino SET LISTENER')
      AudioDownloader.addListener('permission', (data) => {
        this.onPermissionUpdate(data)
      })
      AudioDownloader.addListener('onDownloadComplete', (data) => {
        this.onDownloadComplete(data)
      })
      AudioDownloader.addListener('onDownloadFailed', (data) => {
        this.onDownloadFailed(data)
      })
      AudioDownloader.addListener('onMediaLoaded', (data) => {
        this.onMediaLoaded(data.items)
      })
      AudioDownloader.addListener('onDownloadProgress', (data) => {
        this.onDownloadProgress(data)
      })

      await this.$localStore.loadUserAudiobooks()
      await this.$localStore.getDownloadFolder()

      var userSavedSettings = await this.$localStore.getUserSettings()
      if (userSavedSettings) {
        this.$store.commit('user/setSettings', userSavedSettings)
      }

      var downloads = await this.$sqlStore.getAllDownloads()
      if (downloads.length) {
        var urls = downloads
          .map((d) => {
            return {
              contentUrl: d.contentUrl,
              coverUrl: d.coverUrl || '',
              storageId: d.storageId,
              basePath: d.basePath,
              coverBasePath: d.coverBasePath || ''
            }
          })
          .filter((d) => {
            if (!d.contentUrl) {
              console.error('Invalid Download no Content URL', JSON.stringify(d))
              return false
            }
            return true
          })

        AudioDownloader.load({
          audiobookUrls: urls
        })
      }

      var checkPermission = await AudioDownloader.checkStoragePermission()
      console.log('Storage Permission is', checkPermission.value)
      if (!checkPermission.value) {
        console.log('Will require permissions')
      } else {
        console.log('Has Storage Permission')
        this.$store.commit('setHasStoragePermission', true)
      }
    },
    async setupNetworkListener() {
      var status = await Network.getStatus()
      this.$store.commit('setNetworkStatus', status)

      Network.addListener('networkStatusChange', (status) => {
        console.log('Network status changed', status.connected, status.connectionType)
        this.$store.commit('setNetworkStatus', status)
      })
    }
  },
  mounted() {
    if (!this.$server) return console.error('No Server')

    this.$server.on('connected', this.connected)
    this.$server.on('initialStream', this.initialStream)

    if (this.$store.state.isFirstLoad) {
      this.$store.commit('setIsFirstLoad', false)
      this.setupNetworkListener()
      this.checkForUpdate()
      this.initMediaStore()
    }

    // For Testing Android Auto
    MyNativeAudio.addListener('onPrepareMedia', (data) => {
      var audiobookId = data.audiobookId
      var playWhenReady = data.playWhenReady

      var audiobook = this.$store.getters['audiobooks/getAudiobook'](audiobookId)

      var download = this.$store.getters['downloads/getDownloadIfReady'](audiobookId)
      this.$store.commit('setPlayOnLoad', playWhenReady)
      if (!download) {
        // Stream
        this.$store.commit('setStreamAudiobook', audiobook)
        this.$server.socket.emit('open_stream', audiobook.id)
      } else {
        // Local
        this.$store.commit('setPlayingDownload', download)
      }
    })
  },
  beforeDestroy() {
    if (!this.$server) {
      console.error('No Server beforeDestroy')
      return
    }
    console.log('Default Before Destroy remove listeners')
    this.$server.off('connected', this.connected)
    this.$server.off('initialStream', this.initialStream)
  }
}
</script>

<style>
#content {
  height: calc(100vh - 64px);
}
#content.playerOpen {
  height: calc(100vh - 240px);
}
</style>