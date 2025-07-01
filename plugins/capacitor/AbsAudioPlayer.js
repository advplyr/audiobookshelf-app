import { registerPlugin, WebPlugin } from '@capacitor/core'
import { AbsLogger } from '@/plugins/capacitor'
import { nanoid } from 'nanoid'
const { PlayerState } = require('../constants')

var $axios = null
var vuexStore = null

class AbsAudioPlayerWeb extends WebPlugin {
  constructor() {
    super()

    this.player = null
    this.playWhenReady = false
    this.playableMimeTypes = {}
    this.playbackSession = null
    this.playbackRate = 1
    this.audioTracks = []
    this.startTime = 0
    this.trackStartTime = 0
  }

  // Use startTime to find current track index
  get currentTrackIndex() {
    return Math.max(
      0,
      this.audioTracks.findIndex((t) => Math.floor(t.startOffset) <= this.startTime && Math.floor(t.startOffset + t.duration) > this.startTime)
    )
  }
  get currentTrack() {
    return this.audioTracks[this.currentTrackIndex]
  }
  get playerCurrentTime() {
    return this.player ? this.player.currentTime : 0
  }
  get currentTrackStartOffset() {
    return this.currentTrack ? this.currentTrack.startOffset : 0
  }
  get overallCurrentTime() {
    return this.currentTrackStartOffset + this.playerCurrentTime
  }
  get totalDuration() {
    var total = 0
    this.audioTracks.forEach((at) => (total += at.duration))
    return total
  }
  get playerPlaying() {
    return this.player && !this.player.paused
  }

  getDeviceId() {
    let deviceId = localStorage.getItem('absDeviceId')
    if (!deviceId) {
      deviceId = nanoid()
      localStorage.setItem('absDeviceId', deviceId)
    }
    return deviceId
  }

  // PluginMethod
  async prepareLibraryItem({ libraryItemId, episodeId, playWhenReady, startTime, playbackRate }) {
    console.log('[AbsAudioPlayer] Prepare library item', libraryItemId)

    if (!isNaN(playbackRate) && playbackRate) this.playbackRate = playbackRate

    if (libraryItemId.startsWith('local_')) {
      // Fetch Local - local not implemented on web
    } else {
      const route = !episodeId ? `/api/items/${libraryItemId}/play` : `/api/items/${libraryItemId}/play/${episodeId}`
      const deviceInfo = {
        deviceId: this.getDeviceId()
      }
      const playbackSession = await $axios.$post(route, { deviceInfo, mediaPlayer: 'html5-mobile', forceDirectPlay: true })
      if (playbackSession) {
        if (startTime !== undefined && startTime !== null) playbackSession.currentTime = startTime
        this.setAudioPlayer(playbackSession, playWhenReady)
      }
    }
    return false
  }

  // PluginMethod
  async playPause() {
    if (!this.player) return
    if (this.player.ended) {
      this.startTime = 0
      this.playWhenReady = true
      this.loadCurrentTrack()
      return
    }

    // For testing onLog events in web while on the logs page
    AbsLogger.info({ tag: 'AbsAudioPlayer', message: 'playPause' })

    if (this.player.paused) this.player.play()
    else this.player.pause()
    return {
      playing: !this.player.paused
    }
  }

  // PluginMethod
  playPlayer() {
    if (this.player) this.player.play()
  }

  // PluginMethod
  pausePlayer() {
    if (this.player) this.player.pause()
  }

  // PluginMethod
  async closePlayback() {
    this.playbackSession = null
    this.audioTracks = []
    this.playWhenReady = false
    if (this.player) {
      this.player.remove()
      this.player = null
    }
    this.notifyListeners('onClosePlayback')
  }

  // PluginMethod
  seek({ value }) {
    this.startTime = value
    this.playWhenReady = this.playerPlaying
    this.loadCurrentTrack()
  }

  // PluginMethod
  seekForward({ value }) {
    this.startTime = Math.min(this.overallCurrentTime + value, this.totalDuration)
    this.playWhenReady = this.playerPlaying
    this.loadCurrentTrack()
  }

  // PluginMethod
  seekBackward({ value }) {
    this.startTime = Math.max(0, this.overallCurrentTime - value)
    this.playWhenReady = this.playerPlaying
    this.loadCurrentTrack()
  }

  // PluginMethod
  setPlaybackSpeed({ value }) {
    this.playbackRate = value
    if (this.player) this.player.playbackRate = value
  }

  // PluginMethod
  setChapterTrack({ enabled }) {
    this.useChapterTrack = enabled
  }

  // PluginMethod
  async getCurrentTime() {
    return {
      value: this.overallCurrentTime,
      bufferedTime: 0
    }
  }

  // PluginMethod
  async getIsCastAvailable() {
    return false
  }

  initializePlayer() {
    if (document.getElementById('audio-player')) {
      document.getElementById('audio-player').remove()
    }
    var audioEl = document.createElement('audio')
    audioEl.id = 'audio-player'
    audioEl.style.display = 'none'
    document.body.appendChild(audioEl)
    this.player = audioEl

    this.player.addEventListener('play', this.evtPlay.bind(this))
    this.player.addEventListener('pause', this.evtPause.bind(this))
    this.player.addEventListener('progress', this.evtProgress.bind(this))
    this.player.addEventListener('ended', this.evtEnded.bind(this))
    this.player.addEventListener('error', this.evtError.bind(this))
    this.player.addEventListener('loadedmetadata', this.evtLoadedMetadata.bind(this))
    this.player.addEventListener('timeupdate', this.evtTimeupdate.bind(this))

    var mimeTypes = ['audio/flac', 'audio/mpeg', 'audio/mp4', 'audio/ogg', 'audio/aac']
    mimeTypes.forEach((mt) => {
      this.playableMimeTypes[mt] = this.player.canPlayType(mt)
    })
    console.log(`[LocalPlayer] Supported mime types`, this.playableMimeTypes)
  }

  evtPlay() {
    this.notifyListeners('onPlayingUpdate', { value: true })
  }
  evtPause() {
    this.notifyListeners('onPlayingUpdate', { value: false })
  }
  evtProgress() {
    // var lastBufferTime = this.getLastBufferedTime()
  }
  evtEnded() {
    if (this.currentTrackIndex < this.audioTracks.length - 1) {
      // Has next track
      console.log(`[AbsAudioPlayer] Track ended - loading next track ${this.currentTrackIndex + 1}`)
      var nextTrack = this.audioTracks[this.currentTrackIndex + 1]
      this.playWhenReady = true
      this.startTime = Math.floor(nextTrack.startOffset)
      this.loadCurrentTrack()
    } else {
      console.log(`[LocalPlayer] Ended`)
      this.sendPlaybackMetadata(PlayerState.ENDED)
    }
  }
  evtError(error) {
    console.error('Player error', error)
  }
  evtLoadedMetadata(data) {
    console.log(`[AbsAudioPlayer] Loaded metadata`, data)
    if (!this.player) {
      console.error('[AbsAudioPlayer] evtLoadedMetadata player not set')
      return
    }
    this.player.currentTime = this.trackStartTime

    this.sendPlaybackMetadata(PlayerState.READY)
    if (this.playWhenReady) {
      this.player.play()
    }
  }
  evtTimeupdate() {}

  sendPlaybackMetadata(playerState) {
    this.notifyListeners('onMetadata', {
      duration: this.totalDuration,
      currentTime: this.overallCurrentTime,
      playerState
    })
  }

  loadCurrentTrack() {
    if (!this.currentTrack) return
    // When direct play track is loaded current time needs to be set
    this.trackStartTime = Math.max(0, this.startTime - (this.currentTrack.startOffset || 0))
    const serverAddressUrl = new URL(vuexStore.getters['user/getServerAddress'])
    const serverHost = `${serverAddressUrl.protocol}//${serverAddressUrl.host}`
    this.player.src = `${serverHost}${this.currentTrack.contentUrl}`
    console.log(`[AbsAudioPlayer] Loading track src ${this.player.src}`)
    this.player.load()
    this.player.playbackRate = this.playbackRate
  }

  setAudioPlayer(playbackSession, playWhenReady = false) {
    if (!this.player) {
      this.initializePlayer()
    }

    // Notify client playback session set
    this.notifyListeners('onPlaybackSession', playbackSession)

    this.playbackSession = playbackSession
    this.playWhenReady = playWhenReady
    this.audioTracks = playbackSession.audioTracks || []
    this.startTime = playbackSession.currentTime

    this.loadCurrentTrack()
  }
}

const AbsAudioPlayer = registerPlugin('AbsAudioPlayer', {
  web: () => new AbsAudioPlayerWeb()
})

export { AbsAudioPlayer }

export default ({ app, store }, inject) => {
  $axios = app.$axios
  vuexStore = store
}
