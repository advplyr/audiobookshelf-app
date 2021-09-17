<template>
  <div class="w-full min-h-screen h-full bg-bg text-white">
    <app-appbar />
    <div id="content" class="overflow-hidden" :class="playerIsOpen ? 'playerOpen' : ''">
      <Nuxt />
    </div>
    <app-stream-container ref="streamContainer" />
    <modals-downloads-modal ref="downloadsModal" @selectDownload="selectDownload" @deleteDownload="deleteDownload" />
  </div>
</template>

<script>
import Path from 'path'
import { Capacitor } from '@capacitor/core'
import { Network } from '@capacitor/network'
import { AppUpdate } from '@robingenz/capacitor-app-update'
import AudioDownloader from '@/plugins/audio-downloader'

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
    connected(isConnected) {
      if (this.$route.name === 'connect') {
        if (isConnected) {
          this.$router.push('/')
        }
      }
      this.syncUserProgress()
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
          } else {
            // Server is more recent than local
            newestLocal[audiobookId] = userAudiobooks[audiobookId]
            localHasUpdates = true
          }
        } else {
          // Not on local yet - store on local
          newestLocal[audiobookId] = userAudiobooks[audiobookId]
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
          this.$toast.info(`Update is available!`, {
            draggable: false,
            hideProgressBar: false,
            timeout: 4000,
            closeButton: false,
            onClick: this.clickUpdateToast()
          })
          // this.showUpdateToast(result.availableVersion, !!result.immediateUpdateAllowed)
        }, 5000)
      }
    },
    onDownloadProgress(data) {
      // var downloadId = data.downloadId
      var progress = data.progress
      var filename = data.filename
      var audiobookId = filename ? Path.basename(filename, Path.extname(filename)) : ''

      var downloadObj = this.$store.getters['downloads/getDownload'](audiobookId)
      if (downloadObj) {
        if (this.$refs.downloadsModal) {
          this.$refs.downloadsModal.updateDownloadProgress({ audiobookId, progress })
        }
        this.$toast.update(downloadObj.toastId, { content: `${progress}% Downloading ${downloadObj.audiobook.book.title}` })
      }
    },
    onDownloadComplete(data) {
      var downloadId = data.downloadId
      var contentUrl = data.contentUrl
      var filename = data.filename
      var audiobookId = filename ? Path.basename(filename, Path.extname(filename)) : ''

      if (audiobookId) {
        // Notify server to remove prepared download
        if (this.$server.socket) {
          this.$server.socket.emit('remove_download', downloadId)
        }

        console.log('Download complete', filename, downloadId, contentUrl, 'AudiobookId:', audiobookId)
        var downloadObj = this.$store.getters['downloads/getDownload'](audiobookId)
        if (!downloadObj) {
          console.error('Could not find download...')
        } else {
          this.$toast.update(downloadObj.toastId, { content: `Success! ${downloadObj.audiobook.book.title} downloaded.`, options: { timeout: 5000, type: 'success' } }, true)

          console.log('Found download, update with content url')
          delete downloadObj.isDownloading
          delete downloadObj.isPreparing
          downloadObj.contentUrl = contentUrl
          this.$store.commit('downloads/addUpdateDownload', downloadObj)
        }
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
    onMediaLoaded(items) {
      var jsitems = JSON.parse(items)
      jsitems = jsitems.map((item) => {
        return {
          id: item.id,
          size: item.size,
          duration: item.duration,
          filename: item.name,
          audiobookId: item.name ? Path.basename(item.name, Path.extname(item.name)) : '',
          contentUrl: item.uri.replace(/\\\//g, '/'),
          coverUrl: item.coverUrl || null
        }
      })
      jsitems.forEach((item) => {
        var download = this.$store.getters['downloads/getDownload'](item.audiobookId)
        if (!download) {
          console.error(`Unknown media item found for filename ${item.filename}`)

          var orphanDownload = {
            id: `orphan-${item.id}`,
            contentUrl: item.contentUrl,
            coverUrl: item.coverUrl,
            cover: item.coverUrl ? Capacitor.convertFileSrc(item.coverUrl) : null,
            mediaId: item.id,
            filename: item.filename,
            size: item.size,
            duration: item.duration,
            isOrphan: true
          }
          this.$store.commit('downloads/addUpdateDownload', orphanDownload)
        } else {
          console.log(`Found media item for audiobook ${download.audiobook.book.title} (${item.audiobookId})`)
          download.contentUrl = item.contentUrl
          download.coverUrl = item.coverUrl
          download.cover = item.coverUrl ? Capacitor.convertFileSrc(item.coverUrl) : null
          download.size = item.size
          download.duration = item.duration
          this.$store.commit('downloads/addUpdateDownload', download)

          download.audiobook.isDownloaded = true
          this.$store.commit('audiobooks/addUpdate', download.audiobook)
        }
      })

      var downloads = this.$store.state.downloads.downloads

      downloads.forEach((download) => {
        var matchingItem = jsitems.find((item) => item.audiobookId === download.id)
        if (!matchingItem) {
          console.error(`Saved download not in media store ${download.audiobook.book.title} (${download.id})`)
          this.$store.commit('downloads/removeDownload', download)
        }
      })

      this.checkLoadCurrent()
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
        await AudioDownloader.delete({ audiobookId: download.id, filename: download.filename, url: download.contentUrl, coverUrl: download.coverUrl })
      }
      this.$store.commit('downloads/removeDownload', download)
    },
    async initMediaStore() {
      // Load local database of downloads
      await this.$store.dispatch('downloads/loadFromStorage')
      await this.$localStore.loadUserAudiobooks()

      // Request and setup listeners for media files on native
      AudioDownloader.addListener('onDownloadComplete', (data) => {
        this.onDownloadComplete(data)
      })
      AudioDownloader.addListener('onMediaLoaded', (data) => {
        this.onMediaLoaded(data.items)
      })
      AudioDownloader.addListener('onDownloadProgress', (data) => {
        this.onDownloadProgress(data)
      })
      AudioDownloader.load()
    },
    async setupNetworkListener() {
      var status = await Network.getStatus()
      console.log('Network status', status.connected, status.connectionType)
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

    this.setupNetworkListener()
    this.checkForUpdate()
    this.initMediaStore()
  }
}
</script>

<style>
#content {
  height: calc(100vh - 64px);
}
#content.playerOpen {
  /* height: calc(100vh - 204px); */
  height: calc(100vh - 240px);
}
</style>