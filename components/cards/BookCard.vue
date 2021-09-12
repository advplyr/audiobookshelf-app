<template>
  <div class="relative">
    <!-- New Book Flag -->
    <div v-if="isNew" class="absolute top-4 left-0 w-4 h-10 pr-2 bg-darkgreen box-shadow-xl">
      <div class="absolute top-0 left-0 w-full h-full transform -rotate-90 flex items-center justify-center">
        <p class="text-center text-sm">New</p>
      </div>
      <div class="absolute -bottom-4 left-0 triangle-right" />
    </div>

    <div class="rounded-sm h-full overflow-hidden relative box-shadow-book">
      <nuxt-link :to="`/audiobook/${audiobookId}`" class="cursor-pointer">
        <div class="w-full relative" :style="{ height: height + 'px' }">
          <cards-book-cover :audiobook="audiobook" :download-cover="downloadCover" :author-override="authorFormat" :width="width" />

          <div v-if="download" class="absolute" :style="{ top: 0.5 * sizeMultiplier + 'rem', right: 0.5 * sizeMultiplier + 'rem' }">
            <span class="material-icons text-success" :style="{ fontSize: 1.1 * sizeMultiplier + 'rem' }">download_done</span>
          </div>

          <div class="absolute bottom-0 left-0 h-1.5 bg-yellow-400 shadow-sm" :style="{ width: width * userProgressPercent + 'px' }"></div>

          <div :style="{ height: 1.5 * sizeMultiplier + 'rem', width: 2.5 * sizeMultiplier + 'rem' }" class="bg-error rounded-r-full shadow-md flex items-center justify-end border-r border-b border-red-300">
            <span class="material-icons text-red-100 pr-1" :style="{ fontSize: 0.875 * sizeMultiplier + 'rem' }">priority_high</span>
          </div>
        </div>
      </nuxt-link>
    </div>
  </div>
</template>

<script>
export default {
  props: {
    audiobook: {
      type: Object,
      default: () => null
    },
    userProgress: {
      type: Object,
      default: () => null
    },
    localUserProgress: {
      type: Object,
      default: () => null
    },
    width: {
      type: Number,
      default: 140
    }
  },
  data() {
    return {}
  },
  computed: {
    isNew() {
      return this.tags.includes('new')
    },
    tags() {
      return this.audiobook.tags || []
    },
    audiobookId() {
      return this.audiobook.id
    },
    book() {
      return this.audiobook.book || {}
    },
    height() {
      return this.width * 1.6
    },
    sizeMultiplier() {
      return this.width / 120
    },
    paddingX() {
      return 16 * this.sizeMultiplier
    },
    author() {
      return this.book.author
    },
    authorFL() {
      return this.book.authorFL || this.author
    },
    authorLF() {
      return this.book.authorLF || this.author
    },
    authorFormat() {
      if (!this.orderBy || !this.orderBy.startsWith('book.author')) return null
      return this.orderBy === 'book.authorLF' ? this.authorLF : this.authorFL
    },
    orderBy() {
      return this.$store.getters['user/getUserSetting']('orderBy')
    },
    mostRecentUserProgress() {
      if (!this.localUserProgress) return this.userProgress
      if (!this.userProgress) return this.localUserProgress
      return this.localUserProgress.lastUpdate > this.userProgress.lastUpdate ? this.localUserProgress : this.userProgress
    },
    userProgressPercent() {
      return this.mostRecentUserProgress ? this.mostRecentUserProgress.progress || 0 : 0
    },
    showError() {
      return this.hasMissingParts || this.hasInvalidParts
    },
    hasMissingParts() {
      return this.audiobook.hasMissingParts
    },
    hasInvalidParts() {
      return this.audiobook.hasInvalidParts
    },
    downloadCover() {
      return this.download ? this.download.cover : null
    },
    download() {
      return this.$store.getters['downloads/getDownloadIfReady'](this.audiobookId)
    },
    errorText() {
      var txt = ''
      if (this.hasMissingParts) {
        txt = `${this.hasMissingParts} missing parts.`
      }
      if (this.hasInvalidParts) {
        if (this.hasMissingParts) txt += ' '
        txt += `${this.hasInvalidParts} invalid parts.`
      }
      return txt || 'Unknown Error'
    }
  },
  methods: {},
  mounted() {}
}
</script>
