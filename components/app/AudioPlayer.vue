<template>
  <div v-if="playbackSession" id="streamContainer" class="fixed top-0 left-0 layout-wrapper right-0 z-50 pointer-events-none" :class="showFullscreen ? 'fullscreen' : ''">
    <div v-if="showFullscreen" class="w-full h-full z-10 bg-bg absolute top-0 left-0 pointer-events-auto">
      <div class="top-2 left-4 absolute cursor-pointer">
        <span class="material-icons text-5xl" @click="collapseFullscreen">expand_more</span>
      </div>
      <div v-show="showCastBtn" class="top-3.5 right-20 absolute cursor-pointer">
        <span class="material-icons text-3xl" :class="isCasting ? 'text-success' : ''" @click="castClick">cast</span>
      </div>
      <div class="top-4 right-4 absolute cursor-pointer">
        <ui-dropdown-menu ref="dropdownMenu" :items="menuItems" @action="clickMenuAction">
          <span class="material-icons text-3xl">more_vert</span>
        </ui-dropdown-menu>
      </div>
    </div>

    <div v-if="useChapterTrack && showFullscreen" class="absolute total-track w-full px-3 z-30">
      <div class="flex">
        <p class="font-mono text-white text-opacity-90" style="font-size: 0.8rem">{{ currentTimePretty }}</p>
        <div class="flex-grow" />
        <p class="font-mono text-white text-opacity-90" style="font-size: 0.8rem">{{ totalTimeRemainingPretty }}</p>
      </div>
      <div class="w-full">
        <div class="h-1 w-full bg-gray-500 bg-opacity-50 relative">
          <div ref="totalReadyTrack" class="h-full bg-gray-600 absolute top-0 left-0 pointer-events-none" />
          <div ref="totalBufferedTrack" class="h-full bg-gray-500 absolute top-0 left-0 pointer-events-none" />
          <div ref="totalPlayedTrack" class="h-full bg-gray-200 absolute top-0 left-0 pointer-events-none" />
        </div>
      </div>
    </div>

    <div class="cover-wrapper absolute z-30 pointer-events-auto" :class="bookCoverAspectRatio === 1 ? 'square-cover' : ''" @click="clickContainer">
      <div class="cover-container bookCoverWrapper bg-black bg-opacity-75 w-full h-full">
        <covers-book-cover v-if="libraryItem || localLibraryItemCoverSrc" :library-item="libraryItem" :download-cover="localLibraryItemCoverSrc" :width="bookCoverWidth" :book-cover-aspect-ratio="bookCoverAspectRatio" />
      </div>
    </div>

    <div class="title-author-texts absolute z-30 left-0 right-0 overflow-hidden">
      <p class="title-text font-book truncate">{{ title }}</p>
      <p class="author-text text-white text-opacity-75 truncate">by {{ authorName }}</p>
    </div>

    <div id="streamContainer" class="w-full z-20 bg-primary absolute bottom-0 left-0 right-0 p-2 pointer-events-auto transition-all" @click="clickContainer">
      <div v-if="showFullscreen" class="absolute top-0 left-0 right-0 w-full py-3 mx-auto px-3" style="max-width: 380px">
        <div class="flex items-center justify-between pointer-events-auto">
          <span v-if="!isPodcast" class="material-icons text-3xl text-white text-opacity-75 cursor-pointer" @click="$emit('showBookmarks')">{{ bookmarks.length ? 'bookmark' : 'bookmark_border' }}</span>
          <!-- hidden for podcasts but still using this as a placeholder -->
          <span v-else class="material-icons text-3xl text-white text-opacity-0">bookmark</span>

          <span class="font-mono text-white text-opacity-75 cursor-pointer" style="font-size: 1.35rem" @click="$emit('selectPlaybackSpeed')">{{ currentPlaybackRate }}x</span>
          <svg v-if="!sleepTimerRunning" xmlns="http://www.w3.org/2000/svg" class="h-7 w-7 text-white text-opacity-75 cursor-pointer" fill="none" viewBox="0 0 24 24" stroke="currentColor" @click.stop="$emit('showSleepTimer')">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M20.354 15.354A9 9 0 018.646 3.646 9.003 9.003 0 0012 21a9.003 9.003 0 008.354-5.646z" />
          </svg>
          <div v-else class="h-7 w-7 flex items-center justify-around cursor-pointer" @click.stop="$emit('showSleepTimer')">
            <p class="text-xl font-mono text-success">{{ sleepTimeRemainingPretty }}</p>
          </div>

          <span class="material-icons text-3xl text-white cursor-pointer" :class="chapters.length ? 'text-opacity-75' : 'text-opacity-10'" @click="showChapterModal = true">format_list_bulleted</span>
        </div>
      </div>

      <div id="playerControls" class="absolute right-0 bottom-0 py-2">
        <div class="flex items-center justify-center">
          <span v-show="showFullscreen" class="material-icons next-icon text-white text-opacity-75 cursor-pointer" :class="isLoading ? 'text-opacity-10' : 'text-opacity-75'" @click.stop="jumpChapterStart">first_page</span>
          <span class="material-icons jump-icon text-white cursor-pointer" :class="isLoading ? 'text-opacity-10' : 'text-opacity-75'" @click.stop="backward10">replay_10</span>
          <div class="play-btn cursor-pointer shadow-sm bg-accent flex items-center justify-center rounded-full text-primary mx-4" :class="seekLoading ? 'animate-spin' : ''" @mousedown.prevent @mouseup.prevent @click.stop="playPauseClick">
            <span v-if="!isLoading" class="material-icons">{{ seekLoading ? 'autorenew' : !isPlaying ? 'play_arrow' : 'pause' }}</span>
            <widgets-spinner-icon v-else class="h-8 w-8" />
          </div>
          <span class="material-icons jump-icon text-white cursor-pointer" :class="isLoading ? 'text-opacity-10' : 'text-opacity-75'" @click.stop="forward10">forward_10</span>
          <span v-show="showFullscreen" class="material-icons next-icon text-white cursor-pointer" :class="nextChapter && !isLoading ? 'text-opacity-75' : 'text-opacity-10'" @click.stop="jumpNextChapter">last_page</span>
        </div>
      </div>

      <div id="playerTrack" class="absolute bottom-0 left-0 w-full px-3">
        <div ref="track" class="h-2 w-full bg-gray-500 bg-opacity-50 relative" :class="isLoading ? 'animate-pulse' : ''" @click="clickTrack">
          <div ref="readyTrack" class="h-full bg-gray-600 absolute top-0 left-0 pointer-events-none" />
          <div ref="bufferedTrack" class="h-full bg-gray-500 absolute top-0 left-0 pointer-events-none" />
          <div ref="playedTrack" class="h-full bg-gray-200 absolute top-0 left-0 pointer-events-none" />
        </div>
        <div class="flex pt-0.5">
          <p class="font-mono text-white text-opacity-90" style="font-size: 0.8rem" ref="currentTimestamp">0:00</p>
          <div class="flex-grow" />
          <p v-show="showFullscreen" class="text-sm truncate text-white text-opacity-75" style="max-width: 65%">{{ currentChapterTitle }}</p>
          <div class="flex-grow" />
          <p class="font-mono text-white text-opacity-90" style="font-size: 0.8rem">{{ timeRemainingPretty }}</p>
        </div>
      </div>
    </div>

    <modals-chapters-modal v-model="showChapterModal" :current-chapter="currentChapter" :chapters="chapters" @select="selectChapter" />
  </div>
</template>

<script>
import { Capacitor } from '@capacitor/core'
import { AbsAudioPlayer } from '@/plugins/capacitor'

export default {
  props: {
    bookmarks: {
      type: Array,
      default: () => []
    },
    sleepTimerRunning: Boolean,
    sleepTimeRemaining: Number
  },
  data() {
    return {
      playbackSession: null,
      showChapterModal: false,
      showFullscreen: false,
      totalDuration: 0,
      currentPlaybackRate: 1,
      currentTime: 0,
      bufferedTime: 0,
      playInterval: null,
      trackWidth: 0,
      isPlaying: false,
      isEnded: false,
      volume: 0.5,
      readyTrackWidth: 0,
      playedTrackWidth: 0,
      seekedTime: 0,
      seekLoading: false,
      onPlaybackSessionListener: null,
      onPlaybackClosedListener: null,
      onPlayingUpdateListener: null,
      onMetadataListener: null,
      touchStartY: 0,
      touchStartTime: 0,
      touchEndY: 0,
      useChapterTrack: false,
      isLoading: false
    }
  },
  computed: {
    menuItems() {
      var items = []
      items.push({
        text: 'Chapter Track',
        value: 'chapter_track',
        icon: this.useChapterTrack ? 'check_box' : 'check_box_outline_blank'
      })
      items.push({
        text: 'Close Player',
        value: 'close',
        icon: 'close'
      })
      return items
    },
    bookCoverAspectRatio() {
      return this.$store.getters['getBookCoverAspectRatio']
    },
    bookCoverWidth() {
      if (this.bookCoverAspectRatio === 1) {
        return this.showFullscreen ? 260 : 60
      }
      return this.showFullscreen ? 200 : 60
    },
    showCastBtn() {
      return this.$store.state.isCastAvailable && !this.isLocalPlayMethod
    },
    isCasting() {
      return this.mediaPlayer === 'cast-player'
    },
    mediaPlayer() {
      return this.playbackSession ? this.playbackSession.mediaPlayer : null
    },
    mediaType() {
      return this.playbackSession ? this.playbackSession.mediaType : null
    },
    isPodcast() {
      return this.mediaType === 'podcast'
    },
    mediaMetadata() {
      return this.playbackSession ? this.playbackSession.mediaMetadata : null
    },
    libraryItem() {
      return this.playbackSession ? this.playbackSession.libraryItem || null : null
    },
    localLibraryItem() {
      return this.playbackSession ? this.playbackSession.localLibraryItem || null : null
    },
    localLibraryItemCoverSrc() {
      var localItemCover = this.localLibraryItem ? this.localLibraryItem.coverContentUrl : null
      if (localItemCover) return Capacitor.convertFileSrc(localItemCover)
      return null
    },
    playMethod() {
      return this.playbackSession ? this.playbackSession.playMethod : null
    },
    isLocalPlayMethod() {
      return this.playMethod == this.$constants.PlayMethod.LOCAL
    },
    title() {
      if (this.playbackSession) return this.playbackSession.displayTitle
      return this.mediaMetadata ? this.mediaMetadata.title : 'Title'
    },
    authorName() {
      if (this.playbackSession) return this.playbackSession.displayAuthor
      return this.mediaMetadata ? this.mediaMetadata.authorName : 'Author'
    },
    chapters() {
      if (this.playbackSession && this.playbackSession.chapters) {
        return this.playbackSession.chapters
      }
      return []
    },
    currentChapter() {
      if (!this.chapters.length) return null
      return this.chapters.find((ch) => Number(Number(ch.start).toFixed(2)) <= this.currentTime && Number(Number(ch.end).toFixed(2)) > this.currentTime)
    },
    nextChapter() {
      if (!this.chapters.length) return
      return this.chapters.find((c) => Number(Number(c.start).toFixed(2)) > this.currentTime)
    },
    currentChapterTitle() {
      return this.currentChapter ? this.currentChapter.title : ''
    },
    currentChapterDuration() {
      return this.currentChapter ? this.currentChapter.end - this.currentChapter.start : this.totalDuration
    },
    totalDurationPretty() {
      return this.$secondsToTimestamp(this.totalDuration)
    },
    currentTimePretty() {
      return this.$secondsToTimestamp(this.currentTime)
    },
    timeRemaining() {
      if (this.useChapterTrack && this.currentChapter) {
        var currChapTime = this.currentTime - this.currentChapter.start
        return (this.currentChapterDuration - currChapTime) / this.currentPlaybackRate
      }
      return this.totalTimeRemaining
    },
    totalTimeRemaining() {
      return (this.totalDuration - this.currentTime) / this.currentPlaybackRate
    },
    totalTimeRemainingPretty() {
      if (this.totalTimeRemaining < 0) {
        return this.$secondsToTimestamp(this.totalTimeRemaining * -1)
      }
      return '-' + this.$secondsToTimestamp(this.totalTimeRemaining)
    },
    timeRemainingPretty() {
      if (this.timeRemaining < 0) {
        return this.$secondsToTimestamp(this.timeRemaining * -1)
      }
      return '-' + this.$secondsToTimestamp(this.timeRemaining)
    },
    timeLeftInChapter() {
      if (!this.currentChapter) return 0
      return this.currentChapter.end - this.currentTime
    },
    sleepTimeRemainingPretty() {
      if (!this.sleepTimeRemaining) return '0s'
      var secondsRemaining = Math.round(this.sleepTimeRemaining)
      if (secondsRemaining > 91) {
        return Math.ceil(secondsRemaining / 60) + 'm'
      } else {
        return secondsRemaining + 's'
      }
    }
  },
  methods: {
    selectChapter(chapter) {
      this.seek(chapter.start)
      this.showChapterModal = false
    },
    castClick() {
      console.log('Cast Btn Click')
      if (this.isLocalPlayMethod) {
        this.$toast.warn('Cannot cast downloaded media items')
        return
      }

      AbsAudioPlayer.requestSession()
    },
    clickContainer() {
      this.showFullscreen = true

      // Update track for total time bar if useChapterTrack is set
      this.$nextTick(() => {
        this.updateTrack()
      })
    },
    collapseFullscreen() {
      this.showFullscreen = false
      this.forceCloseDropdownMenu()
    },
    jumpNextChapter() {
      if (this.isLoading) return
      if (!this.nextChapter) return
      this.seek(this.nextChapter.start)
    },
    jumpChapterStart() {
      if (this.isLoading) return
      if (!this.currentChapter) {
        return this.restart()
      }

      // If 4 seconds or less into current chapter, then go to previous
      if (this.currentTime - this.currentChapter.start <= 4) {
        var currChapterIndex = this.chapters.findIndex((ch) => Number(ch.start) <= this.currentTime && Number(ch.end) >= this.currentTime)
        if (currChapterIndex > 0) {
          var prevChapter = this.chapters[currChapterIndex - 1]
          this.seek(prevChapter.start)
        }
      } else {
        this.seek(this.currentChapter.start)
      }
    },
    showSleepTimerModal() {
      this.$emit('showSleepTimer')
    },
    setPlaybackSpeed(speed) {
      console.log(`[AudioPlayer] Set Playback Rate: ${speed}`)
      this.currentPlaybackRate = speed
      AbsAudioPlayer.setPlaybackSpeed({ value: speed })
    },
    restart() {
      this.seek(0)
    },
    backward10() {
      if (this.isLoading) return
      AbsAudioPlayer.seekBackward({ value: 10 })
    },
    forward10() {
      if (this.isLoading) return
      AbsAudioPlayer.seekForward({ value: 10 })
    },
    setStreamReady() {
      this.readyTrackWidth = this.trackWidth
      this.updateReadyTrack()
    },
    setChunksReady(chunks, numSegments) {
      var largestSeg = 0
      for (let i = 0; i < chunks.length; i++) {
        var chunk = chunks[i]
        if (typeof chunk === 'string') {
          var chunkRange = chunk.split('-').map((c) => Number(c))
          if (chunkRange.length < 2) continue
          if (chunkRange[1] > largestSeg) largestSeg = chunkRange[1]
        } else if (chunk > largestSeg) {
          largestSeg = chunk
        }
      }
      var percentageReady = largestSeg / numSegments
      var widthReady = Math.round(this.trackWidth * percentageReady)
      if (this.readyTrackWidth === widthReady) {
        return
      }
      this.readyTrackWidth = widthReady
      this.updateReadyTrack()
    },
    updateReadyTrack() {
      if (this.useChapterTrack) {
        if (this.$refs.totalReadyTrack) {
          this.$refs.totalReadyTrack.style.width = this.readyTrackWidth + 'px'
        }
        this.$refs.readyTrack.style.width = this.trackWidth + 'px'
      } else {
        this.$refs.readyTrack.style.width = this.readyTrackWidth + 'px'
      }
    },
    updateTimestamp() {
      var ts = this.$refs.currentTimestamp
      if (!ts) {
        console.error('No timestamp el')
        return
      }
      var currTimeStr = ''
      if (this.useChapterTrack && this.currentChapter) {
        var currChapTime = Math.max(0, this.currentTime - this.currentChapter.start)
        currTimeStr = this.$secondsToTimestamp(currChapTime)
      } else {
        currTimeStr = this.$secondsToTimestamp(this.currentTime)
      }
      ts.innerText = currTimeStr
    },
    timeupdate() {
      if (!this.$refs.playedTrack) {
        console.error('Invalid no played track ref')
        return
      }
      this.$emit('updateTime', this.currentTime)

      if (this.seekLoading) {
        this.seekLoading = false
        if (this.$refs.playedTrack) {
          this.$refs.playedTrack.classList.remove('bg-yellow-300')
          this.$refs.playedTrack.classList.add('bg-gray-200')
        }
      }

      this.updateTimestamp()
      this.updateTrack()
    },
    updateTrack() {
      // Update progress track UI
      var percentDone = this.currentTime / this.totalDuration
      var totalPercentDone = percentDone
      var bufferedPercent = this.bufferedTime / this.totalDuration
      var totalBufferedPercent = bufferedPercent

      if (this.useChapterTrack && this.currentChapter) {
        var currChapTime = this.currentTime - this.currentChapter.start
        percentDone = currChapTime / this.currentChapterDuration
        bufferedPercent = (this.bufferedTime - this.currentChapter.start) / this.currentChapterDuration
      }
      var ptWidth = Math.round(percentDone * this.trackWidth)
      if (this.playedTrackWidth === ptWidth) {
        return
      }
      this.$refs.playedTrack.style.width = ptWidth + 'px'
      this.$refs.bufferedTrack.style.width = Math.round(bufferedPercent * this.trackWidth) + 'px'
      this.playedTrackWidth = ptWidth

      if (this.useChapterTrack) {
        if (this.$refs.totalPlayedTrack) this.$refs.totalPlayedTrack.style.width = Math.round(totalPercentDone * this.trackWidth) + 'px'
        if (this.$refs.totalBufferedTrack) this.$refs.totalBufferedTrack.style.width = Math.round(totalBufferedPercent * this.trackWidth) + 'px'
      }
    },
    seek(time) {
      if (this.isLoading) return
      if (this.seekLoading) {
        console.error('Already seek loading', this.seekedTime)
        return
      }

      this.seekedTime = time
      this.seekLoading = true

      AbsAudioPlayer.seek({ value: Math.floor(time) })

      if (this.$refs.playedTrack) {
        var perc = time / this.totalDuration
        var ptWidth = Math.round(perc * this.trackWidth)
        this.$refs.playedTrack.style.width = ptWidth + 'px'
        this.playedTrackWidth = ptWidth

        this.$refs.playedTrack.classList.remove('bg-gray-200')
        this.$refs.playedTrack.classList.add('bg-yellow-300')
      }
    },
    clickTrack(e) {
      if (this.isLoading) return
      if (!this.showFullscreen) {
        // Track not clickable on mini-player
        return
      }
      if (e) e.stopPropagation()

      var offsetX = e.offsetX
      var perc = offsetX / this.trackWidth
      var time = 0
      if (this.useChapterTrack && this.currentChapter) {
        time = perc * this.currentChapterDuration + this.currentChapter.start
      } else {
        time = perc * this.totalDuration
      }
      if (isNaN(time) || time === null) {
        console.error('Invalid time', perc, time)
        return
      }
      this.seek(time)
    },
    async playPauseClick() {
      if (this.isLoading) return

      this.isPlaying = !!((await AbsAudioPlayer.playPause()) || {}).playing
      this.isEnded = false
    },
    play() {
      AbsAudioPlayer.playPlayer()
      this.startPlayInterval()
      this.isPlaying = true
    },
    pause() {
      AbsAudioPlayer.pausePlayer()
      this.stopPlayInterval()
      this.isPlaying = false
    },
    startPlayInterval() {
      clearInterval(this.playInterval)
      this.playInterval = setInterval(async () => {
        var data = await AbsAudioPlayer.getCurrentTime()
        this.currentTime = Number(data.value.toFixed(2))
        this.bufferedTime = Number(data.bufferedTime.toFixed(2))
        console.log('[AudioPlayer] Got Current Time', this.currentTime)
        this.timeupdate()
      }, 1000)
    },
    stopPlayInterval() {
      clearInterval(this.playInterval)
    },
    resetStream(startTime) {
      this.closePlayback()
    },
    handleGesture() {
      var touchDistance = this.touchEndY - this.touchStartY
      if (touchDistance > 100) {
        this.collapseFullscreen()
      }
    },
    touchstart(e) {
      if (!this.showFullscreen || !e.changedTouches) return

      this.touchStartY = e.changedTouches[0].screenY
      if (this.touchStartY > window.innerHeight / 3) {
        // console.log('touch too low')
        return
      }
      this.touchStartTime = Date.now()
    },
    touchend(e) {
      if (!this.showFullscreen || !e.changedTouches) return

      this.touchEndY = e.changedTouches[0].screenY
      var touchDuration = Date.now() - this.touchStartTime
      if (touchDuration > 1200) {
        // console.log('touch too long', touchDuration)
        return
      }
      this.handleGesture()
    },
    clickMenuAction(action) {
      if (action === 'chapter_track') {
        this.useChapterTrack = !this.useChapterTrack

        this.$nextTick(() => {
          this.updateTimestamp()
          this.updateTrack()
          this.updateReadyTrack()
        })
        this.$localStore.setUseChapterTrack(this.useChapterTrack)
      } else if (action === 'close') {
        this.closePlayback()
      }
    },
    forceCloseDropdownMenu() {
      if (this.$refs.dropdownMenu && this.$refs.dropdownMenu.closeMenu) {
        this.$refs.dropdownMenu.closeMenu()
      }
    },
    closePlayback() {
      this.$store.commit('setPlayerItem', null)
      this.showFullscreen = false
      this.isEnded = false
      this.isLoading = false
      this.playbackSession = null

      AbsAudioPlayer.closePlayback()
    },
    //
    // Listeners from audio AbsAudioPlayer
    //
    onPlayingUpdate(data) {
      console.log('onPlayingUpdate', JSON.stringify(data))
      this.isPlaying = !!data.value
      this.$store.commit('setPlayerPlaying', this.isPlaying)
      if (this.isPlaying) {
        this.startPlayInterval()
      } else {
        this.stopPlayInterval()
      }
    },
    onMetadata(data) {
      console.log('onMetadata', JSON.stringify(data))
      this.totalDuration = Number(data.duration.toFixed(2))
      this.currentTime = Number(data.currentTime.toFixed(2))

      // Done loading
      if (data.playerState !== 'BUFFERING' && data.playerState !== 'IDLE') {
        this.isLoading = false
      }

      if (data.playerState === 'ENDED') {
        console.log('[AudioPlayer] Playback ended')
      }
      this.isEnded = data.playerState === 'ENDED'

      console.log('received metadata update', data)

      if (data.currentRate && data.currentRate > 0) this.playbackSpeed = data.currentRate

      this.timeupdate()
    },
    // When a playback session is started the native android/ios will send the session
    onPlaybackSession(playbackSession) {
      console.log('onPlaybackSession received', JSON.stringify(playbackSession))
      this.playbackSession = playbackSession

      this.isEnded = false
      this.isLoading = true
      this.$store.commit('setPlayerItem', this.playbackSession)

      // Set track width
      this.$nextTick(() => {
        if (this.$refs.track) {
          this.trackWidth = this.$refs.track.clientWidth
        } else {
          console.error('Track not loaded', this.$refs)
        }
      })
    },
    onPlaybackClosed() {
      console.log('Received onPlaybackClosed evt')
      this.closePlayback()
    },
    onPlaybackFailed(data) {
      console.log('Received onPlaybackFailed evt')
      var errorMessage = data.value || 'Unknown Error'
      this.$toast.error(`Playback Failed: ${errorMessage}`)
      this.closePlayback()
    },
    async init() {
      this.useChapterTrack = await this.$localStore.getUseChapterTrack()

      this.onPlaybackSessionListener = AbsAudioPlayer.addListener('onPlaybackSession', this.onPlaybackSession)
      this.onPlaybackClosedListener = AbsAudioPlayer.addListener('onPlaybackClosed', this.onPlaybackClosed)
      this.onPlaybackFailedListener = AbsAudioPlayer.addListener('onPlaybackFailed', this.onPlaybackFailed)
      this.onPlayingUpdateListener = AbsAudioPlayer.addListener('onPlayingUpdate', this.onPlayingUpdate)
      this.onMetadataListener = AbsAudioPlayer.addListener('onMetadata', this.onMetadata)
    }
  },
  mounted() {
    document.body.addEventListener('touchstart', this.touchstart)
    document.body.addEventListener('touchend', this.touchend)

    this.$nextTick(this.init)
  },
  beforeDestroy() {
    this.forceCloseDropdownMenu()
    document.body.removeEventListener('touchstart', this.touchstart)
    document.body.removeEventListener('touchend', this.touchend)

    if (this.onPlayingUpdateListener) this.onPlayingUpdateListener.remove()
    if (this.onMetadataListener) this.onMetadataListener.remove()
    if (this.onPlaybackSessionListener) this.onPlaybackSessionListener.remove()
    if (this.onPlaybackClosedListener) this.onPlaybackClosedListener.remove()
    if (this.onPlaybackFailedListener) this.onPlaybackFailedListener.remove()
    clearInterval(this.playInterval)
  }
}
</script>

<style>
.bookCoverWrapper {
  box-shadow: 3px -2px 5px #00000066;
}
#streamContainer {
  box-shadow: 0px -8px 8px #11111155;
  height: 100px;
}
.fullscreen #streamContainer {
  height: 200px;
}

#playerTrack {
  transition: all 0.15s cubic-bezier(0.39, 0.575, 0.565, 1);
  transition-property: margin;
  margin-bottom: 4px;
}
.fullscreen #playerTrack {
  margin-bottom: 18px;
}

.cover-wrapper {
  bottom: 44px;
  left: 12px;
  height: 96px;
  width: 60px;
  transition: all 0.25s cubic-bezier(0.39, 0.575, 0.565, 1);
  transition-property: left, bottom, width, height;
  transform-origin: left bottom;
}
.cover-wrapper.square-cover {
  height: 60px;
}

.total-track {
  bottom: 215px;
  left: 0;
  right: 0;
}

.title-author-texts {
  transition: all 0.15s cubic-bezier(0.39, 0.575, 0.565, 1);
  transition-property: left, bottom, width, height;
  transform-origin: left bottom;

  width: 40%;
  bottom: 50px;
  left: 80px;
  text-align: left;
}
.title-author-texts .title-text {
  transition: all 0.15s cubic-bezier(0.39, 0.575, 0.565, 1);
  transition-property: font-size;
  font-size: 0.85rem;
  line-height: 1.5;
}
.title-author-texts .author-text {
  transition: all 0.15s cubic-bezier(0.39, 0.575, 0.565, 1);
  transition-property: font-size;
  font-size: 0.75rem;
  line-height: 1.2;
}

.fullscreen .title-author-texts {
  bottom: 36%;
  width: 80%;
  left: 10%;
  text-align: center;
}
.fullscreen .title-author-texts .title-text {
  font-size: 1.2rem;
}
.fullscreen .title-author-texts .author-text {
  font-size: 1rem;
}

#playerControls {
  transition: all 0.15s cubic-bezier(0.39, 0.575, 0.565, 1);
  transition-property: width, bottom;
  height: 48px;
  width: 140px;
  padding-left: 12px;
  padding-right: 12px;
  bottom: 45px;
}
#playerControls .jump-icon {
  transition: all 0.15s cubic-bezier(0.39, 0.575, 0.565, 1);
  transition-property: font-size;

  margin: 0px 0px;
  font-size: 1.6rem;
}
#playerControls .play-btn {
  transition: all 0.15s cubic-bezier(0.39, 0.575, 0.565, 1);
  transition-property: padding, margin, height, width, min-width, min-height;

  height: 40px;
  width: 40px;
  min-width: 40px;
  min-height: 40px;
  margin: 0px 14px;
  /* padding: 8px; */
}
#playerControls .play-btn .material-icons {
  transition: all 0.15s cubic-bezier(0.39, 0.575, 0.565, 1);
  transition-property: font-size;

  font-size: 1.5rem;
}

.fullscreen .cover-wrapper {
  bottom: 46%;
  left: calc(50% - 100px);
  margin: 0 auto;
  height: 320px;
  width: 200px;
}
.fullscreen .cover-wrapper.square-cover {
  height: 260px;
  width: 260px;
  left: calc(50% - 130px);
}

.fullscreen #playerControls {
  width: 100%;
  bottom: 100px;
}
.fullscreen #playerControls .jump-icon {
  margin: 0px 18px;
  font-size: 2.4rem;
}
.fullscreen #playerControls .next-icon {
  margin: 0px 20px;
  font-size: 2rem;
}
.fullscreen #playerControls .play-btn {
  /* padding: 16px; */
  height: 65px;
  width: 65px;
  min-width: 65px;
  min-height: 65px;
  margin: 0px 26px;
}
.fullscreen #playerControls .play-btn .material-icons {
  font-size: 2.1rem;
}
</style>