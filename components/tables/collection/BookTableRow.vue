<template>
  <div class="w-full px-2 py-2 overflow-hidden relative">
    <nuxt-link v-if="book" :to="`/item/${book.id}`" class="flex items-center w-full">
      <div class="h-full relative" :style="{ width: bookWidth + 'px' }">
        <covers-book-cover :library-item="book" :width="bookWidth" :book-cover-aspect-ratio="bookCoverAspectRatio" />
      </div>
      <div class="book-table-content h-full px-2 flex items-center">
        <div class="max-w-full">
          <p class="truncate block text-sm">{{ bookTitle }}</p>
          <p class="truncate block text-fg-muted text-xs">{{ bookAuthor }}</p>
          <p v-if="media.duration" class="text-xxs text-fg-muted">{{ bookDuration }}</p>
        </div>
      </div>
      <div class="w-8 min-w-8 flex justify-center">
        <button v-if="showPlayBtn" class="w-8 h-8 rounded-full border border-white border-opacity-20 flex items-center justify-center" @click.stop.prevent="playClick">
          <span class="material-icons" :class="streamIsPlaying ? '' : 'text-success'">{{ streamIsPlaying ? 'pause' : 'play_arrow' }}</span>
        </button>
      </div>
    </nuxt-link>
  </div>
</template>

<script>
export default {
  props: {
    collectionId: String,
    book: {
      type: Object,
      default: () => {}
    }
  },
  data() {
    return {
      isProcessingReadUpdate: false,
      processingRemove: false
    }
  },
  computed: {
    libraryItemId() {
      return this.book.id
    },
    media() {
      return this.book.media || {}
    },
    mediaMetadata() {
      return this.media.metadata || {}
    },
    tracks() {
      return this.media.tracks || []
    },
    bookTitle() {
      return this.mediaMetadata.title || ''
    },
    bookAuthor() {
      return this.mediaMetadata.authorName || ''
    },
    bookDuration() {
      return this.$elapsedPretty(this.media.duration)
    },
    bookCoverAspectRatio() {
      return this.$store.getters['libraries/getBookCoverAspectRatio']
    },
    bookWidth() {
      if (this.bookCoverAspectRatio === 1) return 50
      return 50
    },
    isMissing() {
      return this.book.isMissing
    },
    isInvalid() {
      return this.book.isInvalid
    },
    showPlayBtn() {
      return !this.isMissing && !this.isInvalid && this.tracks.length
    },
    isStreaming() {
      return this.$store.getters['getIsMediaStreaming'](this.libraryItemId)
    },
    streamIsPlaying() {
      return this.$store.state.playerIsPlaying && this.isStreaming
    }
  },
  methods: {
    async playClick() {
      await this.$hapticsImpact()
      if (this.streamIsPlaying) {
        this.$eventBus.$emit('pause-item')
      } else {
        this.$eventBus.$emit('play-item', {
          libraryItemId: this.libraryItemId
        })
      }
    }
  },
  mounted() {}
}
</script>

<style>
.book-table-content {
  width: calc(100% - 82px);
  max-width: calc(100% - 82px);
}
</style>