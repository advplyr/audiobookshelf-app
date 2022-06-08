<template>
  <div class="w-full">
    <div class="flex items-center">
      <p class="text-lg mb-1 font-semibold">Episodes ({{ episodesFiltered.length }})</p>
      <div class="flex-grow" />
      <button class="outline:none mx-3 pt-0.5 relative" @click="showFilters">
        <span class="material-icons text-xl text-gray-200">filter_alt</span>
        <div v-show="filterKey !== 'all' && episodesAreFiltered" class="absolute top-0 right-0 w-1.5 h-1.5 rounded-full bg-success border border-green-300 shadow-sm z-10 pointer-events-none" />
      </button>

      <div class="flex items-center border border-white border-opacity-25 rounded px-2" @click="clickSort">
        <p class="text-sm text-gray-200">{{ sortText }}</p>
        <span class="material-icons ml-1 text-gray-200">{{ sortDesc ? 'arrow_drop_down' : 'arrow_drop_up' }}</span>
      </div>
    </div>

    <template v-for="episode in episodesSorted">
      <tables-podcast-episode-row :episode="episode" :local-episode="localEpisodeMap[episode.id]" :library-item-id="libraryItemId" :local-library-item-id="localLibraryItemId" :is-local="isLocal" :key="episode.id" />
    </template>

    <!-- What in tarnation is going on here?
        Without anything below the template it will not re-render -->
    <p>&nbsp;</p>

    <modals-dialog v-model="showFiltersModal" title="Episode Filter" :items="filterItems" :selected="filterKey" @action="setFilter" />
  </div>
</template>

<script>
export default {
  props: {
    libraryItemId: String,
    episodes: {
      type: Array,
      default: () => []
    },
    localLibraryItemId: String,
    localEpisodes: {
      type: Array,
      default: () => []
    },
    isLocal: Boolean // If is local then episodes and libraryItemId are local, otherwise local is passed in localLibraryItemId and localEpisodes
  },
  data() {
    return {
      episodesCopy: [],
      showFiltersModal: false,
      sortKey: 'publishedAt',
      sortDesc: false,
      filterKey: 'incomplete',
      episodeSortItems: [
        {
          text: 'Pub Date',
          value: 'publishedAt'
        },
        {
          text: 'Title',
          value: 'title'
        },
        {
          text: 'Season',
          value: 'season'
        },
        {
          text: 'Episode',
          value: 'episode'
        }
      ],
      filterItems: [
        {
          text: 'Show All',
          value: 'all'
        },
        {
          text: 'Incomplete',
          value: 'incomplete'
        },
        {
          text: 'In Progress',
          value: 'inProgress'
        },
        {
          text: 'Complete',
          value: 'complete'
        }
      ]
    }
  },
  watch: {
    episodes: {
      immediate: true,
      handler() {
        this.init()
      }
    }
  },
  computed: {
    episodesAreFiltered() {
      return this.episodesFiltered.length !== this.episodesCopy.length
    },
    episodesFiltered() {
      return this.episodesCopy.filter((ep) => {
        var mediaProgress = this.getEpisodeProgress(ep)
        if (this.filterKey === 'incomplete') {
          return !mediaProgress || !mediaProgress.isFinished
        } else if (this.filterKey === 'complete') {
          return mediaProgress && mediaProgress.isFinished
        } else if (this.filterKey === 'inProgress') {
          return mediaProgress && !mediaProgress.isFinished
        } else if (this.filterKey === 'all') {
          console.log('Filter key is all')
          return true
        }
        return true
      })
    },
    episodesSorted() {
      return this.episodesFiltered.sort((a, b) => {
        if (this.sortDesc) {
          return String(b[this.sortKey]).localeCompare(String(a[this.sortKey]), undefined, { numeric: true, sensitivity: 'base' })
        }
        return String(a[this.sortKey]).localeCompare(String(b[this.sortKey]), undefined, { numeric: true, sensitivity: 'base' })
      })
    },
    // Map of local episodes where server episode id is key
    localEpisodeMap() {
      var epmap = {}
      this.localEpisodes.forEach((localEp) => {
        if (localEp.serverEpisodeId) {
          epmap[localEp.serverEpisodeId] = localEp
        }
      })
      return epmap
    },
    sortText() {
      if (!this.sortKey) return ''
      var _sel = this.episodeSortItems.find((i) => i.value === this.sortKey)
      if (!_sel) return ''
      return _sel.text
    }
  },
  methods: {
    setFilter(filter) {
      this.filterKey = filter
      console.log('Set filter', this.filterKey)
      this.showFiltersModal = false
    },
    showFilters() {
      this.showFiltersModal = true
    },
    clickSort() {
      this.sortDesc = !this.sortDesc
    },
    getEpisodeProgress(episode) {
      if (this.isLocal) return this.$store.getters['globals/getLocalMediaProgressById'](this.libraryItemId, episode.id)
      return this.$store.getters['user/getUserMediaProgress'](this.libraryItemId, episode.id)
    },
    init() {
      this.episodesCopy = this.episodes.map((ep) => {
        return { ...ep }
      })
    }
  },
  mounted() {}
}
</script>