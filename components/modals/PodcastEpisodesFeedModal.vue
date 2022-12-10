<template>
  <modals-modal v-model="show" width="100%" height="100%" max-width="100%">
    <template #outer>
      <div class="absolute top-6 left-4 z-40">
        <p class="text-white text-2xl truncate">Feed Episodes</p>
      </div>
    </template>
    <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" @click="show = false">
      <div class="feed-content w-full overflow-x-hidden overflow-y-auto bg-bg rounded-lg border border-white border-opacity-20">
        <template v-for="(episode, index) in episodes">
          <div :key="index" class="relative" :class="index % 2 == 0 ? 'bg-primary bg-opacity-50' : 'bg-primary bg-opacity-25'">
            <div class="absolute top-0 left-0 h-full flex items-center p-2">
              <span v-if="itemEpisodeMap[episode.enclosure.url]" class="material-icons text-success text-xl">download_done</span>
              <!-- <ui-checkbox v-else v-model="selectedEpisodes[String(index)]" small checkbox-bg="primary" border-color="gray-600" /> -->
            </div>
            <div class="px-2 py-2 border-b border-white border-opacity-10">
              <p v-if="episode.episode" class="font-semibold text-gray-200 text-xs">#{{ episode.episode }}</p>
              <p class="break-words mb-1 text-sm">{{ episode.title }}</p>
              <p v-if="episode.subtitle" class="break-words mb-1 text-xs text-gray-300 episode-subtitle">{{ episode.subtitle }}</p>
              <p class="text-xxs text-gray-300">Published {{ episode.publishedAt ? $dateDistanceFromNow(episode.publishedAt) : 'Unknown' }}</p>
            </div>
          </div>
        </template>
      </div>
      <div class="absolute bottom-0 left-0 w-full flex items-center" style="height: 50px">
        <ui-btn class="w-full" color="success">Download Episodes</ui-btn>
      </div>
    </div>
  </modals-modal>
</template>

<script>
export default {
  props: {
    value: Boolean,
    libraryItem: {
      type: Object,
      default: () => {}
    },
    episodes: {
      type: Array,
      default: () => []
    }
  },
  data() {
    return {}
  },
  watch: {
    show: {
      immediate: true,
      handler(newVal) {
        if (newVal) this.init()
      }
    }
  },
  computed: {
    show: {
      get() {
        return this.value
      },
      set(val) {
        this.$emit('input', val)
      }
    },
    itemEpisodes() {
      if (!this.libraryItem) return []
      return this.libraryItem.media.episodes || []
    },
    itemEpisodeMap() {
      var map = {}
      this.itemEpisodes.forEach((item) => {
        if (item.enclosure) map[item.enclosure.url] = true
      })
      return map
    }
  },
  methods: {
    init() {
      this.episodes.sort((a, b) => (a.publishedAt < b.publishedAt ? 1 : -1))
    }
  },
  mounted() {}
}
</script>

<style>
.feed-content {
  height: calc(100vh - 125px);
  max-height: calc(100vh - 125px);
  margin-top: 20px;
}
</style>