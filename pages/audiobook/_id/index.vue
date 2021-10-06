<template>
  <div class="w-full h-full px-3 py-4 overflow-y-auto">
    <div class="flex">
      <div class="w-32">
        <div class="relative">
          <cards-book-cover :audiobook="audiobook" :download-cover="downloadedCover" :width="128" />
          <div class="absolute bottom-0 left-0 h-1.5 bg-yellow-400 shadow-sm" :style="{ width: 128 * progressPercent + 'px' }"></div>
        </div>
        <div class="flex my-4">
          <p class="text-sm">{{ numTracks }} Tracks</p>
        </div>
      </div>
      <div class="flex-grow px-3">
        <h1 class="text-lg">{{ title }}</h1>
        <h3 v-if="series" class="font-book text-gray-300 text-lg leading-7">{{ seriesText }}</h3>
        <p class="text-sm text-gray-400">by {{ author }}</p>
        <p class="text-gray-300 text-sm my-1">
          {{ $elapsedPretty(duration) }}<span class="px-4">{{ $bytesPretty(size) }}</span>
        </p>

        <div v-if="progressPercent > 0" class="px-4 py-2 bg-primary text-sm font-semibold rounded-md text-gray-200 mt-4 relative" :class="resettingProgress ? 'opacity-25' : ''">
          <p class="leading-6">Your Progress: {{ Math.round(progressPercent * 100) }}%</p>
          <p class="text-gray-400 text-xs">{{ $elapsedPretty(userTimeRemaining) }} remaining</p>
          <div v-if="!resettingProgress" class="absolute -top-1.5 -right-1.5 p-1 w-5 h-5 rounded-full bg-bg hover:bg-error border border-primary flex items-center justify-center cursor-pointer" @click.stop="clearProgressClick">
            <span class="material-icons text-sm">close</span>
          </div>
        </div>

        <div v-if="isConnected || isDownloadPlayable" class="flex mt-4">
          <ui-btn color="success" :disabled="isPlaying" class="flex items-center justify-center w-full mr-2" :padding-x="4" @click="playClick">
            <span v-show="!isPlaying" class="material-icons">play_arrow</span>
            <span class="px-1">{{ isPlaying ? (isStreaming ? 'Streaming' : 'Playing') : isDownloadPlayable ? 'Play local' : 'Play stream' }}</span>
          </ui-btn>
          <ui-btn v-if="isConnected" color="primary" :disabled="isPlaying" class="flex items-center justify-center" :padding-x="2" @click="downloadClick">
            <span class="material-icons" :class="isDownloaded ? 'animate-pulse' : ''">{{ downloadObj ? (isDownloading || isDownloadPreparing ? 'downloading' : 'download_done') : 'download' }}</span>
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

export default {
  async asyncData({ store, params, redirect, app }) {
    var audiobookId = params.id
    var audiobook = null

    if (app.$server.connected) {
      audiobook = await app.$axios.$get(`/api/audiobook/${audiobookId}`).catch((error) => {
        console.error('Failed', error)
        return false
      })
    } else {
      audiobook = store.getters['audiobooks/getAudiobook'](audiobookId)
    }

    if (!audiobook) {
      console.error('No audiobook...', params.id)
      return redirect('/')
    }
    return {
      audiobook
    }
  },
  data() {
    return {
      resettingProgress: false
    }
  },
  computed: {
    isConnected() {
      return this.$store.state.socketConnected
    },
    audiobookId() {
      return this.audiobook.id
    },
    book() {
      return this.audiobook.book || {}
    },
    title() {
      return this.book.title
    },
    author() {
      return this.book.author || 'Unknown'
    },
    description() {
      return this.book.description || ''
    },
    series() {
      return this.book.series || null
    },
    volumeNumber() {
      return this.book.volumeNumber || null
    },
    seriesText() {
      if (!this.series) return ''
      if (!this.volumeNumber) return this.series
      return `${this.series} #${this.volumeNumber}`
    },
    duration() {
      return this.audiobook.duration
    },
    size() {
      return this.audiobook.size
    },
    userAudiobooks() {
      return this.$store.state.user.user ? this.$store.state.user.user.audiobooks || {} : {}
    },
    userAudiobook() {
      return this.userAudiobooks[this.audiobookId] || null
    },
    userToken() {
      return this.$store.getters['user/getToken']
    },
    localUserAudiobooks() {
      return this.$store.state.user.localUserAudiobooks || {}
    },
    localUserAudiobook() {
      return this.localUserAudiobooks[this.audiobookId] || null
    },
    mostRecentUserAudiobook() {
      if (!this.localUserAudiobook) return this.userAudiobook
      if (!this.userAudiobook) return this.localUserAudiobook
      return this.localUserAudiobook.lastUpdate > this.userAudiobook.lastUpdate ? this.localUserAudiobook : this.userAudiobook
    },
    userCurrentTime() {
      return this.mostRecentUserAudiobook ? this.mostRecentUserAudiobook.currentTime : 0
    },
    userTimeRemaining() {
      return this.duration - this.userCurrentTime
    },
    progressPercent() {
      return this.mostRecentUserAudiobook ? this.mostRecentUserAudiobook.progress : 0
    },
    isStreaming() {
      return this.$store.getters['isAudiobookStreaming'](this.audiobookId)
    },
    isPlaying() {
      return this.$store.getters['isAudiobookPlaying'](this.audiobookId)
    },
    numTracks() {
      if (this.audiobook.tracks) return this.audiobook.tracks.length
      return this.audiobook.numTracks || 0
    },
    isDownloading() {
      return this.downloadObj ? this.downloadObj.isDownloading : false
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
      return this.$store.getters['downloads/getDownload'](this.audiobookId)
    },
    hasStoragePermission() {
      return this.$store.state.hasStoragePermission
    }
  },
  methods: {
    playClick() {
      this.$store.commit('setPlayOnLoad', true)
      if (!this.isDownloadPlayable) {
        // Stream
        console.log('[PLAYCLICK] Set Playing STREAM ' + this.title)
        this.$store.commit('setStreamAudiobook', this.audiobook)
        this.$server.socket.emit('open_stream', this.audiobook.id)
      } else {
        // Local
        console.log('[PLAYCLICK] Set Playing Local Download ' + this.title)
        this.$store.commit('setPlayingDownload', this.downloadObj)
      }
    },
    async clearProgressClick() {
      const { value } = await Dialog.confirm({
        title: 'Confirm',
        message: 'Are you sure you want to reset your progress?'
      })

      if (value) {
        this.resettingProgress = true
        if (this.$server.connected) {
          await this.$axios
            .$delete(`/api/user/audiobook/${this.audiobookId}`)
            .then(() => {
              console.log('Progress reset complete')
              this.$toast.success(`Your progress was reset`)
            })
            .catch((error) => {
              console.error('Progress reset failed', error)
            })
        }
        this.$localStore.updateUserAudiobookProgress({
          audiobookId: this.audiobookId,
          currentTime: 0,
          totalDuration: this.duration,
          progress: 0,
          lastUpdate: Date.now(),
          isRead: false
        })
        this.resettingProgress = false
      }
    },
    audiobookUpdated() {
      console.log('Audiobook Updated - Fetch full audiobook')
      this.$axios
        .$get(`/api/audiobook/${this.audiobookId}`)
        .then((audiobook) => {
          this.audiobook = audiobook
        })
        .catch((error) => {
          console.error('Failed', error)
        })
    },
    downloadClick() {
      if (!this.$server.connected) return

      if (this.downloadObj) {
        console.log('Already downloaded', this.downloadObj)
      } else {
        this.prepareDownload()
      }
    },
    async prepareDownload() {
      var audiobook = this.audiobook
      if (!audiobook) {
        return
      }

      if (!this.hasStoragePermission) {
        await AudioDownloader.requestStoragePermission()
        return
      }

      // Download Path
      var dlFolder = this.$localStore.downloadFolder
      if (!dlFolder) {
        console.log('No download folder, request from ujser')
        var folderObj = await AudioDownloader.selectFolder()

        if (folderObj.error) {
          return this.$toast.error(`Error: ${folderObj.error || 'Unknown Error'}`)
        }
        dlFolder = folderObj
        await this.$localStore.setDownloadFolder(folderObj)
      }

      var downloadObject = {
        id: this.audiobookId,
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

        var relTrackPath = track.path.replace('\\', '/').replace(this.audiobook.path.replace('\\', '/'), '')
        var url = `${this.$store.state.serverUrl}/s/book/${this.audiobookId}/${relTrackPath}`
        this.startDownload(url, fileext, downloadObject)
      } else {
        // Multi-track merge
        this.$store.commit('downloads/addUpdateDownload', downloadObject)

        var prepareDownloadPayload = {
          audiobookId: this.audiobookId,
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
      var coverSrc = this.$store.getters['audiobooks/getBookCoverSrc'](this.audiobook)
      return coverSrc
      // var _clean = cover.replace(/\\/g, '/')
      // if (_clean.startsWith('/local')) {
      //   var _cover = process.env.NODE_ENV !== 'production' && process.env.PROD !== '1' ? _clean.replace('/local', '') : _clean
      //   return `${this.$store.state.serverUrl}${_cover}?token=${this.userToken}&ts=${Date.now()}`
      // } else if (_clean.startsWith('/metadata')) {
      //   return `${this.$store.state.serverUrl}${_clean}?token=${this.userToken}&ts=${Date.now()}`
      // }
      // return _clean
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
      console.warn('Audiobook Page mounted: Server socket not set')
    } else {
      this.$server.socket.on('download_ready', this.downloadReady)
      this.$server.socket.on('download_killed', this.downloadKilled)
      this.$server.socket.on('download_failed', this.downloadFailed)
    }

    this.$store.commit('audiobooks/addListener', { id: 'audiobook', audiobookId: this.audiobookId, meth: this.audiobookUpdated })
  },
  beforeDestroy() {
    if (!this.$server.socket) {
      console.warn('Audiobook Page beforeDestroy: Server socket not set')
    } else {
      this.$server.socket.off('download_ready', this.downloadReady)
      this.$server.socket.off('download_killed', this.downloadKilled)
      this.$server.socket.off('download_failed', this.downloadFailed)
    }

    this.$store.commit('audiobooks/removeListener', 'audiobook')
  }
}
</script>