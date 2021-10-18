<template>
  <div class="w-full h-full flex">
    <cards-book-card :audiobook="audiobook" :width="cardWidth" class="self-end" />
    <div class="relative px-2" :style="{ width: contentRowWidth + 'px' }">
      <div class="flex">
        <nuxt-link :to="`/audiobook/${audiobook.id}`">
          <p class="leading-6" style="font-size: 1.1rem">{{ audiobook.book.title }}</p>
        </nuxt-link>
        <div class="flex-grow" />
        <div class="flex items-center">
          <!-- <button class="mx-1" @click="editAudiobook(ab)">
                <span class="material-icons text-icon pb-px">edit</span>
              </button> -->
          <button v-if="showRead" class="mx-1 rounded-full w-6 h-6" @click="readBook">
            <span class="material-icons">auto_stories</span>
          </button>
          <button v-if="showPlay" class="mx-1 rounded-full w-6 h-6" @click="playAudiobook">
            <span class="material-icons">play_arrow</span>
          </button>
        </div>
      </div>
      <p v-if="audiobook.book.subtitle" class="text-gray-200 leading-6 truncate" style="font-size: 0.9rem">{{ audiobook.book.subtitle }}</p>
      <p class="text-sm text-gray-200">by {{ audiobook.book.author }}</p>
      <div v-if="numTracks" class="flex items-center py-1">
        <p class="text-xs text-gray-300">{{ $elapsedPretty(audiobook.duration) }}</p>
        <span class="px-3 text-xs text-gray-300">•</span>
        <p class="text-xs text-gray-300 font-mono">{{ $bytesPretty(audiobook.size, 0) }}</p>
        <span class="px-3 text-xs text-gray-300">•</span>
        <p class="text-xs text-gray-300">{{ numTracks }} tracks</p>
      </div>
      <div class="flex">
        <div v-if="userProgressPercent && !userIsRead" class="w-min my-1">
          <div class="bg-primary bg-opacity-70 text-sm px-2 py-px rounded-full whitespace-nowrap">Progress: {{ Math.floor(userProgressPercent * 100) }}%</div>
        </div>
        <div v-if="isDownloadPlayable" class="w-min my-1 mx-1">
          <div class="bg-success bg-opacity-70 text-sm px-2 py-px rounded-full whitespace-nowrap">Downloaded</div>
        </div>
        <div v-else-if="isDownloading" class="w-min my-1 mx-1">
          <div class="bg-warning bg-opacity-70 text-sm px-2 py-px rounded-full whitespace-nowrap">Downloading...</div>
        </div>
        <div v-if="isPlaying" class="w-min my-1 mx-1">
          <div class="bg-info bg-opacity-70 text-sm px-2 py-px rounded-full whitespace-nowrap">{{ isStreaming ? 'Streaming' : 'Playing' }}</div>
        </div>
        <div v-if="hasEbook" class="w-min my-1 mx-1">
          <div class="bg-bg bg-opacity-70 text-sm px-2 py-px rounded-full whitespace-nowrap">{{ ebookFormat }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  props: {
    audiobook: {
      type: Object,
      default: () => {}
    },
    cardWidth: {
      type: Number,
      default: 75
    },
    pageWidth: Number
  },
  data() {
    return {}
  },
  computed: {
    audiobookId() {
      return this.audiobook.id
    },
    mostRecentUserProgress() {
      return this.$store.getters['user/getMostRecentAudiobookProgress'](this.audiobookId)
    },
    userProgressPercent() {
      return this.mostRecentUserProgress ? this.mostRecentUserProgress.progress || 0 : 0
    },
    userIsRead() {
      return this.mostRecentUserProgress ? !!this.mostRecentUserProgress.isRead : false
    },
    contentRowWidth() {
      return this.pageWidth - 16 - this.cardWidth
    },
    isDownloading() {
      return this.downloadObj ? this.downloadObj.isDownloading : false
    },
    isDownloadPreparing() {
      return this.downloadObj ? this.downloadObj.isPreparing : false
    },
    isDownloadPlayable() {
      return this.downloadObj && !this.isDownloading && !this.isDownloadPreparing
    },
    downloadedCover() {
      return this.downloadObj ? this.downloadObj.cover : null
    },
    downloadObj() {
      return this.$store.getters['downloads/getDownload'](this.audiobookId)
    },
    isStreaming() {
      return this.$store.getters['isAudiobookStreaming'](this.audiobookId)
    },
    isPlaying() {
      return this.$store.getters['isAudiobookPlaying'](this.audiobookId)
    },
    isMissing() {
      return this.audiobook.isMissing
    },
    isIncomplete() {
      return this.audiobook.isIncomplete
    },
    numTracks() {
      if (this.audiobook.tracks) return this.audiobook.tracks.length
      return this.audiobook.numTracks || 0
    },
    showPlay() {
      return !this.isPlaying && !this.isMissing && !this.isIncomplete && this.numTracks
    },
    showRead() {
      return this.hasEbook && this.ebookFormat !== '.pdf'
    },
    hasEbook() {
      return this.audiobook.numEbooks
    },
    ebookFormat() {
      if (!this.audiobook || !this.audiobook.ebooks || !this.audiobook.ebooks.length) return null
      return this.audiobook.ebooks[0].ext.substr(1)
    }
  },
  methods: {
    readBook() {
      this.$store.commit('openReader', this.audiobook)
    },
    playAudiobook() {
      if (this.isPlaying) {
        return
      }
      this.$store.commit('setPlayOnLoad', true)
      if (!this.isDownloadPlayable) {
        // Stream
        console.log('[PLAYCLICK] Set Playing STREAM ' + this.audiobook.book.title)
        this.$store.commit('setStreamAudiobook', this.audiobook)
        this.$server.socket.emit('open_stream', this.audiobook.id)
      } else {
        // Local
        console.log('[PLAYCLICK] Set Playing Local Download ' + this.audiobook.book.title)
        this.$store.commit('setPlayingDownload', this.downloadObj)
      }
    }
  },
  mounted() {}
}
</script>