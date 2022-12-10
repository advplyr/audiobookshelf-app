<template>
  <div class="w-full">
    <!-- Podcast episode downloads queue -->
    <div v-if="episodeDownloadsQueued.length" class="px-4 py-2 my-2 bg-info bg-opacity-40 text-sm font-semibold rounded-md text-gray-100 relative w-full">
      <div class="flex items-center">
        <p class="text-sm py-1">{{ episodeDownloadsQueued.length }} Episode(s) queued for download</p>
        <div class="flex-grow" />
        <span v-if="isAdminOrUp" class="material-icons text-xl ml-3 cursor-pointer" @click="clearDownloadQueue">close</span>
      </div>
    </div>

    <!-- Podcast episodes currently downloading -->
    <div v-if="episodesDownloading.length" class="px-4 py-2 my-2 bg-success bg-opacity-20 text-sm font-semibold rounded-md text-gray-100 relative w-full">
      <div v-for="episode in episodesDownloading" :key="episode.id" class="flex items-center">
        <widgets-loading-spinner />
        <p class="text-sm py-1 pl-4">Downloading episode "{{ episode.episodeDisplayTitle }}"</p>
      </div>
    </div>

    <div class="flex items-center">
      <p class="text-lg mb-1 font-semibold">Episodes ({{ episodesFiltered.length }})</p>

      <div class="flex-grow" />

      <button v-if="isAdminOrUp && !fetchingRSSFeed" class="outline:none mx-1 pt-0.5 relative" @click="searchEpisodes">
        <span class="material-icons text-xl text-gray-200">search</span>
      </button>
      <widgets-loading-spinner v-else-if="fetchingRSSFeed" class="mx-1" />

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
      <tables-podcast-episode-row :episode="episode" :local-episode="localEpisodeMap[episode.id]" :library-item-id="libraryItemId" :local-library-item-id="localLibraryItemId" :is-local="isLocal" :key="episode.id" @addToPlaylist="addEpisodeToPlaylist" />
    </template>

    <!-- Huhhh?
        Without anything below the template it will not re-render -->
    <p>&nbsp;</p>

    <modals-dialog v-model="showFiltersModal" title="Episode Filter" :items="filterItems" :selected="filterKey" @action="setFilter" />

    <modals-podcast-episodes-feed-modal v-model="showPodcastEpisodeFeed" :library-item="libraryItem" :episodes="podcastFeedEpisodes" />
  </div>
</template>

<script>
import { Dialog } from '@capacitor/dialog'

export default {
  props: {
    libraryItem: {
      type: Object,
      default: () => {}
    },
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
      sortDesc: true,
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
      ],
      fetchingRSSFeed: false,
      podcastFeedEpisodes: [],
      showPodcastEpisodeFeed: false,
      episodesDownloading: [],
      episodeDownloadsQueued: []
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
    isAdminOrUp() {
      return this.$store.getters['user/getIsAdminOrUp']
    },
    libraryItemId() {
      return this.libraryItem ? this.libraryItem.id : null
    },
    media() {
      return this.libraryItem ? this.libraryItem.media || {} : {}
    },
    mediaMetadata() {
      return this.media.metadata || {}
    },
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
    async clearDownloadQueue() {
      const { value } = await Dialog.confirm({
        title: 'Confirm',
        message: `Are you sure you want to clear episode download queue?`
      })

      if (value) {
        this.$axios
          .$get(`/api/podcasts/${this.libraryItemId}/clear-queue`)
          .then(() => {
            this.$toast.success('Episode download queue cleared')
            this.episodeDownloadQueued = []
          })
          .catch((error) => {
            console.error('Failed to clear queue', error)
            this.$toast.error('Failed to clear queue')
          })
      }
    },
    async searchEpisodes() {
      if (!this.mediaMetadata.feedUrl) {
        return this.$toast.error('Podcast does not have an RSS Feed')
      }
      this.fetchingRSSFeed = true
      const payload = await this.$axios.$post(`/api/podcasts/feed`, { rssFeed: this.mediaMetadata.feedUrl }).catch((error) => {
        console.error('Failed to get feed', error)
        this.$toast.error('Failed to get podcast feed')
        return null
      })
      this.fetchingRSSFeed = false
      if (!payload) return

      console.log('Podcast feed', payload)
      const podcastfeed = payload.podcast
      if (!podcastfeed.episodes || !podcastfeed.episodes.length) {
        this.$toast.info('No episodes found in RSS feed')
        return
      }

      this.podcastFeedEpisodes = podcastfeed.episodes
      this.showPodcastEpisodeFeed = true
    },
    addEpisodeToPlaylist(episode) {
      this.$store.commit('globals/setSelectedPlaylistItems', [{ libraryItem: this.libraryItem, episode }])
      this.$store.commit('globals/setShowPlaylistsAddCreateModal', true)
    },
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
    },
    episodeDownloadQueued(episodeDownload) {
      if (episodeDownload.libraryItemId === this.libraryItemId) {
        this.episodeDownloadsQueued.push(episodeDownload)
      }
    },
    episodeDownloadStarted(episodeDownload) {
      if (episodeDownload.libraryItemId === this.libraryItemId) {
        this.episodeDownloadsQueued = this.episodeDownloadsQueued.filter((d) => d.id !== episodeDownload.id)
        this.episodesDownloading.push(episodeDownload)
      }
    },
    episodeDownloadFinished(episodeDownload) {
      if (episodeDownload.libraryItemId === this.libraryItemId) {
        this.episodeDownloadsQueued = this.episodeDownloadsQueued.filter((d) => d.id !== episodeDownload.id)
        this.episodesDownloading = this.episodesDownloading.filter((d) => d.id !== episodeDownload.id)
      }
    }
  },
  mounted() {
    this.$socket.$on('episode_download_queued', this.episodeDownloadQueued)
    this.$socket.$on('episode_download_started', this.episodeDownloadStarted)
    this.$socket.$on('episode_download_finished', this.episodeDownloadFinished)
  },
  beforeDestroy() {
    this.$socket.$off('episode_download_queued', this.episodeDownloadQueued)
    this.$socket.$off('episode_download_started', this.episodeDownloadStarted)
    this.$socket.$off('episode_download_finished', this.episodeDownloadFinished)
  }
}
</script>