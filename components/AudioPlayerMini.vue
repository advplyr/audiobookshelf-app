<template>
  <div ref="wrapper" class="w-full pt-2">
    <div class="relative">
      <div class="flex mt-2 mb-4">
        <div class="flex-grow" />
        <template v-if="!loading">
          <div class="cursor-pointer flex items-center justify-center text-gray-300 mr-8" @mousedown.prevent @mouseup.prevent @click.stop="restart">
            <span class="material-icons text-3xl">first_page</span>
          </div>
          <div class="cursor-pointer flex items-center justify-center text-gray-300" @mousedown.prevent @mouseup.prevent @click.stop="backward10">
            <span class="material-icons text-3xl">replay_10</span>
          </div>
          <div class="cursor-pointer p-2 shadow-sm bg-accent flex items-center justify-center rounded-full text-primary mx-8" :class="seekLoading ? 'animate-spin' : ''" @mousedown.prevent @mouseup.prevent @click.stop="playPauseClick">
            <span class="material-icons">{{ seekLoading ? 'autorenew' : isPaused ? 'play_arrow' : 'pause' }}</span>
          </div>
          <div class="cursor-pointer flex items-center justify-center text-gray-300" @mousedown.prevent @mouseup.prevent @click.stop="forward10">
            <span class="material-icons text-3xl">forward_10</span>
          </div>
          <div class="cursor-pointer flex items-center justify-center text-gray-300 ml-7 w-10 text-center" @mousedown.prevent @mouseup.prevent @click="$emit('selectPlaybackSpeed')">
            <span class="font-mono text-lg">{{ playbackRate }}x</span>
          </div>
        </template>
        <template v-else>
          <div class="cursor-pointer p-2 shadow-sm bg-accent flex items-center justify-center rounded-full text-primary mx-8 animate-spin">
            <span class="material-icons">autorenew</span>
          </div>
        </template>
        <div class="flex-grow" />
      </div>
      <!-- Track -->
      <div ref="track" class="w-full h-2 bg-gray-700 relative cursor-pointer transform duration-100 hover:scale-y-125" :class="loading ? 'animate-pulse' : ''" @click.stop="clickTrack">
        <div ref="readyTrack" class="h-full bg-gray-600 absolute top-0 left-0 pointer-events-none" />
        <div ref="bufferTrack" class="h-full bg-gray-400 absolute top-0 left-0 pointer-events-none" />
        <div ref="playedTrack" class="h-full bg-gray-200 absolute top-0 left-0 pointer-events-none" />
        <div ref="trackCursor" class="h-full w-0.5 bg-gray-50 absolute top-0 left-0 opacity-0 pointer-events-none" />
      </div>
      <div class="flex items-center py-1 px-0.5">
        <div>
          <p ref="currentTimestamp" class="font-mono text-sm">00:00:00</p>
        </div>
        <div class="flex-grow" />
        <div>
          <p class="font-mono text-sm">{{ totalDurationPretty }}</p>
        </div>
      </div>
    </div>
    <!-- <audio ref="audio" @progress="progress" @pause="paused" @playing="playing" @timeupdate="timeupdate" @loadeddata="audioLoadedData" /> -->
  </div>
</template>

<script>
import MyNativeAudio from '@/plugins/my-native-audio'

export default {
  props: {
    loading: Boolean
  },
  data() {
    return {
      totalDuration: 0,
      currentPlaybackRate: 1,
      currentTime: 0,
      isResetting: false,
      initObject: null,
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
      onMetadataListener: null
    }
  },
  computed: {
    totalDurationPretty() {
      return this.$secondsToTimestamp(this.totalDuration)
    },
    playbackRate() {
      return this.$store.getters['user/getUserSetting']('playbackRate')
    }
  },
  methods: {
    updatePlaybackRate() {
      this.currentPlaybackRate = this.playbackRate
      MyNativeAudio.setPlaybackSpeed({ speed: this.playbackRate })
    },
    restart() {
      this.seek(0)
    },
    backward10() {
      MyNativeAudio.seekBackward({ amount: '10000' })
    },
    forward10() {
      MyNativeAudio.seekForward({ amount: '10000' })
    },
    sendStreamUpdate() {
      this.$emit('updateTime', this.currentTime)
    },
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
      this.sendStreamUpdate()

      var perc = this.currentTime / this.totalDuration
      var ptWidth = Math.round(perc * this.trackWidth)
      if (this.playedTrackWidth === ptWidth) {
        return
      }
      this.$refs.playedTrack.style.width = ptWidth + 'px'
      this.playedTrackWidth = ptWidth
    },
    seek(time) {
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
    updateVolume(volume) {},
    clickTrack(e) {
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
      if (time < 3) seekback = 0
      else if (time < 60000) seekback = time / 6
      else if (time < 300000) seekback = 15000
      else if (time < 1800000) seekback = 20000
      else if (time < 3600000) seekback = 25000
      else seekback = 29500
      return seekback
    },
    async set(audiobookStreamData, stream, fromAppDestroy) {
      this.isResetting = false
      this.initObject = { ...audiobookStreamData }
      var init = true
      if (!!stream) {
        console.log(JSON.stringify(stream))
        var data = await MyNativeAudio.getStreamSyncData()
        console.log('getStreamSyncData', JSON.stringify(data))
        console.log('lastUpdate', !!stream.lastUpdate ? stream.lastUpdate : 0)
        //Same audiobook
        if (data.id == stream.id && (data.isPlaying || (data.lastPauseTime >= (!!stream.lastUpdate ? stream.lastUpdate : 0)))) {
          console.log('Same audiobook')
          this.isPaused = !data.isPlaying
          this.currentTime = Number((data.currentTime / 1000).toFixed(2))
          this.timeupdate()
          if (data.isPlaying) {
            console.log('playing - continue')
            if (fromAppDestroy) this.startPlayInterval()
          }
          else console.log('paused and newer')
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
      if (init) MyNativeAudio.initPlayer(this.initObject).then((res) => {
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
      clearInterval(this.playInterval)
      this.playInterval = setInterval(async () => {
        var data = await MyNativeAudio.getCurrentTime()
        this.currentTime = Number((data.value / 1000).toFixed(2))
        this.timeupdate()
      }, 1000)
    },
    stopPlayInterval() {
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
      this.currentTime = Number((data.currentTime / 1000).toFixed(2))
      this.stateName = data.stateName

      if (this.stateName === 'ended' && this.isResetting) {
        this.setFromObj()
      }

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
    }
  },
  mounted() {
    this.$nextTick(this.init)
  },
  beforeDestroy() {
    if (this.onPlayingUpdateListener) this.onPlayingUpdateListener.remove()
    if (this.onMetadataListener) this.onMetadataListener.remove()
    clearInterval(this.playInterval)
  }
}
</script>