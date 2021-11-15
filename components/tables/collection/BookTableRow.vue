<template>
  <div class="w-full px-2 py-2 overflow-hidden relative">
    <div v-if="book" class="flex h-20">
      <div class="h-full relative" :style="{ width: '50px' }">
        <cards-book-cover :audiobook="book" :width="50" />
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
  watch: {
    userIsRead: {
      immediate: true,
      handler(newVal) {
        this.isRead = newVal
      }
    }
  },
  computed: {
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
    },
    userAudiobooks() {
      return this.$store.state.user.user ? this.$store.state.user.user.audiobooks || {} : {}
    },
    userAudiobook() {
      return this.userAudiobooks[this.book.id] || null
    },
    userIsRead() {
      return this.userAudiobook ? !!this.userAudiobook.isRead : false
    }
  },
  methods: {
    playClick() {
      // this.$store.commit('setStreamAudiobook', this.book)
      // this.$root.socket.emit('open_stream', this.book.id)
    },
    clickEdit() {
      this.$emit('edit', this.book)
    },
    toggleRead() {
      var updatePayload = {
        isRead: !this.isRead
      }
      this.isProcessingReadUpdate = true
      this.$axios
        .$patch(`/api/user/audiobook/${this.book.id}`, updatePayload)
        .then(() => {
          this.isProcessingReadUpdate = false
          this.$toast.success(`"${this.bookTitle}" Marked as ${updatePayload.isRead ? 'Read' : 'Not Read'}`)
        })
        .catch((error) => {
          console.error('Failed', error)
          this.isProcessingReadUpdate = false
          this.$toast.error(`Failed to mark as ${updatePayload.isRead ? 'Read' : 'Not Read'}`)
        })
    },
    removeClick() {
      this.processingRemove = true

      this.$axios
        .$delete(`/api/collection/${this.collectionId}/book/${this.book.id}`)
        .then((updatedCollection) => {
          console.log(`Book removed from collection`, updatedCollection)
          this.$toast.success('Book removed from collection')
          this.processingRemove = false
        })
        .catch((error) => {
          console.error('Failed to remove book from collection', error)
          this.$toast.error('Failed to remove book from collection')
          this.processingRemove = false
        })
    }
  },
  mounted() {}
}
</script>