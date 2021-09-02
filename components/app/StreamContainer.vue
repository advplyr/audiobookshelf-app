<template>
  <div class="w-full p-4 pointer-events-none fixed bottom-0 left-0 right-0 z-20">
    <div v-if="streamAudiobook" class="w-full bg-primary absolute bottom-0 left-0 right-0 z-50 p-2 pointer-events-auto" @click.stop @mousedown.stop @mouseup.stop>
      <div class="pl-16 pr-2 flex items-center pb-2">
        <div>
          <p class="px-2">{{ title }}</p>
          <p class="px-2 text-xs text-gray-400">by {{ author }}</p>
        </div>
        <div class="flex-grow" />
        <span class="material-icons" @click="cancelStream">close</span>
      </div>
      <div class="absolute left-2 -top-10">
        <cards-book-cover :audiobook="streamAudiobook" :width="64" />
      </div>
      <audio-player-mini ref="audioPlayerMini" :loading="!stream || currStreamAudiobookId !== streamAudiobookId" @updateTime="updateTime" @hook:mounted="audioPlayerMounted" />
    </div>
  </div>
</template>

<script>
import { Dialog } from '@capacitor/dialog'

export default {
  data() {
    return {
      audioPlayerReady: false,
      stream: null,
      lastServerUpdateSentSeconds: 0
    }
  },
  computed: {
    streamAudiobook() {
      return this.$store.state.streamAudiobook
    },
    streamAudiobookId() {
      return this.streamAudiobook ? this.streamAudiobook.id : null
    },
    currStreamAudiobookId() {
      return this.stream ? this.stream.audiobook.id : null
    },
    book() {
      return this.streamAudiobook ? this.streamAudiobook.book || {} : {}
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
    volumeNumber() {
      return this.book ? this.book.volumeNumber : ''
    },
    seriesTxt() {
      if (!this.series) return ''
      if (!this.volumeNumber) return this.series
      return `${this.series} #${this.volumeNumber}`
    },
    duration() {
      return this.streamAudiobook ? this.streamAudiobook.duration || 0 : 0
    },
    coverForNative() {
      if (!this.cover) {
        return `${this.$store.state.serverUrl}/Logo.png`
      }
      if (this.cover.startsWith('http')) return this.cover
      var _clean = this.cover.replace(/\\/g, '/')
      if (_clean.startsWith('/local')) {
        var _cover = process.env.NODE_ENV !== 'production' && process.env.PROD !== '1' ? _clean.replace('/local', '') : _clean
        return `${this.$store.state.serverUrl}${_cover}`
      }
      return _clean
    }
  },
  methods: {
    async cancelStream() {
      const { value } = await Dialog.confirm({
        title: 'Confirm',
        message: 'Cancel this stream?'
      })
      if (value) {
        this.$server.socket.emit('close_stream')
      }
    },
    updateTime(currentTime) {
      var diff = currentTime - this.lastServerUpdateSentSeconds
      if (diff > 4 || diff < 0) {
        this.lastServerUpdateSentSeconds = currentTime
        var updatePayload = {
          currentTime,
          streamId: this.stream.id
        }
        this.$server.socket.emit('stream_update', updatePayload)
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
          this.$refs.audioPlayerMini.terminateStream(startTime)
        }
      }
    },
    streamOpen(stream) {
      console.log('[StreamContainer] Stream Open', stream)
      if (!this.$refs.audioPlayerMini) {
        console.error('No Audio Player Mini')
        return
      }

      if (this.stream && this.stream.id !== stream.id) {
        console.error('STREAM CHANGED', this.stream.id, stream.id)
      }

      this.stream = stream

      var playlistUrl = stream.clientPlaylistUri
      var currentTime = stream.clientCurrentTime || 0
      var playOnLoad = this.$store.state.playOnLoad
      if (playOnLoad) this.$store.commit('setPlayOnLoad', false)

      var audiobookStreamData = {
        title: this.title,
        author: this.author,
        playWhenReady: !!playOnLoad,
        startTime: String(Math.floor(currentTime * 1000)),
        cover: this.coverForNative,
        duration: String(Math.floor(this.duration * 1000)),
        series: this.seriesTxt,
        playlistUrl: this.$server.url + playlistUrl,
        token: this.$store.getters['user/getToken']
      }

      console.log('audiobook stream data', audiobookStreamData.token, JSON.stringify(audiobookStreamData))

      this.$refs.audioPlayerMini.set(audiobookStreamData)
    },
    audioPlayerMounted() {
      console.log('Audio Player Mounted', this.$server.stream)
      this.audioPlayerReady = true
      if (this.$server.stream) {
        this.streamOpen(this.$server.stream)
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
    console.warn('Stream Container Mounted')
    this.setListeners()
  }
}
</script>