<template>
  <div class="flex items-center justify-center">
    <img v-if="download.cover" :src="download.cover" class="w-10 h-16 object-contain" />
    <img v-else src="/book_placeholder.jpg" class="w-10 h-16 object-contain" />
    <div class="pl-2 w-2/3">
      <p class="font-normal truncate text-sm">{{ download.audiobook.book.title }}</p>
      <p class="font-normal truncate text-xs text-gray-400">{{ download.audiobook.book.author }}</p>
      <p class="font-normal truncate text-xs text-gray-400">{{ $bytesPretty(download.size) }}</p>
    </div>
    <div class="flex-grow" />
    <div v-if="download.isIncomplete || download.isMissing" class="shadow-sm text-warning flex items-center justify-center rounded-full mr-4">
      <span class="material-icons">error_outline</span>
    </div>
    <button v-if="!isMissing" class="shadow-sm text-accent flex items-center justify-center rounded-full" @click.stop="playDownload">
      <span class="material-icons" style="font-size: 2rem">play_arrow</span>
    </button>
    <div class="shadow-sm text-error flex items-center justify-center rounded-ful ml-4" @click.stop="clickDelete">
      <span class="material-icons" style="font-size: 1.2rem">delete</span>
    </div>
  </div>
</template>

<script>
export default {
  props: {
    download: {
      type: Object,
      default: () => {}
    }
  },
  data() {
    return {}
  },
  computed: {
    isIncomplete() {
      return this.download.isIncomplete
    },
    isMissing() {
      return this.download.isMissing
    }
  },
  methods: {
    playDownload() {
      this.$emit('play', this.download)
    },
    clickDelete() {
      this.$emit('delete', this.download)
    }
  },
  mounted() {}
}
</script>