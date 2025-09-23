<template>
  <div class="w-full">
    <!-- Podcast episode downloads queue -->
    <div v-if="episodeDownloadsQueued.length" class="px-4 py-2 my-2 bg-info bg-opacity-40 text-body-medium font-semibold rounded-md text-fg relative w-full">
      <div class="flex items-center">
        <p class="text-body-medium py-1">{{ $getString('MessageEpisodesQueuedForDownload', [episodeDownloadsQueued.length]) }}</p>
        <div class="flex-grow" />
        <span v-if="isAdminOrUp" class="material-symbols text-display-small ml-3 cursor-pointer" @click="clearDownloadQueue">close</span>
      </div>
    </div>

    <!-- Podcast episodes currently downloading -->
    <div v-if="episodesDownloading.length" class="px-4 py-2 my-2 bg-success bg-opacity-20 text-body-medium font-semibold rounded-md text-fg relative w-full">
      <div v-for="episode in episodesDownloading" :key="episode.id" class="flex items-center">
        <widgets-loading-spinner />
        <p class="text-body-medium py-1 pl-4">{{ $strings.MessageDownloadingEpisode }} "{{ episode.episodeDisplayTitle }}"</p>
      </div>
    </div>

    <div class="flex items-center">
      <p class="text-headline-small mb-1 font-semibold">{{ $strings.HeaderEpisodes }} ({{ episodesFiltered.length }})</p>

      <div class="flex-grow" />

      <button v-if="isAdminOrUp && !fetchingRSSFeed" class="outline-none mx-1 pt-0.5 relative w-10 h-10 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center hover:bg-secondary-container/80" @click="searchEpisodes">
        <span class="material-symbols text-display-small text-on-surface">search</span>
      </button>
      <widgets-loading-spinner v-else-if="fetchingRSSFeed" class="mx-1" />

      <button class="outline-none mx-3 pt-0.5 relative w-10 h-10 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center hover:bg-secondary-container/80" @click="showFilters">
        <span class="material-symbols text-display-small text-on-surface">filter_alt</span>
        <div v-show="filterKey !== 'all' && episodesAreFiltered" class="absolute top-0 right-0 w-1.5 h-1.5 rounded-full bg-success border border-outline shadow-sm z-10 pointer-events-none" />
      </button>

      <div class="flex items-center border border-outline rounded-2xl px-3 py-1 bg-surface-container hover:bg-surface-container-high cursor-pointer" @click="clickSort">
        <p class="text-body-medium text-on-surface">{{ sortText }}</p>
        <span class="material-symbols ml-1 text-on-surface">{{ sortDesc ? 'arrow_drop_down' : 'arrow_drop_up' }}</span>
      </div>
    </div>

    <template v-for="episode in episodesSorted">
      <tables-podcast-episode-row :episode="episode" :local-episode="localEpisodeMap[episode.id]" :library-item-id="libraryItemId" :local-library-item-id="localLibraryItemId" :is-local="isLocal" :sort-key="sortKey" :key="episode.id" @addToPlaylist="addEpisodeToPlaylist" />
    </template>

    <!-- Huhhh?
        Without anything below the template it will not re-render -->
    <p>&nbsp;</p>

    <modals-dialog v-model="showFiltersModal" title="Episode Filter" :items="filterItems" :selected="filterKey" @action="setFilter" />

    <modals-podcast-episodes-feed-modal v-model="showPodcastEpisodeFeed" :library-item="libraryItem" :episodes="podcastFeedEpisodes" />

    <modals-order-modal v-model="showSortModal" :order-by.sync="sortKey" :descending.sync="sortDesc" episodes />
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
      showSortModal: false,
      sortKey: 'publishedAt',
      sortDesc: true,
      filterKey: 'incomplete',
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
    socketConnected() {
      return this.$store.state.socketConnected
    },
    libraryItemId() {
      return this.libraryItem?.id || null
    },
    media() {
      return this.libraryItem?.media || {}
    },
    mediaMetadata() {
      return this.media.metadata || {}
    },
    episodesAreFiltered() {
      return this.episodesFiltered.length !== this.episodesCopy.length
    },
    episodeSortItems() {
      return [
        {
          text: this.$strings.LabelPubDate,
          value: 'publishedAt'
        },
        {
          text: this.$strings.LabelTitle,
          value: 'title'
        },
        {
          text: this.$strings.LabelSeason,
          value: 'season'
        },
        {
          text: this.$strings.LabelEpisode,
          value: 'episode'
        },
        {
          text: this.$strings.LabelFilename,
          value: 'audioFile.metadata.filename'
        }
      ]
    },
    filterItems() {
      return [
        {
          text: this.$strings.LabelShowAll,
          value: 'all'
        },
        {
          text: this.$strings.LabelIncomplete,
          value: 'incomplete'
        },
        {
          text: this.$strings.LabelInProgress,
          value: 'inProgress'
        },
        {
          text: this.$strings.LabelComplete,
          value: 'complete'
        },
        {
          text: this.$strings.LabelDownloaded,
          value: 'downloaded'
        }
      ]
    },
    episodesFiltered() {
      return this.episodesCopy.filter((ep) => {
        if (this.filterKey === 'downloaded') {
          return !!this.localEpisodeMap[ep.id]
        }
        var mediaProgress = this.getEpisodeProgress(ep)
        if (this.filterKey === 'incomplete') {
          return !mediaProgress?.isFinished
        } else if (this.filterKey === 'complete') {
          return mediaProgress?.isFinished
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
        let aValue
        let bValue

        if (this.sortKey.includes('.')) {
          const getNestedValue = (ob, s) => s.split('.').reduce((o, k) => o?.[k], ob)
          aValue = getNestedValue(a, this.sortKey)
          bValue = getNestedValue(b, this.sortKey)
        } else {
          aValue = a[this.sortKey]
          bValue = b[this.sortKey]
        }

        // Sort episodes with no pub date as the oldest
        if (this.sortKey === 'publishedAt') {
          if (!aValue) aValue = Number.MAX_VALUE
          if (!bValue) bValue = Number.MAX_VALUE
        }

        if (this.sortDesc) {
          return String(bValue).localeCompare(String(aValue), undefined, { numeric: true, sensitivity: 'base' })
        }
        return String(aValue).localeCompare(String(bValue), undefined, { numeric: true, sensitivity: 'base' })
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
      const _sel = this.episodeSortItems.find((i) => i.value === this.sortKey)
      return _sel?.text || ''
    }
  },
  methods: {
    async clearDownloadQueue() {
      const { value } = await Dialog.confirm({
        title: 'Confirm',
        message: `Are you sure you want to clear episode download queue?`
      })

      if (value) {
        this.$nativeHttp
          .get(`/api/podcasts/${this.libraryItemId}/clear-queue`)
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
      if (!this.socketConnected) {
        return this.$toast.error(this.$strings.MessageNoNetworkConnection)
      }

      if (!this.mediaMetadata.feedUrl) {
        return this.$toast.error('Podcast does not have an RSS Feed')
      }
      this.fetchingRSSFeed = true
      const payload = await this.$nativeHttp.post(`/api/podcasts/feed`, { rssFeed: this.mediaMetadata.feedUrl }).catch((error) => {
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
      this.showFiltersModal = false
    },
    showFilters() {
      this.showFiltersModal = true
    },
    clickSort() {
      this.showSortModal = true
    },
    getEpisodeProgress(episode) {
      if (this.isLocal) return this.$store.getters['globals/getLocalMediaProgressById'](this.libraryItemId, episode.id)
      return this.$store.getters['user/getUserMediaProgress'](this.libraryItemId, episode.id)
    },
    init() {
      this.sortDesc = this.mediaMetadata.type === 'episodic'
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
    if (this.$route.query['episodefilter'] === 'downloaded') {
      this.filterKey = 'downloaded'
    }
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
