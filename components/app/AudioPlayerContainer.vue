<template>
  <div>
    <app-audio-player ref="audioPlayer" :bookmarks="bookmarks" :sleep-timer-running="isSleepTimerRunning" :sleep-time-remaining="sleepTimeRemaining" :serverLibraryItemId="serverLibraryItemId" @selectPlaybackSpeed="showPlaybackSpeedModal = true" @updateTime="(t) => (currentTime = t)" @showSleepTimer="showSleepTimer" @showBookmarks="showBookmarks" />

    <modals-playback-speed-modal v-model="showPlaybackSpeedModal" :playback-rate.sync="playbackSpeed" @update:playbackRate="updatePlaybackSpeed" @change="changePlaybackSpeed" />
    <modals-sleep-timer-modal v-model="showSleepTimerModal" :current-time="sleepTimeRemaining" :sleep-timer-running="isSleepTimerRunning" :current-end-of-chapter-time="currentEndOfChapterTime" :is-auto="isAutoSleepTimer" @change="selectSleepTimeout" @cancel="cancelSleepTimer" @increase="increaseSleepTimer" @decrease="decreaseSleepTimer" />
    <modals-bookmarks-modal v-model="showBookmarksModal" :bookmarks="bookmarks" :current-time="currentTime" :library-item-id="serverLibraryItemId" @select="selectBookmark" />
  </div>
</template>

<script>
import { AbsAudioPlayer } from '@/plugins/capacitor'
import { Dialog } from '@capacitor/dialog'
import CellularPermissionHelpers from '@/mixins/cellularPermissionHelpers'

export default {
  data() {
    return {
      isReady: false,
      settingsLoaded: false,
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
      isAutoSleepTimer: false,
      onLocalMediaProgressUpdateListener: null,
      onSleepTimerEndedListener: null,
      onSleepTimerSetListener: null,
      onMediaPlayerChangedListener: null,
      sleepInterval: null,
      currentEndOfChapterTime: 0,
      serverLibraryItemId: null,
      serverEpisodeId: null
    }
  },
  mixins: [CellularPermissionHelpers],
  computed: {
    bookmarks() {
      if (!this.serverLibraryItemId) return []
      return this.$store.getters['user/getUserBookmarksForItem'](this.serverLibraryItemId)
    },
    isIos() {
      return this.$platform === 'ios'
    }
  },
  methods: {
    showBookmarks() {
      this.showBookmarksModal = true
    },
    selectBookmark(bookmark) {
      this.showBookmarksModal = false
      if (!bookmark || isNaN(bookmark.time)) return
      const bookmarkTime = Number(bookmark.time)
      if (this.$refs.audioPlayer) {
        this.$refs.audioPlayer.seek(bookmarkTime)
      }
    },
    onSleepTimerEnded({ value: currentPosition }) {
      this.isSleepTimerRunning = false
      if (currentPosition) {
        console.log('Sleep Timer Ended Current Position: ' + currentPosition)
      }
    },
    onSleepTimerSet(payload) {
      const { value: sleepTimeRemaining, isAuto } = payload
      console.log('SLEEP TIMER SET', JSON.stringify(payload))
      if (sleepTimeRemaining === 0) {
        console.log('Sleep timer canceled')
        this.isSleepTimerRunning = false
      } else {
        this.isSleepTimerRunning = true
      }

      this.isAutoSleepTimer = !!isAuto
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
      const chunks = data.chunks
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

      // Settings have been loaded (at least once, so it's safe to kickoff onReady)
      if (!this.settingsLoaded) {
        this.settingsLoaded = true
        this.notifyOnReady()
      }
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
        this.playServerLibraryItemAndCast(this.serverLibraryItemId, this.serverEpisodeId)
      }
    },
    playServerLibraryItemAndCast(libraryItemId, episodeId) {
      var playbackRate = 1
      if (this.$refs.audioPlayer) {
        playbackRate = this.$refs.audioPlayer.currentPlaybackRate || 1
      }
      AbsAudioPlayer.prepareLibraryItem({ libraryItemId, episodeId, playWhenReady: false, playbackRate })
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
      const libraryItemId = payload.libraryItemId
      const episodeId = payload.episodeId
      const startTime = payload.startTime
      const startWhenReady = !payload.paused

      const isLocal = libraryItemId.startsWith('local')
      if (!isLocal) {
        const hasPermission = await this.checkCellularPermission('streaming')
        if (!hasPermission) {
          this.$store.commit('setPlayerDoneStartingPlayback')
          return
        }
      }

      // When playing local library item and can also play this item from the server
      //   then store the server library item id so it can be used if a cast is made
      const serverLibraryItemId = payload.serverLibraryItemId || null
      const serverEpisodeId = payload.serverEpisodeId || null

      if (isLocal && this.$store.state.isCasting) {
        const { value } = await Dialog.confirm({
          title: 'Warning',
          message: `Cannot cast downloaded media items. Confirm to close cast and play on your device.`
        })
        if (!value) {
          this.$store.commit('setPlayerDoneStartingPlayback')
          return
        }
      }

      // if already playing this item then jump to start time
      if (this.$store.getters['getIsMediaStreaming'](libraryItemId, episodeId)) {
        console.log('Already streaming item', startTime)
        if (startTime !== undefined && startTime !== null) {
          // seek to start time
          AbsAudioPlayer.seek({ value: Math.floor(startTime) })
        } else if (this.$refs.audioPlayer) {
          this.$refs.audioPlayer.play()
        }
        this.$store.commit('setPlayerDoneStartingPlayback')
        return
      }

      this.serverLibraryItemId = null
      this.serverEpisodeId = null

      let playbackRate = 1
      if (this.$refs.audioPlayer) {
        playbackRate = this.$refs.audioPlayer.currentPlaybackRate || 1
      }

      console.log('Called playLibraryItem', libraryItemId)
      const preparePayload = { libraryItemId, episodeId, playWhenReady: startWhenReady, playbackRate }
      if (startTime !== undefined && startTime !== null) preparePayload.startTime = startTime
      AbsAudioPlayer.prepareLibraryItem(preparePayload)
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
            if (episodeId && !episodeId.startsWith('local')) {
              this.serverEpisodeId = episodeId
            } else {
              this.serverEpisodeId = serverEpisodeId
            }
          }
        })
        .catch((error) => {
          console.error('Failed', error)
          this.$toast.error('Failed to play')
        })
        .finally(() => {
          this.$store.commit('setPlayerDoneStartingPlayback')
        })
    },
    pauseItem() {
      if (this.$refs.audioPlayer && this.$refs.audioPlayer.isPlaying) {
        this.$refs.audioPlayer.pause()
      }
    },
    onLocalMediaProgressUpdate(localMediaProgress) {
      console.log('Got local media progress update', localMediaProgress.progress, JSON.stringify(localMediaProgress))
      this.$store.commit('globals/updateLocalMediaProgress', localMediaProgress)
    },
    onMediaPlayerChanged(data) {
      this.$store.commit('setMediaPlayer', data.value)
    },
    onReady() {
      // The UI is reporting elsewhere we are ready
      this.isReady = true
      this.notifyOnReady()
    },
    notifyOnReady() {
      // TODO: was used on iOS to open last played media. May be removed
      if (!this.isIos) return

      // If settings aren't loaded yet, native player will receive incorrect settings
      console.log('Notify on ready... settingsLoaded:', this.settingsLoaded, 'isReady:', this.isReady)
      if (this.settingsLoaded && this.isReady && this.$store.state.isFirstAudioLoad) {
        this.$store.commit('setIsFirstAudioLoad', false) // Only run this once on app launch
        AbsAudioPlayer.onReady()
      }
    },
    playbackTimeUpdate(currentTime) {
      this.$refs.audioPlayer?.seek(currentTime)
    },
    /**
     * When device gains focus then refresh the timestamps in the audio player
     */
    deviceFocused(hasFocus) {
      if (!this.$store.state.currentPlaybackSession) return

      if (hasFocus) {
        if (!this.$refs.audioPlayer?.isPlaying) {
          const playbackSession = this.$store.state.currentPlaybackSession
          if (this.$refs.audioPlayer.isLocalPlayMethod) {
            const localLibraryItemId = playbackSession.localLibraryItem?.id
            const localEpisodeId = playbackSession.localEpisodeId
            if (!localLibraryItemId) {
              console.error('[AudioPlayerContainer] device visibility: no local library item for session', JSON.stringify(playbackSession))
              return
            }
            const localMediaProgress = this.$store.state.globals.localMediaProgress.find((mp) => {
              if (localEpisodeId) return mp.localEpisodeId === localEpisodeId
              return mp.localLibraryItemId === localLibraryItemId
            })
            if (localMediaProgress) {
              console.log('[AudioPlayerContainer] device visibility: found local media progress', localMediaProgress.currentTime, 'last time in player is', this.currentTime)
              this.$refs.audioPlayer.currentTime = localMediaProgress.currentTime
              this.$refs.audioPlayer.timeupdate()
            } else {
              console.error('[AudioPlayerContainer] device visibility: Local media progress not found')
            }
          } else {
            const libraryItemId = playbackSession.libraryItemId
            const episodeId = playbackSession.episodeId
            const url = episodeId ? `/api/me/progress/${libraryItemId}/${episodeId}` : `/api/me/progress/${libraryItemId}`
            this.$nativeHttp
              .get(url)
              .then((data) => {
                if (!this.$refs.audioPlayer?.isPlaying && data.libraryItemId === libraryItemId) {
                  console.log('[AudioPlayerContainer] device visibility: got server media progress', data.currentTime, 'last time in player is', this.currentTime)
                  this.$refs.audioPlayer.currentTime = data.currentTime
                  this.$refs.audioPlayer.timeupdate()
                }
              })
              .catch((error) => {
                console.error('[AudioPlayerContainer] device visibility: Failed to get progress', error)
              })
          }
        }
      }
    }
  },
  mounted() {
    this.onLocalMediaProgressUpdateListener = AbsAudioPlayer.addListener('onLocalMediaProgressUpdate', this.onLocalMediaProgressUpdate)
    this.onSleepTimerEndedListener = AbsAudioPlayer.addListener('onSleepTimerEnded', this.onSleepTimerEnded)
    this.onSleepTimerSetListener = AbsAudioPlayer.addListener('onSleepTimerSet', this.onSleepTimerSet)
    this.onMediaPlayerChangedListener = AbsAudioPlayer.addListener('onMediaPlayerChanged', this.onMediaPlayerChanged)

    this.playbackSpeed = this.$store.getters['user/getUserSetting']('playbackRate')
    console.log(`[AudioPlayerContainer] Init Playback Speed: ${this.playbackSpeed}`)

    this.$eventBus.$on('abs-ui-ready', this.onReady)
    this.$eventBus.$on('play-item', this.playLibraryItem)
    this.$eventBus.$on('pause-item', this.pauseItem)
    this.$eventBus.$on('close-stream', this.closeStreamOnly)
    this.$eventBus.$on('cast-local-item', this.castLocalItem)
    this.$eventBus.$on('user-settings', this.settingsUpdated)
    this.$eventBus.$on('playback-time-update', this.playbackTimeUpdate)
    this.$eventBus.$on('device-focus-update', this.deviceFocused)
  },
  beforeDestroy() {
    if (this.onLocalMediaProgressUpdateListener) this.onLocalMediaProgressUpdateListener.remove()
    if (this.onSleepTimerEndedListener) this.onSleepTimerEndedListener.remove()
    if (this.onSleepTimerSetListener) this.onSleepTimerSetListener.remove()
    if (this.onMediaPlayerChangedListener) this.onMediaPlayerChangedListener.remove()

    this.$eventBus.$off('abs-ui-ready', this.onReady)
    this.$eventBus.$off('play-item', this.playLibraryItem)
    this.$eventBus.$off('pause-item', this.pauseItem)
    this.$eventBus.$off('close-stream', this.closeStreamOnly)
    this.$eventBus.$off('cast-local-item', this.castLocalItem)
    this.$eventBus.$off('user-settings', this.settingsUpdated)
    this.$eventBus.$off('playback-time-update', this.playbackTimeUpdate)
    this.$eventBus.$off('device-focus-update', this.deviceFocused)
  }
}
</script>
