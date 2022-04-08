<template>
  <div>
    <app-audio-player ref="audioPlayer" :playing.sync="isPlaying" :bookmarks="bookmarks" :sleep-timer-running="isSleepTimerRunning" :sleep-time-remaining="sleepTimeRemaining" @selectPlaybackSpeed="showPlaybackSpeedModal = true" @updateTime="(t) => (currentTime = t)" @showSleepTimer="showSleepTimer" @showBookmarks="showBookmarks" />

    <modals-playback-speed-modal v-model="showPlaybackSpeedModal" :playback-rate.sync="playbackSpeed" @update:playbackRate="updatePlaybackSpeed" @change="changePlaybackSpeed" />
    <modals-sleep-timer-modal v-model="showSleepTimerModal" :current-time="sleepTimeRemaining" :sleep-timer-running="isSleepTimerRunning" :current-end-of-chapter-time="currentEndOfChapterTime" @change="selectSleepTimeout" @cancel="cancelSleepTimer" @increase="increaseSleepTimer" @decrease="decreaseSleepTimer" />
    <modals-bookmarks-modal v-model="showBookmarksModal" :bookmarks="bookmarks" :current-time="currentTime" @select="selectBookmark" />
  </div>
</template>

<script>
import { AbsAudioPlayer } from '@/plugins/capacitor'

export default {
  data() {
    return {
      isPlaying: false,
      audioPlayerReady: false,
      stream: null,
      download: null,
      showPlaybackSpeedModal: false,
      showBookmarksModal: false,
      showSleepTimerModal: false,
      playbackSpeed: 1,
      currentTime: 0,
      isSleepTimerRunning: false,
      sleepTimerEndTime: 0,
      sleepTimeRemaining: 0,
      onSleepTimerEndedListener: null,
      onSleepTimerSetListener: null,
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
    bookmarks() {
      // return this.$store.getters['user/getUserBookmarksForItem'](this.)
      return []
    },
    socketConnected() {
      return this.$store.state.socketConnected
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
        // TODO: Was syncing to the server here before
      }
    },
    onSleepTimerSet({ value: sleepTimeRemaining }) {
      console.log('SLEEP TIMER SET', sleepTimeRemaining)
      if (sleepTimeRemaining === 0) {
        console.log('Sleep timer canceled')
        this.isSleepTimerRunning = false
      } else {
        this.isSleepTimerRunning = true
      }

      this.sleepTimeRemaining = sleepTimeRemaining
    },
    showSleepTimer() {
      if (this.$refs.audioPlayer && this.$refs.audioPlayer.currentChapter) {
        this.currentEndOfChapterTime = Math.floor(this.$refs.audioPlayer.currentChapter.end)
      } else {
        this.currentEndOfChapterTime = 0
      }
      this.showSleepTimerModal = true
    },
    async selectSleepTimeout({ time, isChapterTime }) {
      console.log('Setting sleep timer', time, isChapterTime)
      var res = await AbsAudioPlayer.setSleepTimer({ time: String(time), isChapterTime })
      if (!res.success) {
        return this.$toast.error('Sleep timer did not set, invalid time')
      }
    },
    increaseSleepTimer() {
      // Default time to increase = 5 min
      AbsAudioPlayer.increaseSleepTime({ time: '300000' })
    },
    decreaseSleepTimer() {
      AbsAudioPlayer.decreaseSleepTime({ time: '300000' })
    },
    async cancelSleepTimer() {
      console.log('Canceling sleep timer')
      await AbsAudioPlayer.cancelSleepTimer()
    },
    streamClosed() {
      console.log('Stream Closed')
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
      console.log('received stream reset', streamId, startTime)
      if (this.$refs.audioPlayer) {
        if (this.stream && this.stream.id === streamId) {
          this.$refs.audioPlayer.resetStream(startTime)
        }
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
    setListeners() {
      // if (!this.$server.socket) {
      //   console.error('Invalid server socket not set')
      //   return
      // }
      // this.$server.socket.on('stream_open', this.streamOpen)
      // this.$server.socket.on('stream_closed', this.streamClosed)
      // this.$server.socket.on('stream_progress', this.streamProgress)
      // this.$server.socket.on('stream_ready', this.streamReady)
      // this.$server.socket.on('stream_reset', this.streamReset)
    },
    closeStreamOnly() {
      // If user logs out or disconnects from server and not playing local
      if (this.$refs.audioPlayer && !this.$refs.audioPlayer.isLocalPlayMethod) {
        this.$refs.audioPlayer.terminateStream()
      }
    },
    async playLibraryItem(libraryItemId) {
      console.log('Called playLibraryItem', libraryItemId)
      AbsAudioPlayer.prepareLibraryItem({ libraryItemId, playWhenReady: true })
        .then((data) => {
          console.log('Library item play response', JSON.stringify(data))
        })
        .catch((error) => {
          console.error('Failed', error)
        })
    }
  },
  mounted() {
    this.onSleepTimerEndedListener = AbsAudioPlayer.addListener('onSleepTimerEnded', this.onSleepTimerEnded)
    this.onSleepTimerSetListener = AbsAudioPlayer.addListener('onSleepTimerSet', this.onSleepTimerSet)

    this.playbackSpeed = this.$store.getters['user/getUserSetting']('playbackRate')
    console.log(`[AudioPlayerContainer] Init Playback Speed: ${this.playbackSpeed}`)

    this.setListeners()
    this.$eventBus.$on('play-item', this.playLibraryItem)
    this.$eventBus.$on('close-stream', this.closeStreamOnly)
    this.$store.commit('user/addSettingsListener', { id: 'streamContainer', meth: this.settingsUpdated })
  },
  beforeDestroy() {
    if (this.onSleepTimerEndedListener) this.onSleepTimerEndedListener.remove()
    if (this.onSleepTimerSetListener) this.onSleepTimerSetListener.remove()

    // if (this.$server.socket) {
    //   this.$server.socket.off('stream_open', this.streamOpen)
    //   this.$server.socket.off('stream_closed', this.streamClosed)
    //   this.$server.socket.off('stream_progress', this.streamProgress)
    //   this.$server.socket.off('stream_ready', this.streamReady)
    //   this.$server.socket.off('stream_reset', this.streamReset)
    // }
    this.$eventBus.$off('play-item', this.playLibraryItem)
    this.$eventBus.$off('close-stream', this.closeStreamOnly)
    this.$store.commit('user/removeSettingsListener', 'streamContainer')
  }
}
</script>