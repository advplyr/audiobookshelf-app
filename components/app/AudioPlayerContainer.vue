<template>
  <div>
    <div v-if="audiobook" id="streamContainer">
      <app-audio-player
        ref="audioPlayer"
        :audiobook="audiobook"
        :download="download"
        :loading="isLoading"
        :bookmarks="bookmarks"
        :sleep-timer-running="isSleepTimerRunning"
        :sleep-timer-end-of-chapter-time="sleepTimerEndOfChapterTime"
        :sleep-timeout-current-time="sleepTimeoutCurrentTime"
        @close="cancelStream"
        @sync="sync"
        @selectPlaybackSpeed="showPlaybackSpeedModal = true"
        @selectChapter="clickChapterBtn"
        @showSleepTimer="showSleepTimer"
        @showBookmarks="showBookmarks"
        @hook:mounted="audioPlayerMounted"
      />
    </div>

    <modals-playback-speed-modal v-model="showPlaybackSpeedModal" :playback-speed.sync="playbackSpeed" @change="changePlaybackSpeed" />
    <modals-chapters-modal v-model="showChapterModal" :current-chapter="currentChapter" :chapters="chapters" @select="selectChapter" />
    <modals-sleep-timer-modal v-model="showSleepTimerModal" :current-time="sleepTimeoutCurrentTime" :sleep-timer-running="isSleepTimerRunning" :current-end-of-chapter-time="currentEndOfChapterTime" :end-of-chapter-time-set="sleepTimerEndOfChapterTime" @change="selectSleepTimeout" @cancel="cancelSleepTimer" />
    <modals-bookmarks-modal v-model="showBookmarksModal" :audiobook-id="audiobookId" :bookmarks="bookmarks" :current-time="currentTime" @select="selectBookmark" />
  </div>
</template>

<script>
import { Dialog } from '@capacitor/dialog'
import MyNativeAudio from '@/plugins/my-native-audio'

export default {
  data() {
    return {
      audioPlayerReady: false,
      stream: null,
      download: null,
      lastProgressTimeUpdate: 0,
      showPlaybackSpeedModal: false,
      showBookmarksModal: false,
      showSleepTimerModal: false,
      playbackSpeed: 1,
      showChapterModal: false,
      currentTime: 0,
      sleepTimeoutCurrentTime: 0,
      isSleepTimerRunning: false,
      sleepTimerEndOfChapterTime: 0,
      onSleepTimerEndedListener: null,
      sleepInterval: null,
      currentEndOfChapterTime: 0
    }
  },
  watch: {
    socketConnected(newVal) {
      if (newVal) {
        console.log('Socket Connected set listeners')
        this.setListeners()
      }
    }
  },
  computed: {
    userToken() {
      return this.$store.getters['user/getToken']
    },
    userAudiobook() {
      if (!this.audiobookId) return
      return this.$store.getters['user/getMostRecentUserAudiobookData'](this.audiobookId)
    },
    bookmarks() {
      if (!this.userAudiobook) return []
      return this.userAudiobook.bookmarks || []
    },
    currentChapter() {
      if (!this.audiobook || !this.chapters.length) return null
      return this.chapters.find((ch) => ch.start <= this.currentTime && ch.end > this.currentTime)
    },
    socketConnected() {
      return this.$store.state.socketConnected
    },
    isLoading() {
      if (this.playingDownload) return false
      if (!this.streamAudiobook) return false
      return !this.stream || this.streamAudiobook.id !== this.stream.audiobook.id
    },
    playingDownload() {
      return this.$store.state.playingDownload
    },
    audiobook() {
      if (this.playingDownload) return this.playingDownload.audiobook
      return this.streamAudiobook
    },
    audiobookId() {
      return this.audiobook ? this.audiobook.id : null
    },
    streamAudiobook() {
      return this.$store.state.streamAudiobook
    },
    book() {
      return this.audiobook ? this.audiobook.book || {} : {}
    },
    title() {
      return this.book ? this.book.title : ''
    },
    author() {
      return this.book ? this.book.author : ''
    },
    cover() {
      return this.book ? this.book.cover : ''
    },
    downloadedCover() {
      return this.download ? this.download.cover : null
    },
    series() {
      return this.book ? this.book.series : ''
    },
    chapters() {
      return this.audiobook ? this.audiobook.chapters || [] : []
    },
    volumeNumber() {
      return this.book ? this.book.volumeNumber : ''
    },
    seriesTxt() {
      if (!this.series) return ''
      if (!this.volumeNumber) return this.series
      return `${this.series} #${this.volumeNumber}`
    },
    duration() {
      return this.audiobook ? this.audiobook.duration || 0 : 0
    },
    coverForNative() {
      if (!this.cover) {
        return `${this.$store.state.serverUrl}/Logo.png`
      }
      if (this.cover.startsWith('http')) return this.cover
      var coverSrc = this.$store.getters['audiobooks/getBookCoverSrc'](this.audiobook)
      return coverSrc
    }
  },
  methods: {
    showBookmarks() {
      this.showBookmarksModal = true
    },
    selectBookmark(bookmark) {
      this.showBookmarksModal = false
      if (!bookmark || isNaN(bookmark.time)) return
      var bookmarkTime = Number(bookmark.time)
      if (this.$refs.audioPlayer) {
        this.$refs.audioPlayer.seek(bookmarkTime)
      }
    },
    onSleepTimerEnded({ value: currentPosition }) {
      this.isSleepTimerRunning = false
      if (this.sleepInterval) clearInterval(this.sleepInterval)

      if (currentPosition) {
        console.log('Sleep Timer Ended Current Position: ' + currentPosition)
        var currentTime = Math.floor(currentPosition / 1000)
        this.updateTime(currentTime)
      }
    },
    showSleepTimer() {
      console.log('show sleep timer')
      if (this.currentChapter) {
        this.currentEndOfChapterTime = Math.floor(this.currentChapter.end)
      } else {
        this.currentEndOfChapterTime = 0
      }
      this.showSleepTimerModal = true
    },
    async getSleepTimerTime() {
      var res = await MyNativeAudio.getSleepTimerTime()
      if (res && res.value) {
        var time = Number(res.value)
        return time - Date.now()
      }
      return 0
    },
    async selectSleepTimeout({ time, isChapterTime }) {
      console.log('Setting sleep timer', time, isChapterTime)
      var res = await MyNativeAudio.setSleepTimer({ time: String(time), isChapterTime })
      if (!res.success) {
        return this.$toast.error('Sleep timer did not set, invalid time')
      }
      if (isChapterTime) {
        this.sleepTimerEndOfChapterTime = time
        this.isSleepTimerRunning = true
      } else {
        this.sleepTimerEndOfChapterTime = 0
        this.setSleepTimeoutTimer(time)
      }
    },
    async cancelSleepTimer() {
      console.log('Canceling sleep timer')
      await MyNativeAudio.cancelSleepTimer()
      this.isSleepTimerRunning = false
      this.sleepTimerEndOfChapterTime = 0
      if (this.sleepInterval) clearInterval(this.sleepInterval)
    },
    async syncSleepTimer() {
      var time = await this.getSleepTimerTime()
      this.setSleepTimeoutTimer(time)
    },
    setSleepTimeoutTimer(startTime) {
      if (this.sleepInterval) clearInterval(this.sleepInterval)

      this.sleepTimeoutCurrentTime = startTime
      this.isSleepTimerRunning = true
      var elapsed = 0
      this.sleepInterval = setInterval(() => {
        this.sleepTimeoutCurrentTime = Math.max(0, this.sleepTimeoutCurrentTime - 1000)

        if (this.sleepTimeoutCurrentTime <= 0) {
          clearInterval(this.sleepInterval)
          return
        }

        // Sync with the actual time from android Timer
        elapsed++
        if (elapsed > 5) {
          clearInterval(this.sleepInterval)
          this.syncSleepTimer()
        }
      }, 1000)
    },
    clickChapterBtn() {
      if (!this.chapters.length) return
      this.showChapterModal = true
    },
    selectChapter(chapter) {
      if (this.$refs.audioPlayer) {
        this.$refs.audioPlayer.seek(chapter.start)
      }
      this.showChapterModal = false
    },
    async cancelStream() {
      this.currentTime = 0

      if (this.download) {
        if (this.$refs.audioPlayer) {
          this.$refs.audioPlayer.terminateStream()
        }
        this.download = null
        this.$store.commit('setPlayingDownload', null)

        this.$localStore.setCurrent(null)
      } else {
        const { value } = await Dialog.confirm({
          title: 'Confirm',
          message: 'Cancel this stream?'
        })
        if (value) {
          this.$server.socket.emit('close_stream')
          this.$store.commit('setStreamAudiobook', null)
          this.$server.stream = null
          if (this.$refs.audioPlayer) {
            this.$refs.audioPlayer.terminateStream()
          }
        }
      }
    },
    sync(syncData) {
      var diff = syncData.currentTime - this.lastServerUpdateSentSeconds
      if (Math.abs(diff) < 1 && !syncData.timeListened) {
        // No need to sync
        return
      }

      if (this.stream) {
        this.$server.socket.emit('stream_sync', syncData)
      } else {
        var progressUpdate = {
          audiobookId: syncData.audiobookId,
          currentTime: syncData.currentTime,
          totalDuration: syncData.totalDuration,
          progress: Number((syncData.currentTime / syncData.totalDuration).toFixed(3)),
          lastUpdate: Date.now(),
          isRead: false
        }

        if (this.$server.connected) {
          this.$server.socket.emit('progress_update', progressUpdate)
        } else {
          this.$store.dispatch('user/updateUserAudiobookData', progressUpdate)
          // this.$localStore.updateUserAudiobookData(progressUpdate).then(() => {
          //   console.log('Updated user audiobook progress', currentTime)
          // })
        }
      }
    },
    updateTime(currentTime) {
      this.currentTime = currentTime

      var diff = currentTime - this.lastProgressTimeUpdate

      if (diff > 4 || diff < 0) {
        this.lastProgressTimeUpdate = currentTime
        if (this.stream) {
          var updatePayload = {
            currentTime,
            streamId: this.stream.id
          }
          this.$server.socket.emit('stream_update', updatePayload)
        } else if (this.download) {
          var progressUpdate = {
            audiobookId: this.download.id,
            currentTime: currentTime,
            totalDuration: this.download.audiobook.duration,
            progress: Number((currentTime / this.download.audiobook.duration).toFixed(3)),
            lastUpdate: Date.now(),
            isRead: false
          }

          if (this.$server.connected) {
            this.$server.socket.emit('progress_update', progressUpdate)
          }
          this.$localStore.updateUserAudiobookData(progressUpdate).then(() => {
            console.log('Updated user audiobook progress', currentTime)
          })
        }
      }
    },
    closeStream() {},
    streamClosed(audiobookId) {
      console.log('Stream Closed')
      if (this.stream.audiobook.id === audiobookId || audiobookId === 'n/a') {
        this.$store.commit('setStreamAudiobook', null)
      }
    },
    streamProgress(data) {
      if (!data.numSegments) return
      var chunks = data.chunks
      if (this.$refs.audioPlayer) {
        this.$refs.audioPlayer.setChunksReady(chunks, data.numSegments)
      }
    },
    streamReady() {
      console.log('[StreamContainer] Stream Ready')
      if (this.$refs.audioPlayer) {
        this.$refs.audioPlayer.setStreamReady()
      }
    },
    streamReset({ streamId, startTime }) {
      if (this.$refs.audioPlayer) {
        if (this.stream && this.stream.id === streamId) {
          this.$refs.audioPlayer.resetStream(startTime)
        }
      }
    },
    async getDownloadStartTime() {
      var userAudiobook = await this.$localStore.getMostRecentUserAudiobook(this.audiobookId)
      if (!userAudiobook) {
        console.log('[StreamContainer] getDownloadStartTime no user audiobook record found')
        return 0
      }
      return userAudiobook.currentTime
    },
    async playDownload() {
      if (this.stream) {
        if (this.$refs.audioPlayer) {
          this.$refs.audioPlayer.terminateStream()
        }
        this.stream = null
      }

      this.lastProgressTimeUpdate = 0
      console.log('[StreamContainer] Playing local', this.playingDownload)
      if (!this.$refs.audioPlayer) {
        console.error('No Audio Player Mini')
        return
      }

      var playOnLoad = this.$store.state.playOnLoad
      if (playOnLoad) this.$store.commit('setPlayOnLoad', false)

      var currentTime = await this.getDownloadStartTime()
      if (isNaN(currentTime) || currentTime === null) currentTime = 0
      this.currentTime = currentTime

      // Update local current time
      this.$localStore.setCurrent({
        audiobookId: this.download.id,
        lastUpdate: Date.now()
      })

      var audiobookStreamData = {
        title: this.title,
        author: this.author,
        playWhenReady: !!playOnLoad,
        startTime: String(Math.floor(currentTime * 1000)),
        playbackSpeed: this.playbackSpeed || 1,
        cover: this.download.coverUrl || null,
        duration: String(Math.floor(this.duration * 1000)),
        series: this.seriesTxt,
        token: this.userToken,
        contentUrl: this.playingDownload.contentUrl,
        isLocal: true,
        audiobookId: this.download.id
      }

      this.$refs.audioPlayer.set(audiobookStreamData, null, false)
    },
    streamOpen(stream) {
      if (this.download) {
        if (this.$refs.audioPlayer) {
          this.$refs.audioPlayer.terminateStream()
        }
      }

      this.lastProgressTimeUpdate = 0
      console.log('[StreamContainer] Stream Open: ' + this.title)

      if (!this.$refs.audioPlayer) {
        console.error('No Audio Player Mini')
        return
      }

      // Update local remove current
      this.$localStore.setCurrent(null)

      var playlistUrl = stream.clientPlaylistUri
      var currentTime = stream.clientCurrentTime || 0
      this.currentTime = currentTime
      var playOnLoad = this.$store.state.playOnLoad
      if (playOnLoad) this.$store.commit('setPlayOnLoad', false)

      var audiobookStreamData = {
        id: stream.id,
        title: this.title,
        author: this.author,
        playWhenReady: !!playOnLoad,
        startTime: String(Math.floor(currentTime * 1000)),
        playbackSpeed: this.playbackSpeed || 1,
        cover: this.coverForNative,
        duration: String(Math.floor(this.duration * 1000)),
        series: this.seriesTxt,
        playlistUrl: this.$server.url + playlistUrl,
        token: this.$store.getters['user/getToken'],
        audiobookId: this.audiobookId
      }
      this.$refs.audioPlayer.set(audiobookStreamData, stream, !this.stream)

      this.stream = stream
    },
    audioPlayerMounted() {
      console.log('Audio Player Mounted', this.$server.stream)
      this.audioPlayerReady = true

      if (this.playingDownload) {
        console.log('[StreamContainer] Play download on audio mount')
        if (!this.download) {
          this.download = { ...this.playingDownload }
        }
        this.playDownload()
      } else if (this.$server.stream) {
        console.log('[StreamContainer] Open stream on audio mount')
        this.streamOpen(this.$server.stream)
      }
    },
    changePlaybackSpeed(speed) {
      console.log(`[AudioPlayerContainer] Change Playback Speed: ${speed}`)
      if (this.$refs.audioPlayer) {
        this.$refs.audioPlayer.setPlaybackSpeed(speed)
      }
      this.$store.dispatch('user/updateUserSettings', { playbackRate: speed })
    },
    settingsUpdated(settings) {
      console.log(`[AudioPlayerContainer] Settings Update | PlaybackRate: ${settings.playbackRate}`)
      this.playbackSpeed = settings.playbackRate
      if (this.$refs.audioPlayer && this.$refs.audioPlayer.currentPlaybackRate !== settings.playbackRate) {
        console.log(`[AudioPlayerContainer] PlaybackRate Updated: ${this.playbackSpeed}`)
        this.$refs.audioPlayer.setPlaybackSpeed(this.playbackSpeed)
      }
    },
    streamUpdated(type, data) {
      if (type === 'download') {
        if (data) {
          this.download = { ...data }
          if (this.audioPlayerReady) {
            this.playDownload()
          }
        } else if (this.download) {
          this.cancelStream()
        }
      }
    },
    setListeners() {
      if (!this.$server.socket) {
        console.error('Invalid server socket not set')
        return
      }
      this.$server.socket.on('stream_open', this.streamOpen)
      this.$server.socket.on('stream_closed', this.streamClosed)
      this.$server.socket.on('stream_progress', this.streamProgress)
      this.$server.socket.on('stream_ready', this.streamReady)
      this.$server.socket.on('stream_reset', this.streamReset)
    }
  },
  mounted() {
    this.onSleepTimerEndedListener = MyNativeAudio.addListener('onSleepTimerEnded', this.onSleepTimerEnded)

    this.playbackSpeed = this.$store.getters['user/getUserSetting']('playbackRate')
    console.log(`[AudioPlayerContainer] Init Playback Speed: ${this.playbackSpeed}`)

    this.setListeners()
    this.$store.commit('user/addSettingsListener', { id: 'streamContainer', meth: this.settingsUpdated })
    // this.$store.commit('user/addUserAudiobookListener', { id: 'streamContainer', meth: this.userAudiobooksUpdated })
    this.$store.commit('setStreamListener', this.streamUpdated)
  },
  beforeDestroy() {
    if (this.onSleepTimerEndedListener) this.onSleepTimerEndedListener.remove()

    if (this.$server.socket) {
      this.$server.socket.off('stream_open', this.streamOpen)
      this.$server.socket.off('stream_closed', this.streamClosed)
      this.$server.socket.off('stream_progress', this.streamProgress)
      this.$server.socket.off('stream_ready', this.streamReady)
      this.$server.socket.off('stream_reset', this.streamReset)
    }

    this.$store.commit('user/removeSettingsListener', 'streamContainer')
    // this.$store.commit('user/removeUserAudiobookListener', 'streamContainer')
    this.$store.commit('removeStreamListener')
  }
}
</script>