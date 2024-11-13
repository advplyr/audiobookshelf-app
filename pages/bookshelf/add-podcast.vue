<template>
  <div class="w-full h-full relative overflow-hidden">
    <template v-if="!showSelectedFeed">
      <div class="w-full mx-auto h-20 flex items-center px-2">
        <form class="w-full" @submit.prevent="submit">
          <ui-text-input v-model="searchInput" :disabled="processing || !socketConnected" :placeholder="$strings.MessagePodcastSearchField" text-size="sm" />
        </form>
      </div>

      <div v-if="!socketConnected" class="w-full text-center py-6">
        <p class="text-lg text-error">{{ $strings.MessageNoNetworkConnection }}</p>
      </div>
      <div v-else class="w-full mx-auto pb-2 overflow-y-auto overflow-x-hidden h-[calc(100%-85px)]">
        <p v-if="termSearched && !results.length && !processing" class="text-center text-xl">{{ $strings.MessageNoPodcastsFound }}</p>
        <template v-for="podcast in results">
          <div :key="podcast.id" class="p-2 border-b border-fg border-opacity-10" @click="selectPodcast(podcast)">
            <div class="flex">
              <div class="w-8 min-w-8 py-1">
                <div class="h-8 w-full bg-primary">
                  <img v-if="podcast.cover" :src="podcast.cover" class="h-full w-full" />
                </div>
              </div>
              <div class="flex-grow pl-2">
                <p class="text-xs text-fg whitespace-nowrap truncate">{{ podcast.artistName }}</p>
                <p class="text-xxs text-fg leading-5">{{ podcast.trackCount }} {{ $strings.HeaderEpisodes }}</p>
              </div>
            </div>

            <p class="text-sm text-fg mb-1">{{ podcast.title }}</p>
            <p class="text-xs text-fg-muted leading-5">{{ podcast.genres.join(', ') }}</p>
          </div>
        </template>
      </div>
    </template>
    <template v-else>
      <div class="flex items-center px-2 h-16">
        <div class="flex items-center" @click="clearSelected">
          <span class="material-icons text-2xl text-fg-muted">arrow_back</span>
          <p class="pl-2 uppercase text-sm font-semibold text-fg-muted leading-4 pb-px">{{ $strings.ButtonBack }}</p>
        </div>
      </div>

      <div class="w-full py-2 overflow-y-auto overflow-x-hidden h-[calc(100%-69px)]">
        <forms-new-podcast-form :podcast-data="selectedPodcast" :podcast-feed-data="selectedPodcastFeed" :processing.sync="processing" />
      </div>
    </template>

    <div v-show="processing" class="absolute top-0 left-0 w-full h-full flex items-center justify-center bg-black bg-opacity-25 z-40">
      <ui-loading-indicator />
    </div>
  </div>
</template>

<script>
export default {
  data() {
    return {
      searchInput: '',
      termSearched: false,
      processing: false,
      results: [],
      selectedPodcastFeed: null,
      selectedPodcast: null,
      showSelectedFeed: false
    }
  },
  computed: {
    socketConnected() {
      return this.$store.state.socketConnected
    }
  },
  methods: {
    clearSelected() {
      this.selectedPodcastFeed = null
      this.selectedPodcast = null
      this.showSelectedFeed = false
    },
    submit() {
      if (!this.searchInput) return

      if (this.searchInput.startsWith('http:') || this.searchInput.startsWith('https:')) {
        this.termSearched = ''
        this.results = []
        this.checkRSSFeed(this.searchInput)
      } else {
        this.submitSearch(this.searchInput)
      }
    },
    async checkRSSFeed(rssFeed) {
      this.processing = true
      var payload = await this.$nativeHttp.post(`/api/podcasts/feed`, { rssFeed }).catch((error) => {
        console.error('Failed to get feed', error)
        this.$toast.error('Failed to get podcast feed')
        return null
      })
      this.processing = false
      if (!payload) return

      this.selectedPodcastFeed = payload.podcast
      this.selectedPodcast = null
      this.showSelectedFeed = true
    },
    async submitSearch(term) {
      this.processing = true
      this.termSearched = ''
      const results = await this.$nativeHttp.get(`/api/search/podcast?term=${encodeURIComponent(term)}`).catch((error) => {
        console.error('Search request failed', error)
        return []
      })
      console.log('Got results', results)
      this.results = results
      this.termSearched = term
      this.processing = false
    },
    async selectPodcast(podcast) {
      console.log('Selected podcast', podcast)
      if (!podcast.feedUrl) {
        this.$toast.error('Invalid podcast - no feed')
        return
      }
      this.processing = true
      const payload = await this.$nativeHttp.post(`/api/podcasts/feed`, { rssFeed: podcast.feedUrl }).catch((error) => {
        console.error('Failed to get feed', error)
        this.$toast.error('Failed to get podcast feed')
        return null
      })
      this.processing = false
      if (!payload) return

      this.selectedPodcastFeed = payload.podcast
      this.selectedPodcast = podcast
      this.showSelectedFeed = true
      console.log('Got podcast feed', payload.podcast)
    },
    libraryChanged() {
      const libraryMediaType = this.$store.getters['libraries/getCurrentLibraryMediaType']
      if (libraryMediaType !== 'podcast') {
        this.$router.replace('/bookshelf')
      }
    }
  },
  mounted() {
    this.$eventBus.$on('library-changed', this.libraryChanged)
  },
  beforeDestroy() {
    this.$eventBus.$off('library-changed', this.libraryChanged)
  }
}
</script>
