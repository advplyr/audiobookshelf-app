<template>
  <div class="fixed top-0 bottom-0 left-0 right-0 z-50 pointer-events-none" :class="showFullscreen ? 'fullscreen' : ''">
    <div v-if="showFullscreen" class="w-full h-full z-10 bg-bg absolute top-0 left-0 pointer-events-auto">
      <div class="top-2 left-4 absolute cursor-pointer">
        <span class="material-icons text-5xl" @click="collapseFullscreen">expand_more</span>
      </div>
      <div class="top-3 right-4 absolute cursor-pointer">
        <span class="material-icons text-4xl" @click="$emit('close')">close</span>
      </div>
    </div>

    <div class="cover-wrapper absolute z-30 pointer-events-auto" @click="clickContainer">
      <div class="cover-container bookCoverWrapper bg-black bg-opacity-75 w-full h-full">
        <cards-player-book-cover :audiobook="audiobook" :download-cover="downloadedCover" :width="showFullscreen ? 200 : 60" />
      </div>
    </div>

    <div class="title-author-texts absolute z-30 left-0 right-0 overflow-hidden">
      <p class="title-text font-book truncate">{{ title }}</p>
      <p class="author-text text-white text-opacity-75 truncate">by {{ authorFL }}</p>
    </div>

    <div id="streamContainer" class="w-full z-20 bg-primary absolute bottom-0 left-0 right-0 p-2 pointer-events-auto transition-all" @click="clickContainer">
      <div v-if="showFullscreen" class="absolute top-0 left-0 right-0 w-full py-3 mx-auto px-3" style="max-width: 380px">
        <div class="flex items-center justify-between pointer-events-auto">
          <span class="material-icons text-3xl text-white text-opacity-75 cursor-pointer" @click="$emit('showBookmarks')">{{ bookmarks.length ? 'bookmark' : 'bookmark_border' }}</span>
          <span class="font-mono text-white text-opacity-75 cursor-pointer" style="font-size: 1.35rem" @click="$emit('selectPlaybackSpeed')">{{ currentPlaybackRate }}x</span>
          <svg v-if="!sleepTimerRunning" xmlns="http://www.w3.org/2000/svg" class="h-7 w-7 text-white text-opacity-75 cursor-pointer" fill="none" viewBox="0 0 24 24" stroke="currentColor" @click.stop="$emit('showSleepTimer')">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M20.354 15.354A9 9 0 018.646 3.646 9.003 9.003 0 0012 21a9.003 9.003 0 008.354-5.646z" />
          </svg>
          <div v-else class="h-7 w-7 flex items-center justify-around cursor-pointer" @click.stop="$emit('showSleepTimer')">
            <p v-if="sleepTimerEndOfChapterTime" class="text-lg font-mono text-warning">-{{ $secondsToTimestamp(timeLeftInChapter) }}</p>
            <p v-else class="text-xl font-mono text-success">{{ Math.ceil(sleepTimeoutCurrentTime / 1000 / 60) }}m</p>
          </div>

          <span class="material-icons text-3xl text-white cursor-pointer" :class="chapters.length ? 'text-opacity-75' : 'text-opacity-10'" @click="$emit('selectChapter')">format_list_bulleted</span>
        </div>
      </div>

      <div id="playerControls" class="absolute right-0 bottom-0 py-2">
        <div class="flex items-center justify-center">
          <span v-show="showFullscreen" class="material-icons next-icon text-white text-opacity-75 cursor-pointer" :class="loading ? 'text-opacity-10' : 'text-opacity-75'" @click.stop="jumpChapterStart">first_page</span>
          <span class="material-icons jump-icon text-white cursor-pointer" :class="loading ? 'text-opacity-10' : 'text-opacity-75'" @click.stop="backward10">replay_10</span>
          <div class="play-btn cursor-pointer shadow-sm bg-accent flex items-center justify-center rounded-full text-primary mx-4" :class="seekLoading ? 'animate-spin' : ''" @mousedown.prevent @mouseup.prevent @click.stop="playPauseClick">
            <span v-if="!loading" class="material-icons">{{ seekLoading ? 'autorenew' : isPaused ? 'play_arrow' : 'pause' }}</span>
            <widgets-spinner-icon v-else class="h-8 w-8" />
          </div>
          <span class="material-icons jump-icon text-white cursor-pointer" :class="loading ? 'text-opacity-10' : 'text-opacity-75'" @click.stop="forward10">forward_10</span>
          <span v-show="showFullscreen" class="material-icons next-icon text-white cursor-pointer" :class="nextChapter && !loading ? 'text-opacity-75' : 'text-opacity-10'" @click.stop="jumpNextChapter">last_page</span>
        </div>
      </div>

      <div id="playerTrack" class="absolute bottom-0 left-0 w-full px-3">
        <div ref="track" class="h-2 w-full bg-gray-500 bg-opacity-50 relative" :class="loading ? 'animate-pulse' : ''" @click.stop="clickTrack">
          <div ref="readyTrack" class="h-full bg-gray-600 absolute top-0 left-0 pointer-events-none" />
          <div ref="bufferTrack" class="h-full bg-gray-400 absolute top-0 left-0 pointer-events-none" />
          <div ref="playedTrack" class="h-full bg-gray-200 absolute top-0 left-0 pointer-events-none" />
        </div>
        <div class="flex pt-0.5">
          <p class="font-mono text-sm" ref="currentTimestamp">0:00</p>
          <div class="flex-grow" />
          <p v-show="showFullscreen" class="text-sm truncate text-white text-opacity-75" style="max-width: 65%">{{ currentChapterTitle }}</p>
          <div class="flex-grow" />
          <p class="font-mono text-sm">{{ totalDurationPretty }}</p>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import MyNativeAudio from '@/plugins/my-native-audio'

export default {
  props: {
    audiobook: {
      type: Object,
      default: () => {}
    },
    download: {
      type: Object,
      default: () => {}
    },
    bookmarks: {
      type: Array,
      default: () => []
    },
    loading: Boolean,
    sleepTimerRunning: Boolean,
    sleepTimeoutCurrentTime: Number,
    sleepTimerEndOfChapterTime: Number
  },
  data() {
    return {
      showFullscreen: false,
      totalDuration: 0,
      currentPlaybackRate: 1,
      currentTime: 0,
      isResetting: false,
      initObject: null,
      streamId: null,
      audiobookId: null,
      stateName: 'idle',
      playInterval: null,
      trackWidth: 0,
      isPaused: true,
      src: null,
      volume: 0.5,
      readyTrackWidth: 0,
      bufferTrackWidth: 0,
      playedTrackWidth: 0,
      seekedTime: 0,
      seekLoading: false,
      onPlayingUpdateListener: null,
      onMetadataListener: null,
      // noSyncUpdateTime: false,
      touchStartY: 0,
      touchStartTime: 0,
      touchEndY: 0,
      listenTimeInterval: null,
      listeningTimeSinceLastUpdate: 0,
      totalListeningTimeInSession: 0
    }
  },
  computed: {
    book() {
      return this.audiobook.book || {}
    },
    title() {
      return this.book.title
    },
    authorFL() {
      return this.book.authorFL
    },
    chapters() {
      return this.audiobook ? this.audiobook.chapters || [] : []
    },
    currentChapter() {
      if (!this.audiobook || !this.chapters.length) return null
      return this.chapters.find((ch) => ch.start <= this.currentTime && ch.end > this.currentTime)
    },
    nextChapter() {
      if (!this.chapters.length) return
      return this.chapters.find((c) => c.start >= this.currentTime)
    },
    currentChapterTitle() {
      return this.currentChapter ? this.currentChapter.title : ''
    },
    downloadedCover() {
      return this.download ? this.download.cover : null
    },
    totalDurationPretty() {
      return this.$secondsToTimestamp(this.totalDuration)
    },
    timeLeftInChapter() {
      if (!this.currentChapter) return 0
      return this.currentChapter.end - this.currentTime
    }
  },
  methods: {
    sendStreamSync(timeListened = 0) {
      var syncData = {
        timeListened,
        currentTime: this.currentTime,
        streamId: this.streamId,
        audiobookId: this.audiobookId,
        totalDuration: this.totalDuration
      }
      this.$emit('sync', syncData)
    },
    sendAddListeningTime() {
      var listeningTimeToAdd = Math.floor(this.listeningTimeSinceLastUpdate)
      this.listeningTimeSinceLastUpdate = Math.max(0, this.listeningTimeSinceLastUpdate - listeningTimeToAdd)
      this.sendStreamSync(listeningTimeToAdd)
    },
    cancelListenTimeInterval() {
      this.sendAddListeningTime()
      clearInterval(this.listenTimeInterval)
      this.listenTimeInterval = null
    },
    startListenTimeInterval() {
      clearInterval(this.listenTimeInterval)
      var lastTime = this.currentTime
      var lastTick = Date.now()
      var noProgressCount = 0
      this.listenTimeInterval = setInterval(() => {
        var timeSinceLastTick = Date.now() - lastTick
        lastTick = Date.now()

        var expectedAudioTime = lastTime + timeSinceLastTick / 1000
        var currentTime = this.currentTime
        var differenceFromExpected = expectedAudioTime - currentTime
        if (currentTime === lastTime) {
          noProgressCount++
          if (noProgressCount > 3) {
            console.error('Audio current time has not increased - cancel interval and pause player')
            this.pause()
          }
        } else if (Math.abs(differenceFromExpected) > 0.1) {
          noProgressCount = 0
          console.warn('Invalid time between interval - resync last', differenceFromExpected)
          lastTime = currentTime
        } else {
          noProgressCount = 0
          var exactPlayTimeDifference = currentTime - lastTime
          // console.log('Difference from expected', differenceFromExpected, 'Exact play time diff', exactPlayTimeDifference)
          lastTime = currentTime
          this.listeningTimeSinceLastUpdate += exactPlayTimeDifference
          this.totalListeningTimeInSession += exactPlayTimeDifference
          // console.log('Time since last update:', this.listeningTimeSinceLastUpdate, 'Session listening time:', this.totalListeningTimeInSession)
          if (this.listeningTimeSinceLastUpdate > 5) {
            this.sendAddListeningTime()
          }
        }
      }, 1000)
    },
    clickContainer() {
      this.showFullscreen = true
    },
    collapseFullscreen() {
      this.showFullscreen = false
    },
    jumpNextChapter() {
      if (this.loading) return
      if (!this.nextChapter) return
      this.seek(this.nextChapter.start)
    },
    jumpChapterStart() {
      if (this.loading) return
      if (!this.currentChapter) {
        return this.restart()
      }

      // If 1 second or less into current chapter, then go to previous
      if (this.currentTime - this.currentChapter.start <= 1) {
        var currChapterIndex = this.chapters.findIndex((ch) => ch.start <= this.currentTime && ch.end >= this.currentTime)
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
      MyNativeAudio.setPlaybackSpeed({ speed: speed })
    },
    restart() {
      this.seek(0)
    },
    backward10() {
      if (this.loading) return
      MyNativeAudio.seekBackward({ amount: '10000' })
    },
    forward10() {
      if (this.loading) return
      MyNativeAudio.seekForward({ amount: '10000' })
    },
    // sendStreamUpdate() {
    //   this.$emit('updateTime', this.currentTime)
    // },
    setStreamReady() {
      this.readyTrackWidth = this.trackWidth
      this.$refs.readyTrack.style.width = this.trackWidth + 'px'
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
      this.$refs.readyTrack.style.width = widthReady + 'px'
    },
    updateTimestamp() {
      var ts = this.$refs.currentTimestamp
      if (!ts) {
        console.error('No timestamp el')
        return
      }
      var currTimeClean = this.$secondsToTimestamp(this.currentTime)
      ts.innerText = currTimeClean
    },
    timeupdate() {
      if (!this.$refs.playedTrack) {
        console.error('Invalid no played track ref')
        return
      }

      if (this.seekLoading) {
        this.seekLoading = false
        if (this.$refs.playedTrack) {
          this.$refs.playedTrack.classList.remove('bg-yellow-300')
          this.$refs.playedTrack.classList.add('bg-gray-200')
        }
      }

      this.updateTimestamp()
      // if (this.noSyncUpdateTime) this.noSyncUpdateTime = false
      // else this.sendStreamUpdate()

      var perc = this.currentTime / this.totalDuration
      var ptWidth = Math.round(perc * this.trackWidth)
      if (this.playedTrackWidth === ptWidth) {
        return
      }
      this.$refs.playedTrack.style.width = ptWidth + 'px'
      this.playedTrackWidth = ptWidth
    },
    seek(time) {
      if (this.loading) return
      if (this.seekLoading) {
        console.error('Already seek loading', this.seekedTime)
        return
      }

      this.seekedTime = time
      this.seekLoading = true
      MyNativeAudio.seekPlayer({ timeMs: String(Math.floor(time * 1000)) })

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
      if (this.loading) return
      var offsetX = e.offsetX
      var perc = offsetX / this.trackWidth
      var time = perc * this.totalDuration
      if (isNaN(time) || time === null) {
        console.error('Invalid time', perc, time)
        return
      }
      this.seek(time)
    },
    playPauseClick() {
      if (this.loading) return
      if (this.isPaused) {
        console.log('playPause PLAY')
        this.play()
      } else {
        console.log('playPause PAUSE')
        this.pause()
      }
    },
    calcSeekBackTime(lastUpdate) {
      var time = Date.now() - lastUpdate
      var seekback = 0
      if (time < 3000) seekback = 0
      else if (time < 60000) seekback = time / 6
      else if (time < 300000) seekback = 15000
      else if (time < 1800000) seekback = 20000
      else if (time < 3600000) seekback = 25000
      else seekback = 29500
      return seekback
    },
    async set(audiobookStreamData, stream, fromAppDestroy) {
      this.isResetting = false
      this.streamId = stream ? stream.id : null
      this.audiobookId = audiobookStreamData.audiobookId
      this.initObject = { ...audiobookStreamData }

      var init = true
      if (!!stream) {
        //console.log(JSON.stringify(stream))
        var data = await MyNativeAudio.getStreamSyncData()
        console.log('getStreamSyncData', JSON.stringify(data))
        console.log('lastUpdate', stream.lastUpdate || 0)
        //Same audiobook
        if (data.id == stream.id && (data.isPlaying || data.lastPauseTime >= (stream.lastUpdate || 0))) {
          console.log('Same audiobook')
          this.isPaused = !data.isPlaying
          this.currentTime = Number((data.currentTime / 1000).toFixed(2))
          this.totalDuration = Number((data.duration / 1000).toFixed(2))
          this.$emit('setTotalDuration', this.totalDuration)
          this.timeupdate()
          if (data.isPlaying) {
            console.log('playing - continue')
            if (fromAppDestroy) this.startPlayInterval()
          } else console.log('paused and newer')
          if (!fromAppDestroy) return
          init = false
          this.initObject.startTime = String(Math.floor(this.currentTime * 1000))
        }
        //new audiobook stream or sync from other client
        else if (stream.clientCurrentTime > 0) {
          console.log('new audiobook stream or sync from other client')
          if (!!stream.lastUpdate) {
            var backTime = this.calcSeekBackTime(stream.lastUpdate)
            var currentTime = Math.floor(stream.clientCurrentTime * 1000)
            if (backTime >= currentTime) backTime = currentTime - 500
            console.log('SeekBackTime', backTime)
            this.initObject.startTime = String(Math.floor(currentTime - backTime))
          }
        }
      }

      this.currentPlaybackRate = this.initObject.playbackSpeed
      console.log(`[AudioPlayer] Set Stream Playback Rate: ${this.currentPlaybackRate}`)

      if (init)
        MyNativeAudio.initPlayer(this.initObject).then((res) => {
          if (res && res.success) {
            console.log('Success init audio player')
          } else {
            console.error('Failed to init audio player')
          }
        })

      if (audiobookStreamData.isLocal) {
        this.setStreamReady()
      }
    },
    setFromObj() {
      if (!this.initObject) {
        console.error('Cannot set from obj')
        return
      }
      this.isResetting = false
      MyNativeAudio.initPlayer(this.initObject).then((res) => {
        if (res && res.success) {
          console.log('Success init audio player')
        } else {
          console.error('Failed to init audio player')
        }
      })

      if (audiobookStreamData.isLocal) {
        this.setStreamReady()
      }
    },
    play() {
      MyNativeAudio.playPlayer()
      this.startPlayInterval()
    },
    pause() {
      MyNativeAudio.pausePlayer()
      this.stopPlayInterval()
    },
    startPlayInterval() {
      this.startListenTimeInterval()

      clearInterval(this.playInterval)
      this.playInterval = setInterval(async () => {
        var data = await MyNativeAudio.getCurrentTime()
        this.currentTime = Number((data.value / 1000).toFixed(2))
        this.timeupdate()
      }, 1000)
    },
    stopPlayInterval() {
      this.cancelListenTimeInterval()
      clearInterval(this.playInterval)
    },
    resetStream(startTime) {
      var _time = String(Math.floor(startTime * 1000))
      if (!this.initObject) {
        console.error('Terminate stream when no init object is set...')
        return
      }
      this.isResetting = true
      this.initObject.currentTime = _time
      this.terminateStream()
    },
    terminateStream() {
      MyNativeAudio.terminateStream()
    },
    onPlayingUpdate(data) {
      this.isPaused = !data.value
      if (!this.isPaused) {
        this.startPlayInterval()
      } else {
        this.stopPlayInterval()
      }
    },
    onMetadata(data) {
      console.log('Native Audio On Metadata', JSON.stringify(data))
      this.totalDuration = Number((data.duration / 1000).toFixed(2))
      this.$emit('setTotalDuration', this.totalDuration)
      this.currentTime = Number((data.currentTime / 1000).toFixed(2))
      this.stateName = data.stateName

      if (this.stateName === 'ended' && this.isResetting) {
        this.setFromObj()
      }

      // if (this.stateName === 'ready_no_sync' || this.stateName === 'buffering_no_sync') this.noSyncUpdateTime = true

      this.timeupdate()
    },
    init() {
      this.onPlayingUpdateListener = MyNativeAudio.addListener('onPlayingUpdate', this.onPlayingUpdate)
      this.onMetadataListener = MyNativeAudio.addListener('onMetadata', this.onMetadata)

      if (this.$refs.track) {
        this.trackWidth = this.$refs.track.clientWidth
      } else {
        console.error('Track not loaded', this.$refs)
      }
    },
    handleGesture() {
      var touchDistance = this.touchEndY - this.touchStartY
      if (touchDistance > 100) {
        console.log('Collapsing')
        this.collapseFullscreen()
      } else {
        console.log('Not collapsing touch distance =', touchDistance)
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
    }
  },
  mounted() {
    document.body.addEventListener('touchstart', this.touchstart)
    document.body.addEventListener('touchend', this.touchend)

    this.$nextTick(this.init)
  },
  beforeDestroy() {
    document.body.removeEventListener('touchstart', this.touchstart)
    document.body.removeEventListener('touchend', this.touchend)

    if (this.onPlayingUpdateListener) this.onPlayingUpdateListener.remove()
    if (this.onMetadataListener) this.onMetadataListener.remove()
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