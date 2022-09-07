<template>
  <div class="w-full px-2 py-2 overflow-hidden relative">
    <nuxt-link v-if="book" :to="`/item/${book.id}`" class="flex w-full">
      <div class="h-full relative" :style="{ width: bookWidth + 'px' }">
        <covers-book-cover :library-item="book" :width="bookWidth" :book-cover-aspect-ratio="bookCoverAspectRatio" />
      </div>
      <div class="flex-grow book-table-content h-full px-2 flex items-center">
        <div class="max-w-full">
          <p class="truncate block text-sm">{{ bookTitle }}</p>
          <p class="truncate block text-gray-400 text-xs">{{ bookAuthor }}</p>
          <p class="text-xxs text-gray-500">{{ bookDuration }}</p>
        </div>
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
      return this.$store.getters['getBookCoverAspectRatio']
    },
    bookWidth() {
      if (this.bookCoverAspectRatio === 1) return 50
      return 50
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
      return this.$store.getters['getIsItemStreaming'](this.book.id)
    },
    showPlayBtn() {
      return !this.isMissing && !this.isIncomplete && !this.isStreaming && this.numTracks
    }
  },
  methods: {
    clickEdit() {
      this.$emit('edit', this.book)
    }
  },
  mounted() {}
}
</script>

<style>
.book-table-content {
  width: calc(100% - 50px);
  max-width: calc(100% - 50px);
}
</style>