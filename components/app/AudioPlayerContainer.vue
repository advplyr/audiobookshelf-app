<template>
  <div>
    <app-audio-player ref="audioPlayer" :bookmarks="bookmarks" :sleep-timer-running="isSleepTimerRunning" :sleep-time-remaining="sleepTimeRemaining" @selectPlaybackSpeed="showPlaybackSpeedModal = true" @updateTime="(t) => (currentTime = t)" @showSleepTimer="showSleepTimer" @showBookmarks="showBookmarks" />

    <modals-playback-speed-modal v-model="showPlaybackSpeedModal" :playback-rate.sync="playbackSpeed" @update:playbackRate="updatePlaybackSpeed" @change="changePlaybackSpeed" />
    <modals-sleep-timer-modal v-model="showSleepTimerModal" :current-time="sleepTimeRemaining" :sleep-timer-running="isSleepTimerRunning" :current-end-of-chapter-time="currentEndOfChapterTime" @change="selectSleepTimeout" @cancel="cancelSleepTimer" @increase="increaseSleepTimer" @decrease="decreaseSleepTimer" />
    <modals-bookmarks-modal v-model="showBookmarksModal" :bookmarks="bookmarks" :current-time="currentTime" :library-item-id="serverLibraryItemId" @select="selectBookmark" />
  </div>
</template>

<script>
import { AbsAudioPlayer } from '@/plugins/capacitor'
import { Dialog } from '@capacitor/dialog'

export default {
  data() {
    return {
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
      onLocalMediaProgressUpdateListener: null,
      onSleepTimerEndedListener: null,
      onSleepTimerSetListener: null,
      onMediaPlayerChangedListener: null,
      onProgressSyncFailing: null,
      sleepInterval: null,
      currentEndOfChapterTime: 0,
      serverLibraryItemId: null,
      syncFailedToast: null
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
      if (!this.serverLibraryItemId) return []
      return this.$store.getters['user/getUserBookmarksForItem'](this.serverLibraryItemId)
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
        this.$refs.audioPlayer.closePlayback()
      }
    },
    castLocalItem() {
      if (!this.serverLibraryItemId) {
        this.$toast.error(`Cannot cast locally downloaded media`)
      } else {
        // Change to server library item
        this.playServerLibraryItemAndCast(this.serverLibraryItemId)
      }
    },
    playServerLibraryItemAndCast(libraryItemId) {
      var playbackRate = 1
      if (this.$refs.audioPlayer) {
        playbackRate = this.$refs.audioPlayer.currentPlaybackRate || 1
      }
      AbsAudioPlayer.prepareLibraryItem({ libraryItemId, episodeId: null, playWhenReady: false, playbackRate })
        .then((data) => {
          if (data.error) {
            const errorMsg = data.error || 'Failed to play'
            this.$toast.error(errorMsg)
          } else {
            console.log('Library item play response', JSON.stringify(data))
            AbsAudioPlayer.requestSession()
          }
        })
        .catch((error) => {
          console.error('Failed', error)
          this.$toast.error('Failed to play')
        })
    },
    async playLibraryItem(payload) {
      var libraryItemId = payload.libraryItemId
      var episodeId = payload.episodeId

      // When playing local library item and can also play this item from the server
      //   then store the server library item id so it can be used if a cast is made
      var serverLibraryItemId = payload.serverLibraryItemId || null

      if (libraryItemId.startsWith('local') && this.$store.state.isCasting) {
        const { value } = await Dialog.confirm({
          title: 'Warning',
          message: `Cannot cast downloaded media items. Confirm to close cast and play on your device.`
        })
        if (!value) {
          return
        }
      }

      this.serverLibraryItemId = null

      var playbackRate = 1
      if (this.$refs.audioPlayer) {
        playbackRate = this.$refs.audioPlayer.currentPlaybackRate || 1
      }

      console.log('Called playLibraryItem', libraryItemId)
      AbsAudioPlayer.prepareLibraryItem({ libraryItemId, episodeId, playWhenReady: true, playbackRate })
        .then((data) => {
          if (data.error) {
            const errorMsg = data.error || 'Failed to play'
            this.$toast.error(errorMsg)
          } else {
            console.log('Library item play response', JSON.stringify(data))
            if (!libraryItemId.startsWith('local')) {
              this.serverLibraryItemId = libraryItemId
            } else {
              this.serverLibraryItemId = serverLibraryItemId
            }
          }
        })
        .catch((error) => {
          console.error('Failed', error)
          this.$toast.error('Failed to play')
        })
    },
    pauseItem() {
      if (this.$refs.audioPlayer && !this.$refs.audioPlayer.isPaused) {
        this.$refs.audioPlayer.pause()
      }
    },
    onLocalMediaProgressUpdate(localMediaProgress) {
      console.log('Got local media progress update', localMediaProgress.progress, JSON.stringify(localMediaProgress))
      this.$store.commit('globals/updateLocalMediaProgress', localMediaProgress)
    },
    onMediaPlayerChanged(data) {
      var mediaPlayer = data.value
      this.$store.commit('setMediaPlayer', mediaPlayer)
    },
    showProgressSyncIsFailing() {
      if (!isNaN(this.syncFailedToast)) this.$toast.dismiss(this.syncFailedToast)
      this.syncFailedToast = this.$toast('Progress is not being synced', { timeout: false, type: 'error' })
    }
  },
  mounted() {
    this.onLocalMediaProgressUpdateListener = AbsAudioPlayer.addListener('onLocalMediaProgressUpdate', this.onLocalMediaProgressUpdate)
    this.onSleepTimerEndedListener = AbsAudioPlayer.addListener('onSleepTimerEnded', this.onSleepTimerEnded)
    this.onSleepTimerSetListener = AbsAudioPlayer.addListener('onSleepTimerSet', this.onSleepTimerSet)
    this.onMediaPlayerChangedListener = AbsAudioPlayer.addListener('onMediaPlayerChanged', this.onMediaPlayerChanged)
    this.onProgressSyncFailing = AbsAudioPlayer.addListener('onProgressSyncFailing', this.showProgressSyncIsFailing)

    this.playbackSpeed = this.$store.getters['user/getUserSetting']('playbackRate')
    console.log(`[AudioPlayerContainer] Init Playback Speed: ${this.playbackSpeed}`)

    this.setListeners()
    this.$eventBus.$on('play-item', this.playLibraryItem)
    this.$eventBus.$on('pause-item', this.pauseItem)
    this.$eventBus.$on('close-stream', this.closeStreamOnly)
    this.$eventBus.$on('cast-local-item', this.castLocalItem)
    this.$store.commit('user/addSettingsListener', { id: 'streamContainer', meth: this.settingsUpdated })
  },
  beforeDestroy() {
    if (this.onLocalMediaProgressUpdateListener) this.onLocalMediaProgressUpdateListener.remove()
    if (this.onSleepTimerEndedListener) this.onSleepTimerEndedListener.remove()
    if (this.onSleepTimerSetListener) this.onSleepTimerSetListener.remove()
    if (this.onMediaPlayerChangedListener) this.onMediaPlayerChangedListener.remove()
    if (this.onProgressSyncFailing) this.onProgressSyncFailing.remove()

    // if (this.$server.socket) {
    //   this.$server.socket.off('stream_open', this.streamOpen)
    //   this.$server.socket.off('stream_closed', this.streamClosed)
    //   this.$server.socket.off('stream_progress', this.streamProgress)
    //   this.$server.socket.off('stream_ready', this.streamReady)
    //   this.$server.socket.off('stream_reset', this.streamReset)
    // }
    this.$eventBus.$off('play-item', this.playLibraryItem)
    this.$eventBus.$off('pause-item', this.pauseItem)
    this.$eventBus.$off('close-stream', this.closeStreamOnly)
    this.$eventBus.$off('cast-local-item', this.castLocalItem)
    this.$store.commit('user/removeSettingsListener', 'streamContainer')
  }
}
</script>