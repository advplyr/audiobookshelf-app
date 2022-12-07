<template>
  <div class="w-full h-full relative overflow-hidden">
    <template v-if="!showSelectedFeed">
      <div class="w-full mx-auto flex py-5 px-2">
        <form @submit.prevent="submit" class="flex flex-grow">
          <ui-text-input v-model="searchInput" :disabled="processing" placeholder="Enter search term or RSS feed URL" text-size="sm" class="flex-grow mr-2" />
          <!-- <ui-btn type="submit" :disabled="processing" small>Submit</ui-btn> -->
        </form>
      </div>

      <div class="w-full mx-auto pb-2 search-results-container overflow-y-auto overflow-x-hidden">
        <p v-if="termSearched && !results.length && !processing" class="text-center text-xl">No Podcasts Found</p>
        <template v-for="podcast in results">
          <div :key="podcast.id" class="p-2 border-b border-white border-opacity-10" @click="selectPodcast(podcast)">
            <div class="flex">
              <div class="w-8 min-w-8 py-1">
                <div class="h-8 w-full bg-primary">
                  <img v-if="podcast.cover" :src="podcast.cover" class="h-full w-full" />
                </div>
              </div>
              <div class="flex-grow pl-2">
                <p class="text-xs text-gray-100 whitespace-nowrap truncate">{{ podcast.artistName }}</p>
                <p class="text-xxs text-gray-300 leading-5">{{ podcast.trackCount }} Episodes</p>
              </div>
            </div>

            <p class="text-sm text-gray-200 mb-1">{{ podcast.title }}</p>
            <p class="text-xs text-gray-400 leading-5">{{ podcast.genres.join(', ') }}</p>
          </div>
        </template>
      </div>
    </template>
    <template v-else>
      <div class="flex items-center mb-4 py-4 px-2">
        <div class="flex items-center" @click="clearSelected">
          <span class="material-icons text-2xl text-gray-300">arrow_back</span>
          <p class="pl-2 uppercase text-sm font-semibold text-gray-300 leading-4 pb-px">Back</p>
        </div>
      </div>

      <div class="p-2">
        <p>Selected Podcast Feed</p>
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
  computed: {},
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
      var payload = await this.$axios.$post(`/api/podcasts/feed`, { rssFeed }).catch((error) => {
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
      const results = await this.$axios.$get(`/api/search/podcast?term=${encodeURIComponent(term)}`).catch((error) => {
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
      const payload = await this.$axios.$post(`/api/podcasts/feed`, { rssFeed: podcast.feedUrl }).catch((error) => {
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
    }
  },
  mounted() {}
}
</script>

<style scoped>
.search-results-container {
  max-height: calc(100vh - 180px);
}
</style>