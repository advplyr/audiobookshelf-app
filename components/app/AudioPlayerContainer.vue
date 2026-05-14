<template>
  <div>
    <app-audio-player ref="audioPlayer" :bookmarks="bookmarks" :sleep-timer-running="isSleepTimerRunning" :sleep-time-remaining="sleepTimeRemaining" :serverLibraryItemId="serverLibraryItemId" @selectPlaybackSpeed="showPlaybackSpeedModal = true" @updateTime="(t) => (currentTime = t)" @showSleepTimer="showSleepTimer" @showBookmarks="showBookmarks" />

    <modals-playback-speed-modal v-model="showPlaybackSpeedModal" :playback-rate.sync="playbackSpeed" @update:playbackRate="updatePlaybackSpeed" @change="changePlaybackSpeed" />
    <modals-sleep-timer-modal v-model="showSleepTimerModal" :current-time="sleepTimeRemaining" :sleep-timer-running="isSleepTimerRunning" :current-end-of-chapter-time="currentEndOfChapterTime" :is-auto="isAutoSleepTimer" @change="selectSleepTimeout" @cancel="cancelSleepTimer" @increase="increaseSleepTimer" @decrease="decreaseSleepTimer" />
    <modals-bookmarks-modal v-model="showBookmarksModal" :bookmarks="bookmarks" :current-time="currentTime" :library-item-id="serverLibraryItemId" :playback-rate="playbackSpeed" @select="selectBookmark" />
  </div>
</template>

<script>
import { AbsAudioPlayer, AbsLogger } from '@/plugins/capacitor'
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
    },
    currentPlaybackSession() {
      return this.$store.state.currentPlaybackSession
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
      await AbsLogger.info({ tag: 'AudioPlayerContainer', message: `playLibraryItem: Received play request for library item ${payload.libraryItemId} ${payload.episodeId ? `episode ${payload.episodeId}` : ''}` })
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
     * Fetch the current user's media progress from the server for a given library item / episode.
     * Returns the server media progress object, or null if the request fails, times out, or the
     * response doesn't match the requested library item.
     *
     * The audio player's loading state is shown while the request is in flight so the user
     * doesn't tap play before we have a chance to update the timestamps. The request timeout
     * is 7 seconds so a slow/unresponsive server doesn't block the user for long.
     */
    async getServerMediaProgressForCurrentSession() {
      if (!this.$store.state.user.user || !this.$store.state.networkConnected) return null
      const libraryItemId = this.currentPlaybackSession?.libraryItemId
      const episodeId = this.currentPlaybackSession?.episodeId
      if (!libraryItemId) return null

      if (this.$refs.audioPlayer?.isCheckingServerProgress) {
        console.log('[AudioPlayerContainer] getServerMediaProgressForCurrentSession: already checking server progress')
        return null
      }

      const url = episodeId ? `/api/me/progress/${libraryItemId}/${episodeId}` : `/api/me/progress/${libraryItemId}`

      this.$refs.audioPlayer?.setIsCheckingServerProgress(true)
      try {
        const data = await this.$nativeHttp.get(url, { connectTimeout: 7000, readTimeout: 7000 })
        if (!data || data.libraryItemId !== libraryItemId) return null
        return data
      } catch (error) {
        console.error('[AudioPlayerContainer] Failed to get server media progress', error)
        return null
      } finally {
        this.$refs.audioPlayer?.setIsCheckingServerProgress(false)
      }
    },
    getLocalMediaProgressForCurrentSession() {
      if (!this.currentPlaybackSession) return null
      return this.$store.getters['globals/getLocalMediaProgressById'](this.currentPlaybackSession.localLibraryItem?.id, this.currentPlaybackSession.localEpisodeId)
    },
    /**
     * Sync the server media progress with the local media progress
     */
    async syncServerMediaProgressWithLocalMediaProgress(localMediaProgressId, serverMediaProgress) {
      try {
        const newLocalMediaProgress = await this.$db.syncServerMediaProgressWithLocalMediaProgress({
          localMediaProgressId,
          mediaProgress: serverMediaProgress
        })
        if (newLocalMediaProgress?.id) {
          this.$store.commit('globals/updateLocalMediaProgress', newLocalMediaProgress)
        }
      } catch (error) {
        console.error('[AudioPlayerContainer] Failed to sync server progress with local media progress', error)
      }
    },
    /**
     * Check if the server media progress is more recent than the local media progress and sync if so
     */
    async checkSyncServerProgressWithLocalProgress(localMediaProgress) {
      if (!localMediaProgress) return
      console.log('[AudioPlayerContainer] checkSyncServerProgressWithLocalProgress: checking server media progress for local media item open in player')
      const serverMediaProgress = await this.getServerMediaProgressForCurrentSession()
      if (!serverMediaProgress?.lastUpdate || serverMediaProgress.lastUpdate <= localMediaProgress.lastUpdate) return

      console.log('[AudioPlayerContainer] checkSyncServerProgressWithLocalProgress: server progress is more recent than local progress. Server current time:', serverMediaProgress.currentTime, 'vs local', localMediaProgress.currentTime, `(server lastUpdate=${serverMediaProgress.lastUpdate} > local lastUpdate=${localMediaProgress.lastUpdate})`)
      if (!this.$refs.audioPlayer?.isPlaying && serverMediaProgress.currentTime !== localMediaProgress.currentTime) {
        // Use seek() so the native audio player's current session is updated
        this.$refs.audioPlayer.seek(serverMediaProgress.currentTime)
      }

      await this.syncServerMediaProgressWithLocalMediaProgress(localMediaProgress.id, serverMediaProgress)
    },
    /**
     * When socket is reconnected after a delay, if a local media item is open in the player (paused)
     * we fetch the server media progress and sync it if it is more recent than the local progress
     *
     * If there is no socket connection we may have missed external progress updates
     */
    async socketReconnected() {
      if (!this.currentPlaybackSession) return
      // dont update timestamps if player is playing
      if (this.$refs.audioPlayer?.isPlaying) return

      if (this.$refs.audioPlayer.isLocalPlayMethod) {
        const localMediaProgress = this.getLocalMediaProgressForCurrentSession()
        if (!localMediaProgress) {
          console.error('[AudioPlayerContainer] socket reconnected: Local media progress not found')
          return
        }

        await this.checkSyncServerProgressWithLocalProgress(localMediaProgress)
      }
    },
    /**
     * When device re-gains focus then refresh the timestamps in the audio player
     * if local item is open then fetch the server media progress and update if more recent
     */
    async deviceFocused(hasFocus) {
      if (!this.currentPlaybackSession || !hasFocus) return
      // dont update timestamps if player is playing
      if (this.$refs.audioPlayer?.isPlaying) return

      if (this.$refs.audioPlayer.isLocalPlayMethod) {
        const localMediaProgress = this.getLocalMediaProgressForCurrentSession()
        if (!localMediaProgress) {
          console.error('[AudioPlayerContainer] device visibility: Local media progress not found')
          return
        }

        console.log('[AudioPlayerContainer] device visibility: found local media progress', localMediaProgress.currentTime, 'last time in player is', this.currentTime)
        this.$refs.audioPlayer.currentTime = localMediaProgress.currentTime
        this.$refs.audioPlayer.timeupdate()

        await this.checkSyncServerProgressWithLocalProgress(localMediaProgress)
      } else {
        // server item so fetch server media progress and update player time
        console.log('[AudioPlayerContainer] device visibility: checking server media progress for server media item open in player')
        const data = await this.getServerMediaProgressForCurrentSession()
        if (!data) return
        if (!this.$refs.audioPlayer?.isPlaying) {
          console.log('[AudioPlayerContainer] device visibility: got server media progress', data.currentTime, 'last time in player is', this.currentTime)
          // Only seek if the difference is greater than 1 second
          if (Math.abs(data.currentTime - this.currentTime) > 1) {
            // Use seek() so the native audio player's current session is updated
            this.$refs.audioPlayer.seek(data.currentTime)
          }
        }
      }
    }
  },
  async mounted() {
    this.onLocalMediaProgressUpdateListener = await AbsAudioPlayer.addListener('onLocalMediaProgressUpdate', this.onLocalMediaProgressUpdate)
    this.onSleepTimerEndedListener = await AbsAudioPlayer.addListener('onSleepTimerEnded', this.onSleepTimerEnded)
    this.onSleepTimerSetListener = await AbsAudioPlayer.addListener('onSleepTimerSet', this.onSleepTimerSet)
    this.onMediaPlayerChangedListener = await AbsAudioPlayer.addListener('onMediaPlayerChanged', this.onMediaPlayerChanged)

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
    this.$eventBus.$on('socket-reconnected', this.socketReconnected)
  },
  beforeDestroy() {
    this.onLocalMediaProgressUpdateListener?.remove()
    this.onSleepTimerEndedListener?.remove()
    this.onSleepTimerSetListener?.remove()
    this.onMediaPlayerChangedListener?.remove()

    this.$eventBus.$off('abs-ui-ready', this.onReady)
    this.$eventBus.$off('play-item', this.playLibraryItem)
    this.$eventBus.$off('pause-item', this.pauseItem)
    this.$eventBus.$off('close-stream', this.closeStreamOnly)
    this.$eventBus.$off('cast-local-item', this.castLocalItem)
    this.$eventBus.$off('user-settings', this.settingsUpdated)
    this.$eventBus.$off('playback-time-update', this.playbackTimeUpdate)
    this.$eventBus.$off('device-focus-update', this.deviceFocused)
    this.$eventBus.$off('socket-reconnected', this.socketReconnected)
  }
}
</script>
