<template>
  <modals-modal v-model="show" width="100%" height="100%" max-width="100%">
    <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" data-modal-backdrop >
      <div class="feed-content w-full overflow-x-hidden overflow-y-auto bg-surface rounded-lg border border-outline-variant shadow-elevation-4 backdrop-blur-md">
        <!-- Material 3 Modal Header -->
        <div class="px-6 py-4 border-b border-outline-variant">
          <h2 class="text-headline-small text-on-surface font-medium">Feed Episodes</h2>
        </div>

        <template v-for="(episode, index) in episodes">
          <div :key="index" class="relative state-layer" :class="itemEpisodeMap[episode.enclosure.url] ? 'bg-primary-container text-on-primary-container' : selectedEpisodes[String(index)] ? 'bg-success-container text-on-success-container' : index % 2 == 0 ? 'bg-surface-variant bg-opacity-25' : 'bg-surface'" @click="selectEpisode(episode, index)">
            <div class="absolute top-0 left-0 h-full flex items-center p-2">
              <span v-if="itemEpisodeMap[episode.enclosure.url]" class="material-symbols text-success text-xl">download_done</span>
              <ui-checkbox v-else v-model="selectedEpisodes[String(index)]" small checkbox-bg="primary" border-color="gray-600" />
            </div>
            <div class="pl-9 pr-2 py-2 border-b border-outline-variant border-opacity-50">
              <p v-if="episode.episode" class="font-semibold text-on-surface text-xs">#{{ episode.episode }}</p>
              <p class="text-base font-semibold text-on-surface break-words">{{ episode.title }}</p>
              <p v-if="episode.subtitle" class="break-words mb-1 text-xs text-on-surface-variant episode-subtitle">{{ episode.subtitle }}</p>
              <p class="text-xxs text-on-surface-variant">{{ $getString('LabelPublishedDate', [episode.publishedAt ? $dateDistanceFromNow(episode.publishedAt) : $strings.LabelUnknown]) }}</p>
            </div>
          </div>
        </template>
      </div>
      <div class="absolute bottom-6 left-0 w-full flex items-center" style="height: 50px">
        <ui-btn class="w-full" :disabled="!episodesSelected.length" color="success" @click="downloadEpisodes">{{ episodesSelected.length ? `Add ${episodesSelected.length} Episode(s) to Server` : 'No Episodes Selected' }}</ui-btn>
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
    return {
      processing: false,
      selectedEpisodes: {}
    }
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
    allDownloaded() {
      return !this.episodes.some((episode) => !this.itemEpisodeMap[episode.enclosure.url])
    },
    episodesSelected() {
      return Object.keys(this.selectedEpisodes).filter((key) => !!this.selectedEpisodes[key])
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
    downloadEpisodes() {
      const episodesToDownload = this.episodesSelected.map((episodeIndex) => this.episodes[Number(episodeIndex)])

      const payloadSize = JSON.stringify(episodesToDownload).length
      const sizeInMb = payloadSize / 1024 / 1024
      const sizeInMbPretty = sizeInMb.toFixed(2) + 'MB'
      console.log('Request size', sizeInMb)
      if (sizeInMb > 4.99) {
        return this.$toast.error(`Request is too large (${sizeInMbPretty}) should be < 5Mb`)
      }

      this.processing = true
      this.$nativeHttp
        .post(`/api/podcasts/${this.libraryItem.id}/download-episodes`, episodesToDownload)
        .then(() => {
          this.processing = false
          this.$toast.success('Started downloading episodes on server')
          this.show = false
        })
        .catch((error) => {
          var errorMsg = error.response && error.response.data ? error.response.data : 'Failed to download episodes'
          console.error('Failed to download episodes', error)
          this.processing = false
          this.$toast.error(errorMsg)

          this.selectedEpisodes = {}
        })
    },
    selectEpisode(episode, index) {
      if (this.itemEpisodeMap[episode.enclosure.url]) return
      this.selectedEpisodes[String(index)] = !this.selectedEpisodes[String(index)]
    },
    init() {
      this.episodes.sort((a, b) => (a.publishedAt < b.publishedAt ? 1 : -1))
      for (let i = 0; i < this.episodes.length; i++) {
        // this.selectedEpisodes[String(i)] = false
        this.$set(this.selectedEpisodes, String(i), false)
      }
    }
  },
  mounted() {}
}
</script>

<style>
.feed-content {
  height: calc(100vh - 150px);
  max-height: calc(100vh - 150px);
  margin-top: 5px;
}
</style>
