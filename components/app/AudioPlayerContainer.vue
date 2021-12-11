<template>
  <div>
    <div v-if="audiobook" id="streamContainer">
      <app-audio-player
        ref="audioPlayer"
        :playing.sync="isPlaying"
        :audiobook="audiobook"
        :download="download"
        :loading="isLoading"
        :bookmarks="bookmarks"
        :sleep-timer-running="isSleepTimerRunning"
        :sleep-timer-end-time="sleepTimerEndTime"
        @close="cancelStream"
        @sync="sync"
        @setTotalDuration="setTotalDuration"
        @selectPlaybackSpeed="showPlaybackSpeedModal = true"
        @selectChapter="clickChapterBtn"
        @updateTime="(t) => (currentTime = t)"
        @showSleepTimer="showSleepTimer"
        @showBookmarks="showBookmarks"
        @hook:mounted="audioPlayerMounted"
      />
    </div>

    <modals-playback-speed-modal v-model="showPlaybackSpeedModal" :playback-rate.sync="playbackSpeed" @update:playbackRate="updatePlaybackSpeed" @change="changePlaybackSpeed" />
    <modals-chapters-modal v-model="showChapterModal" :current-chapter="currentChapter" :chapters="chapters" @select="selectChapter" />
    <modals-sleep-timer-modal v-model="showSleepTimerModal" :current-time="sleepTimeRemaining" :sleep-timer-running="isSleepTimerRunning" :current-end-of-chapter-time="currentEndOfChapterTime" @change="selectSleepTimeout" @cancel="cancelSleepTimer" @increase="increaseSleepTimer" @decrease="decreaseSleepTimer" />
    <modals-bookmarks-modal v-model="showBookmarksModal" :audiobook-id="audiobookId" :bookmarks="bookmarks" :current-time="currentTime" @select="selectBookmark" />
  </div>
</template>

<script>
import { Dialog } from '@capacitor/dialog'
import MyNativeAudio from '@/plugins/my-native-audio'

export default {
  data() {
    return {
      isPlaying: false,
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
      isSleepTimerRunning: false,
      sleepTimerEndTime: 0,
      onSleepTimerEndedListener: null,
      onSleepTimerSetListener: null,
      sleepInterval: null,
      currentEndOfChapterTime: 0,
      totalDuration: 0
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
      return this.$store.getters['user/getUserAudiobookData'](this.audiobookId)
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
    },
    sleepTimeRemaining() {
      if (!this.sleepTimerEndTime) return 0
      return Math.max(0, this.sleepTimerEndTime / 1000 - this.currentTime)
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
      if (currentPosition) {
        console.log('Sleep Timer Ended Current Position: ' + currentPosition)
        var currentTime = Math.floor(currentPosition / 1000)
        this.updateTime(currentTime)
      }
    },
    onSleepTimerSet({ value: sleepTimerEndTime }) {
      console.log('SLEEP TIMER SET', sleepTimerEndTime)
      if (sleepTimerEndTime === 0) {
        console.log('Sleep timer canceled')
        this.isSleepTimerRunning = false
      } else {
        this.isSleepTimerRunning = true
      }

      this.sleepTimerEndTime = sleepTimerEndTime
    },
    showSleepTimer() {
      if (this.currentChapter) {
        this.currentEndOfChapterTime = Math.floor(this.currentChapter.end)
      } else {
        this.currentEndOfChapterTime = 0
      }
      this.showSleepTimerModal = true
    },
    async selectSleepTimeout({ time, isChapterTime }) {
      console.log('Setting sleep timer', time, isChapterTime)
      var res = await MyNativeAudio.setSleepTimer({ time: String(time), isChapterTime })
      if (!res.success) {
        return this.$toast.error('Sleep timer did not set, invalid time')
      }
    },
    increaseSleepTimer() {
      // Default time to increase = 5 min
      MyNativeAudio.increaseSleepTime({ time: '300000' })
    },
    decreaseSleepTimer() {
      MyNativeAudio.decreaseSleepTime({ time: '300000' })
    },
    async cancelSleepTimer() {
      console.log('Canceling sleep timer')
      await MyNativeAudio.cancelSleepTimer()
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
          progress: syncData.totalDuration ? Number((syncData.currentTime / syncData.totalDuration).toFixed(3)) : 0,
          lastUpdate: Date.now(),
          isRead: false
        }

        if (this.$server.connected) {
          this.$server.socket.emit('progress_update', progressUpdate)
        } else {
          this.$store.dispatch('user/updateUserAudiobookData', progressUpdate)
        }
      }
    },
    updateTime(currentTime) {
      this.sync({
        currentTime,
        audiobookId: this.audiobookId,
        streamId: this.stream ? this.stream.id : null,
        timeListened: 0,
        totalDuration: this.totalDuration || 0
      })
    },
    setTotalDuration(duration) {
      this.totalDuration = duration
    },
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
      var userAudiobook = this.$store.getters['user/getUserAudiobookData'](this.audiobookId)
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
        this.download = null
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
        token: this.userToken,
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
    updatePlaybackSpeed(speed) {
      if (this.$refs.audioPlayer) {
        console.log(`[AudioPlayerContainer] Update Playback Speed: ${speed}`)
        this.$refs.audioPlayer.setPlaybackSpeed(speed)
      }
    },
    changePlaybackSpeed(speed) {
      console.log(`[AudioPlayerContainer] Change Playback Speed: ${speed}`)
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
    },
    closeStreamOnly() {
      // If user logs out or disconnects from server, close audio if streaming
      if (!this.download) {
        this.$store.commit('setStreamAudiobook', null)
        if (this.$refs.audioPlayer) {
          this.$refs.audioPlayer.terminateStream()
        }
      }
    }
  },
  mounted() {
    this.onSleepTimerEndedListener = MyNativeAudio.addListener('onSleepTimerEnded', this.onSleepTimerEnded)
    this.onSleepTimerSetListener = MyNativeAudio.addListener('onSleepTimerSet', this.onSleepTimerSet)

    this.playbackSpeed = this.$store.getters['user/getUserSetting']('playbackRate')
    console.log(`[AudioPlayerContainer] Init Playback Speed: ${this.playbackSpeed}`)

    this.setListeners()
    this.$eventBus.$on('close_stream', this.closeStreamOnly)
    this.$store.commit('user/addSettingsListener', { id: 'streamContainer', meth: this.settingsUpdated })
    this.$store.commit('setStreamListener', this.streamUpdated)
  },
  beforeDestroy() {
    if (this.onSleepTimerEndedListener) this.onSleepTimerEndedListener.remove()
    if (this.onSleepTimerSetListener) this.onSleepTimerSetListener.remove()

    if (this.$server.socket) {
      this.$server.socket.off('stream_open', this.streamOpen)
      this.$server.socket.off('stream_closed', this.streamClosed)
      this.$server.socket.off('stream_progress', this.streamProgress)
      this.$server.socket.off('stream_ready', this.streamReady)
      this.$server.socket.off('stream_reset', this.streamReset)
    }

    this.$eventBus.$off('close_stream', this.closeStreamOnly)
    this.$store.commit('user/removeSettingsListener', 'streamContainer')
    this.$store.commit('removeStreamListener')
  }
}
</script>