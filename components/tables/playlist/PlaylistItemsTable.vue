<template>
  <div class="w-full bg-surface-container border border-outline-variant rounded-2xl">
    <div class="w-full h-14 flex items-center px-3 bg-surface-container text-on-surface border-b border-outline-variant rounded-t-2xl">
      <p class="pr-2 font-medium">{{ $strings.HeaderPlaylistItems }}</p>

      <div class="w-6 h-6 md:w-7 md:h-7 bg-secondary-container text-on-secondary-container rounded-full flex items-center justify-center">
        <span class="text-label-small md:text-body-medium font-mono leading-none">{{ items.length }}</span>
      </div>

      <div class="flex-grow" />
      <p v-if="totalDuration" class="text-body-medium text-on-surface-variant">{{ totalDurationPretty }}</p>
    </div>
    <template v-for="item in items">
      <tables-playlist-item-table-row :key="item.id" :item="item" :playlist-id="playlistId" @showMore="showMore" />
    </template>
  </div>
</template>

<script>
export default {
  props: {
    playlistId: String,
    items: {
      type: Array,
      default: () => []
    }
  },
  data() {
    return {}
  },
  computed: {
    totalDuration() {
      var _total = 0
      this.items.forEach((item) => {
        if (item.episode) _total += item.episode.duration
        else _total += item.libraryItem.media.duration
      })
      return _total
    },
    totalDurationPretty() {
      return this.$elapsedPrettyExtended(this.totalDuration)
    }
  },
  methods: {
    showMore(playlistItem) {
      this.$emit('showMore', playlistItem)
    }
  },
  mounted() {}
}
</script>
