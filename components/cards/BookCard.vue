<template>
  <div class="relative">
    <div class="rounded-sm h-full overflow-hidden relative box-shadow-book">
      <nuxt-link :to="`/audiobook/${audiobookId}`" class="cursor-pointer">
        <div class="w-full relative" :style="{ height: height + 'px' }">
          <covers-book-cover :audiobook="audiobook" :download-cover="downloadCover" :width="width" :book-cover-aspect-ratio="bookCoverAspectRatio" />

          <div v-if="download" class="absolute" :style="{ top: 0.5 * sizeMultiplier + 'rem', right: 0.5 * sizeMultiplier + 'rem' }">
            <span class="material-icons text-success" :style="{ fontSize: 1.1 * sizeMultiplier + 'rem' }">download_done</span>
          </div>

          <div class="absolute bottom-0 left-0 h-1.5 shadow-sm" :class="userIsRead ? 'bg-success' : 'bg-yellow-400'" :style="{ width: width * userProgressPercent + 'px' }"></div>

          <div v-if="showError" :style="{ height: 1.5 * sizeMultiplier + 'rem', width: 2.5 * sizeMultiplier + 'rem', bottom: sizeMultiplier + 'rem' }" class="bg-error rounded-r-full shadow-md flex items-center justify-end border-r border-b border-red-300 absolute left-0">
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
    width: {
      type: Number,
      default: 140
    },
    bookCoverAspectRatio: Number
  },
  data() {
    return {}
  },
  computed: {
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
      return this.width * this.bookCoverAspectRatio
    },
    sizeMultiplier() {
      if (this.bookCoverAspectRatio === 1) return this.width / 160
      return this.width / 100
    },
    mostRecentUserProgress() {
      return this.$store.getters['user/getUserAudiobookData'](this.audiobookId)
    },
    userProgressPercent() {
      return this.mostRecentUserProgress ? this.mostRecentUserProgress.progress || 0 : 0
    },
    userIsRead() {
      return this.mostRecentUserProgress ? !!this.mostRecentUserProgress.isRead : false
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
