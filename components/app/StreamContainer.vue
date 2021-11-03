<template>
  <div class="w-full p-4 pointer-events-none fixed bottom-0 left-0 right-0 z-20">
    <div v-if="audiobook" id="streamContainer" class="w-full bg-primary absolute bottom-0 left-0 right-0 z-50 p-2 pointer-events-auto" @click.stop @mousedown.stop @mouseup.stop>
      <div class="pl-16 pr-2 flex items-center pb-2">
        <div>
          <p class="px-2">{{ title }}</p>
          <p class="px-2 text-xs text-gray-400">by {{ author }}</p>
        </div>
        <div class="flex-grow" />
        <div v-if="chapters.length" class="cursor-pointer flex items-center justify-center mr-6 w-6 text-center" :class="chapters.length ? 'text-gray-300' : 'text-gray-400'" @mousedown.prevent @mouseup.prevent @click="clickChapterBtn">
          <span class="material-icons text-2xl">format_list_bulleted</span>
        </div>
        <span class="material-icons" @click="cancelStream">close</span>
      </div>
      <div class="absolute left-2 -top-10 bookCoverWrapper">
        <cards-book-cover :audiobook="audiobook" :download-cover="downloadedCover" :width="64" />
      </div>
      <audio-player-mini ref="audioPlayerMini" :loading="isLoading" :sleep-timer-running="isSleepTimerRunning" :sleep-timer-end-of-chapter-time="sleepTimerEndOfChapterTime" :sleep-timeout-current-time="sleepTimeoutCurrentTime" @updateTime="updateTime" @selectPlaybackSpeed="showPlaybackSpeedModal = true" @showSleepTimer="showSleepTimer" @hook:mounted="audioPlayerMounted" />
    </div>
    <modals-playback-speed-modal v-model="showPlaybackSpeedModal" :playback-speed.sync="playbackSpeed" @change="changePlaybackSpeed" />

    <modals-chapters-modal v-model="showChapterModal" :current-chapter="currentChapter" :chapters="chapters" @select="selectChapter" />

    <modals-sleep-timer-modal v-model="showSleepTimerModal" :current-time="sleepTimeoutCurrentTime" :sleep-timer-running="isSleepTimerRunning" :current-end-of-chapter-time="currentEndOfChapterTime" :end-of-chapter-time-set="sleepTimerEndOfChapterTime" @change="selectSleepTimeout" @cancel="cancelSleepTimer" />
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
      showSleepTimerModal: false,
      playbackSpeed: 1,
      showChapterModal: false,
      currentTime: 0,
      sleepTimeoutCurrentTime: 0,
      isSleepTimerRunning: false,
      sleepTimerEndOfChapterTime: false,
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
      if (this.$refs.audioPlayerMini) {
        this.$refs.audioPlayerMini.seek(chapter.start)
      }
      this.showChapterModal = false
    },
    async cancelStream() {
      this.currentTime = 0

      if (this.download) {
        if (this.$refs.audioPlayerMini) {
          this.$refs.audioPlayerMini.terminateStream()
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
          if (this.$refs.audioPlayerMini) {
            this.$refs.audioPlayerMini.terminateStream()
          }
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
      if (this.$refs.audioPlayerMini) {
        this.$refs.audioPlayerMini.setChunksReady(chunks, data.numSegments)
      }
    },
    streamReady() {
      console.log('[StreamContainer] Stream Ready')
      if (this.$refs.audioPlayerMini) {
        this.$refs.audioPlayerMini.setStreamReady()
      }
    },
    streamReset({ streamId, startTime }) {
      if (this.$refs.audioPlayerMini) {
        if (this.stream && this.stream.id === streamId) {
          this.$refs.audioPlayerMini.resetStream(startTime)
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
        if (this.$refs.audioPlayerMini) {
          this.$refs.audioPlayerMini.terminateStream()
        }
        this.stream = null
      }

      this.lastProgressTimeUpdate = 0
      console.log('[StreamContainer] Playing local', this.playingDownload)
      if (!this.$refs.audioPlayerMini) {
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
        isLocal: true
      }

      this.$refs.audioPlayerMini.set(audiobookStreamData, null, false)
    },
    streamOpen(stream) {
      if (this.download) {
        if (this.$refs.audioPlayerMini) {
          this.$refs.audioPlayerMini.terminateStream()
        }
      }

      this.lastProgressTimeUpdate = 0
      console.log('[StreamContainer] Stream Open: ' + this.title)

      if (!this.$refs.audioPlayerMini) {
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
        token: this.$store.getters['user/getToken']
      }
      this.$refs.audioPlayerMini.set(audiobookStreamData, stream, !this.stream)

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
      this.$store.dispatch('user/updateUserSettings', { playbackRate: speed })
    },
    settingsUpdated(settings) {
      if (this.$refs.audioPlayerMini && this.$refs.audioPlayerMini.currentPlaybackRate !== settings.playbackRate) {
        this.playbackSpeed = settings.playbackRate
        this.$refs.audioPlayerMini.updatePlaybackRate()
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

    this.setListeners()
    this.$store.commit('user/addSettingsListener', { id: 'streamContainer', meth: this.settingsUpdated })
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
    this.$store.commit('removeStreamListener')
  }
}
</script>

<style>
.bookCoverWrapper {
  box-shadow: 3px -2px 5px #00000066;
}
#streamContainer {
  box-shadow: 0px -8px 8px #11111177;
}
</style>