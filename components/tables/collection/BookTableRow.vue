<template>
  <div class="w-full px-2 py-2 overflow-hidden relative">
    <div v-if="book" class="flex h-20">
      <div class="h-full relative" :style="{ width: bookWidth + 'px' }">
        <covers-book-cover :audiobook="book" :width="bookWidth" :book-cover-aspect-ratio="bookCoverAspectRatio" />
      </div>
      <div class="w-80 h-full px-2 flex items-center">
        <div>
          <nuxt-link :to="`/audiobook/${book.id}`" class="truncate hover:underline">{{ bookTitle }}</nuxt-link>
          <nuxt-link :to="`/bookshelf/library?filter=authors.${$encode(bookAuthor)}`" class="truncate block text-gray-400 text-sm hover:underline">{{ bookAuthor }}</nuxt-link>
        </div>
      </div>
    </div>
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
    bookCoverAspectRatio() {
      return this.$store.getters['getBookCoverAspectRatio']
    },
    bookWidth() {
      if (this.bookCoverAspectRatio === 1) return 80
      return 50
    },
    _book() {
      return this.book.book || {}
    },
    bookTitle() {
      return this._book.title || ''
    },
    bookAuthor() {
      return this._book.authorFL || ''
    },
    bookDuration() {
      return this.$secondsToTimestamp(this.book.duration)
    },
    isMissing() {
      return this.book.isMissing
    },
    isIncomplete() {
      return this.book.isIncomplete
    },
    numTracks() {
      return this.book.numTracks
    },
    isStreaming() {
      return this.$store.getters['getAudiobookIdStreaming'] === this.book.id
    },
    showPlayBtn() {
      return !this.isMissing && !this.isIncomplete && !this.isStreaming && this.numTracks
    }
  },
  methods: {
    playClick() {
      // this.$store.commit('setStreamAudiobook', this.book)
      // this.$root.socket.emit('open_stream', this.book.id)
    },
    clickEdit() {
      this.$emit('edit', this.book)
    }
  },
  mounted() {}
}
</script>