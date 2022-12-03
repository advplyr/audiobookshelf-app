<template>
  <div class="w-full px-2 py-2 overflow-hidden relative">
    <nuxt-link v-if="libraryItem" :to="`/item/${libraryItem.id}`" class="flex w-full">
      <div class="h-full relative" :style="{ width: '50px' }">
        <covers-book-cover :library-item="libraryItem" :width="50" :book-cover-aspect-ratio="bookCoverAspectRatio" />
      </div>
      <div class="flex-grow item-table-content h-full px-2 flex items-center">
        <div class="max-w-full">
          <p class="truncate block text-sm">{{ itemTitle }}</p>
          <p class="truncate block text-gray-400 text-xs">{{ bookAuthorName }}</p>
          <p class="text-xxs text-gray-500">{{ itemDuration }}</p>
        </div>
      </div>
    </nuxt-link>
  </div>
</template>

<script>
export default {
  props: {
    playlistId: String,
    item: {
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
    libraryItem() {
      return this.item.libraryItem || {}
    },
    episode() {
      return this.item.episode
    },
    episodeId() {
      return this.episode ? this.episode.id : null
    },
    media() {
      return this.libraryItem.media || {}
    },
    mediaMetadata() {
      return this.media.metadata || {}
    },
    tracks() {
      if (this.episode) return []
      return this.media.tracks || []
    },
    itemTitle() {
      if (this.episode) return this.episode.title
      return this.mediaMetadata.title || ''
    },
    bookAuthors() {
      if (this.episode) return []
      return this.mediaMetadata.authors || []
    },
    bookAuthorName() {
      return this.bookAuthors.map((au) => au.name).join(', ')
    },
    itemDuration() {
      if (this.episode) return this.$elapsedPretty(this.episode.duration)
      return this.$elapsedPretty(this.media.duration)
    },
    isMissing() {
      return this.libraryItem.isMissing
    },
    isInvalid() {
      return this.libraryItem.isInvalid
    },
    bookCoverAspectRatio() {
      return this.$store.getters['libraries/getBookCoverAspectRatio']
    },
    coverWidth() {
      return 50
    },
    isMissing() {
      return this.libraryItem.isMissing
    },
    isInvalid() {
      return this.libraryItem.isInvalid
    },
    isStreaming() {
      return this.$store.getters['getIsItemStreaming'](this.item.id)
    },
    showPlayBtn() {
      return !this.isMissing && !this.isInvalid && !this.isStreaming && (this.tracks.length || this.episode)
    }
  },
  methods: {},
  mounted() {}
}
</script>

<style>
.item-table-content {
  width: calc(100% - 50px);
  max-width: calc(100% - 50px);
}
</style>