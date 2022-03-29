<template>
  <div class="w-full h-full px-3 py-4 overflow-y-auto">
    <div class="flex">
      <div class="w-32">
        <div class="relative">
          <covers-book-cover :library-item="libraryItem" :download-cover="downloadedCover" :width="128" :book-cover-aspect-ratio="bookCoverAspectRatio" />
          <div class="absolute bottom-0 left-0 h-1.5 bg-yellow-400 shadow-sm z-10" :style="{ width: 128 * progressPercent + 'px' }"></div>
        </div>
        <div class="flex my-4">
          <p v-if="numTracks" class="text-sm">{{ numTracks }} Tracks</p>
        </div>
      </div>
      <div class="flex-grow px-3">
        <h1 class="text-lg">{{ title }}</h1>
        <!-- <h3 v-if="series" class="font-book text-gray-300 text-lg leading-7">{{ seriesText }}</h3> -->
        <p class="text-sm text-gray-400">by {{ author }}</p>
        <p v-if="numTracks" class="text-gray-300 text-sm my-1">
          {{ $elapsedPretty(duration) }}
          <span class="px-4">{{ $bytesPretty(size) }}</span>
        </p>

        <div v-if="progressPercent > 0" class="px-4 py-2 bg-primary text-sm font-semibold rounded-md text-gray-200 mt-4 relative" :class="resettingProgress ? 'opacity-25' : ''">
          <p class="leading-6">Your Progress: {{ Math.round(progressPercent * 100) }}%</p>
          <p v-if="progressPercent < 1" class="text-gray-400 text-xs">{{ $elapsedPretty(userTimeRemaining) }} remaining</p>
          <div v-if="!resettingProgress" class="absolute -top-1.5 -right-1.5 p-1 w-5 h-5 rounded-full bg-bg hover:bg-error border border-primary flex items-center justify-center cursor-pointer" @click.stop="clearProgressClick">
            <span class="material-icons text-sm">close</span>
          </div>
        </div>

        <div v-if="(isConnected && (showPlay || showRead)) || isDownloadPlayable" class="flex mt-4 -mr-2">
          <ui-btn v-if="showPlay" color="success" :disabled="isPlaying" class="flex items-center justify-center flex-grow mr-2" :padding-x="4" @click="playClick">
            <span v-show="!isPlaying" class="material-icons">play_arrow</span>
            <span class="px-1 text-sm">{{ isPlaying ? (isStreaming ? 'Streaming' : 'Playing') : isDownloadPlayable ? 'Play local' : 'Play stream' }}</span>
          </ui-btn>
          <ui-btn v-if="showRead && isConnected" color="info" class="flex items-center justify-center mr-2" :class="showPlay ? '' : 'flex-grow'" :padding-x="2" @click="readBook">
            <span class="material-icons">auto_stories</span>
            <span v-if="!showPlay" class="px-2 text-base">Read {{ ebookFormat }}</span>
          </ui-btn>
          <ui-btn v-if="isConnected && showPlay && !isIos" color="primary" class="flex items-center justify-center" :padding-x="2" @click="downloadClick">
            <span class="material-icons" :class="downloadObj ? 'animate-pulse' : ''">{{ downloadObj ? (isDownloading || isDownloadPreparing ? 'downloading' : 'download_done') : 'download' }}</span>
          </ui-btn>
        </div>
      </div>
    </div>
    <div class="w-full py-4">
      <p>{{ description }}</p>
    </div>
  </div>
</template>

<script>
import Path from 'path'
import { Dialog } from '@capacitor/dialog'
import AudioDownloader from '@/plugins/audio-downloader'
import StorageManager from '@/plugins/storage-manager'

export default {
  async asyncData({ store, params, redirect, app }) {
    var libraryItemId = params.id
    var libraryItem = null

    if (app.$server.connected) {
      libraryItem = await app.$axios.$get(`/api/items/${libraryItemId}?expanded=1`).catch((error) => {
        console.error('Failed', error)
        return false
      })
    } else {
      var download = store.getters['downloads/getDownload'](libraryItemId)
      if (download) {
        libraryItem = download.libraryItem
      }
    }

    if (!libraryItem) {
      console.error('No item...', params.id)
      return redirect('/')
    }
    return {
      libraryItem
    }
  },
  data() {
    return {
      resettingProgress: false
    }
  },
  computed: {
    isIos() {
      return this.$platform === 'ios'
    },
    isConnected() {
      return this.$store.state.socketConnected
    },
    bookCoverAspectRatio() {
      return this.$store.getters['getBookCoverAspectRatio']
    },
    libraryItemId() {
      return this.libraryItem.id
    },
    media() {
      return this.libraryItem.media || {}
    },
    mediaMetadata() {
      return this.media.metadata || {}
    },
    title() {
      return this.mediaMetadata.title
    },
    author() {
      return this.mediaMetadata.authorName
    },
    description() {
      return this.mediaMetadata.description || ''
    },
    series() {
      return this.mediaMetadata.series || []
    },
    duration() {
      return this.media.duration
    },
    size() {
      return this.media.size
    },
    userToken() {
      return this.$store.getters['user/getToken']
    },
    userItemProgress() {
      return this.$store.getters['user/getUserLibraryItemProgress'](this.libraryItemId)
    },
    userIsFinished() {
      return this.userItemProgress ? !!this.userItemProgress.isFinished : false
    },
    userTimeRemaining() {
      if (!this.userItemProgress) return 0
      var duration = this.userItemProgress.duration || this.duration
      return duration - this.userItemProgress.currentTime
    },
    progressPercent() {
      return this.userItemProgress ? Math.max(Math.min(1, this.userItemProgress.progress), 0) : 0
    },
    userProgressStartedAt() {
      return this.userItemProgress ? this.userItemProgress.startedAt : 0
    },
    userProgressFinishedAt() {
      return this.userItemProgress ? this.userItemProgress.finishedAt : 0
    },
    isStreaming() {
      return this.$store.getters['isAudiobookStreaming'](this.libraryItemId)
    },
    isPlaying() {
      return this.$store.getters['isAudiobookPlaying'](this.libraryItemId)
    },
    numTracks() {
      if (!this.media.tracks) return 0
      return this.media.tracks.length || 0
    },
    isMissing() {
      return this.libraryItem.isMissing
    },
    isIncomplete() {
      return this.libraryItem.isIncomplete
    },
    isDownloading() {
      return this.downloadObj ? this.downloadObj.isDownloading : false
    },
    showPlay() {
      return !this.isMissing && !this.isIncomplete && this.numTracks
    },
    showRead() {
      return this.ebookFile && this.ebookFormat !== '.pdf'
    },
    ebookFile() {
      return this.media.ebookFile
    },
    ebookFormat() {
      if (!this.ebookFile) return null
      return this.ebookFile.ebookFormat
    },
    isDownloadPreparing() {
      return this.downloadObj ? this.downloadObj.isPreparing : false
    },
    isDownloadPlayable() {
      return this.downloadObj && !this.isDownloading && !this.isDownloadPreparing
    },
    downloadedCover() {
      return this.downloadObj ? this.downloadObj.cover : null
    },
    downloadObj() {
      return this.$store.getters['downloads/getDownload'](this.libraryItemId)
    },
    hasStoragePermission() {
      return this.$store.state.hasStoragePermission
    }
  },
  methods: {
    readBook() {
      this.$store.commit('openReader', this.libraryItem)
    },
    playClick() {
      this.$eventBus.$emit('play-item', this.libraryItem.id)

      // this.$store.commit('setPlayOnLoad', true)
      // if (!this.isDownloadPlayable) {
      // Stream
      // console.log('[PLAYCLICK] Set Playing STREAM ' + this.title)
      // this.$store.commit('setStreamAudiobook', this.libraryItem)
      // this.$server.socket.emit('open_stream', this.libraryItem.id)
      // } else {
      // Local
      // console.log('[PLAYCLICK] Set Playing Local Download ' + this.title)
      // this.$store.commit('setPlayingDownload', this.downloadObj)
      // }
    },
    async clearProgressClick() {
      if (!this.$server.connected) {
        this.$toast.info('Clear downloaded book progress not yet implemented')
        return
      }

      const { value } = await Dialog.confirm({
        title: 'Confirm',
        message: 'Are you sure you want to reset your progress?'
      })
      if (value) {
        this.resettingProgress = true
        this.$axios
          .$delete(`/api/me/progress/${this.libraryItemId}`)
          .then(() => {
            console.log('Progress reset complete')
            this.$toast.success(`Your progress was reset`)
            this.resettingProgress = false
          })
          .catch((error) => {
            console.error('Progress reset failed', error)
            this.resettingProgress = false
          })
      }
      // if (value) {
      //   this.resettingProgress = true
      //   this.$store.dispatch('user/updateUserAudiobookData', {
      //     libraryItemId: this.libraryItemId,
      //     currentTime: 0,
      //     totalDuration: this.duration,
      //     progress: 0,
      //     lastUpdate: Date.now(),
      //     isRead: false
      //   })
      // }
    },
    itemUpdated(libraryItem) {
      if (libraryItem.id === this.libraryItemId) {
        console.log('Item Updated')
        this.libraryItem = libraryItem
      }
    },
    downloadClick() {
      console.log('downloadClick ' + this.$server.connected + ' | ' + !!this.downloadObj)
      if (!this.$server.connected) return

      if (this.downloadObj) {
        console.log('Already downloaded', this.downloadObj)
      } else {
        this.prepareDownload()
      }
    },
    async changeDownloadFolderClick() {
      if (!this.hasStoragePermission) {
        console.log('Requesting Storage Permission')
        await StorageManager.requestStoragePermission()
      } else {
        var folderObj = await StorageManager.selectFolder()
        if (folderObj.error) {
          return this.$toast.error(`Error: ${folderObj.error || 'Unknown Error'}`)
        }

        var permissionsGood = await StorageManager.checkFolderPermissions({ folderUrl: folderObj.uri })
        console.log('Storage Permission check folder ' + permissionsGood)

        if (!permissionsGood) {
          this.$toast.error('Folder permissions failed')
          return
        } else {
          this.$toast.success('Folder permission success')
        }

        await this.$localStore.setDownloadFolder(folderObj)
      }
    },
    async prepareDownload() {
      var audiobook = this.libraryItem
      if (!audiobook) {
        return
      }

      // Download Path
      var dlFolder = this.$localStore.downloadFolder
      console.log('Prepare download: ' + this.hasStoragePermission + ' | ' + dlFolder)

      if (!this.hasStoragePermission || !dlFolder) {
        console.log('No download folder, request from user')
        // User to select download folder from download modal to ensure permissions
        // this.$store.commit('downloads/setShowModal', true)
        this.changeDownloadFolderClick()
        return
      } else {
        console.log('Has Download folder: ' + JSON.stringify(dlFolder))
      }

      var downloadObject = {
        id: this.libraryItemId,
        downloadFolderUrl: dlFolder.uri,
        audiobook: {
          ...audiobook
        },
        isPreparing: true,
        isDownloading: false,
        toastId: this.$toast(`Preparing download for "${this.title}"`, { timeout: false })
      }
      if (audiobook.tracks.length === 1) {
        // Single track should not need preparation
        console.log('Single track, start download no prep needed')
        var track = audiobook.tracks[0]
        var fileext = track.ext

        console.log('Download Single Track Path: ' + track.path)

        var relTrackPath = track.path.replace('\\', '/').replace(this.libraryItem.path.replace('\\', '/'), '')

        var url = `${this.$store.state.serverUrl}/s/book/${this.libraryItemId}${relTrackPath}?token=${this.userToken}`
        this.startDownload(url, fileext, downloadObject)
      } else {
        // Multi-track merge
        this.$store.commit('downloads/addUpdateDownload', downloadObject)

        var prepareDownloadPayload = {
          audiobookId: this.libraryItemId,
          audioFileType: 'same',
          type: 'singleAudio'
        }
        this.$server.socket.emit('download', prepareDownloadPayload)
      }
    },
    getCoverUrlForDownload() {
      if (!this.book || !this.book.cover) return null

      var cover = this.book.cover
      if (cover.startsWith('http')) return cover
      var coverSrc = this.$store.getters['global/getLibraryItemCoverSrc'](this.libraryItem)
      return coverSrc
    },
    async startDownload(url, fileext, download) {
      this.$toast.update(download.toastId, { content: `Downloading "${download.audiobook.book.title}"...` })

      var coverDownloadUrl = this.getCoverUrlForDownload()
      var coverFilename = null
      if (coverDownloadUrl) {
        var coverNoQueryString = coverDownloadUrl.split('?')[0]

        var coverExt = Path.extname(coverNoQueryString) || '.jpg'
        coverFilename = `cover-${download.id}${coverExt}`
      }

      download.isDownloading = true
      download.isPreparing = false
      download.filename = `${download.audiobook.book.title}${fileext}`
      this.$store.commit('downloads/addUpdateDownload', download)

      console.log('Starting Download URL', url)
      var downloadRequestPayload = {
        audiobookId: download.id,
        filename: download.filename,
        coverFilename,
        coverDownloadUrl,
        downloadUrl: url,
        title: download.audiobook.book.title,
        downloadFolderUrl: download.downloadFolderUrl
      }
      var downloadRes = await AudioDownloader.download(downloadRequestPayload)
      if (downloadRes.error) {
        var errorMsg = downloadRes.error || 'Unknown error'
        console.error('Download error', errorMsg)
        this.$toast.update(download.toastId, { content: `Error: ${errorMsg}`, options: { timeout: 5000, type: 'error' } })
        this.$store.commit('downloads/removeDownload', download)
      }
    },
    downloadReady(prepareDownload) {
      var download = this.$store.getters['downloads/getDownload'](prepareDownload.audiobookId)
      if (download) {
        var fileext = prepareDownload.ext
        var url = `${this.$store.state.serverUrl}/downloads/${prepareDownload.id}/${prepareDownload.filename}?token=${this.userToken}`
        this.startDownload(url, fileext, download)
      } else {
        console.error('Prepare download killed but download not found', prepareDownload)
      }
    },
    downloadKilled(prepareDownload) {
      var download = this.$store.getters['downloads/getDownload'](prepareDownload.audiobookId)
      if (download) {
        this.$toast.update(download.toastId, { content: `Prepare download killed for "${download.audiobook.book.title}"`, options: { timeout: 5000, type: 'error' } })
        this.$store.commit('downloads/removeDownload', download)
      } else {
        console.error('Prepare download killed but download not found', prepareDownload)
      }
    },
    downloadFailed(prepareDownload) {
      var download = this.$store.getters['downloads/getDownload'](prepareDownload.audiobookId)
      if (download) {
        this.$toast.update(download.toastId, { content: `Prepare download failed for "${download.audiobook.book.title}"`, options: { timeout: 5000, type: 'error' } })
        this.$store.commit('downloads/removeDownload', download)
      } else {
        console.error('Prepare download failed but download not found', prepareDownload)
      }
    }
  },
  mounted() {
    if (!this.$server.socket) {
      console.warn('Library Item Page mounted: Server socket not set')
    } else {
      this.$server.socket.on('download_ready', this.downloadReady)
      this.$server.socket.on('download_killed', this.downloadKilled)
      this.$server.socket.on('download_failed', this.downloadFailed)
      this.$server.socket.on('item_updated', this.itemUpdated)
    }
  },
  beforeDestroy() {
    if (!this.$server.socket) {
      console.warn('Library Item Page beforeDestroy: Server socket not set')
    } else {
      this.$server.socket.off('download_ready', this.downloadReady)
      this.$server.socket.off('download_killed', this.downloadKilled)
      this.$server.socket.off('download_failed', this.downloadFailed)
      this.$server.socket.off('item_updated', this.itemUpdated)
    }
  }
}
</script>