<template>
  <div class="w-full h-full px-0 py-4 overflow-y-auto">
    <h1 class="text-xl px-4">
      Stats for <b>{{ username }}</b>
    </h1>

    <div class="flex text-center justify-center">
      <div class="flex p-2">
        <div class="px-3">
          <p class="text-4xl md:text-5xl font-bold">{{ userItemsFinished.length }}</p>
          <p class="text-xs md:text-sm text-white text-opacity-80">Items Finished</p>
        </div>
      </div>

      <div class="flex p-2">
        <div class="px-1">
          <p class="text-4xl md:text-5xl font-bold">{{ totalDaysListened }}</p>
          <p class="text-xs md:text-sm text-white text-opacity-80">Days Listened</p>
        </div>
      </div>

      <div class="flex p-2">
        <div class="px-1">
          <p class="text-4xl md:text-5xl font-bold">{{ totalMinutesListening }}</p>
          <p class="text-xs md:text-sm text-white text-opacity-80">Minutes Listening</p>
        </div>
      </div>
    </div>
    <div class="flex flex-col md:flex-row overflow-hidden max-w-full">
      <stats-daily-listening-chart :listening-stats="listeningStats" class="lg:scale-100 transform scale-90 px-0" />
      <div class="w-80 my-6 mx-auto">
        <div class="flex mb-4 items-center">
          <h1 class="text-2xl">Recent Sessions</h1>
          <div class="flex-grow" />
        </div>
        <p v-if="!mostRecentListeningSessions.length">No Listening Sessions</p>
        <template v-for="(item, index) in mostRecentListeningSessions">
          <div :key="item.id" class="w-full py-0.5">
            <div class="flex items-center mb-1">
              <p class="text-smtext-white text-opacity-70 w-8">{{ index + 1 }}.&nbsp;</p>
              <div class="w-56">
                <p class="text-smtext-white text-opacity-80 truncate">{{ item.mediaMetadata ? item.mediaMetadata.title : '' }}</p>
                <p class="text-xs text-white text-opacity-50">{{ $dateDistanceFromNow(item.updatedAt) }}</p>
              </div>
              <div class="flex-grow" />
              <div class="w-18 text-right">
                <p class="text-sm font-bold">{{ $elapsedPretty(item.timeListening) }}</p>
              </div>
            </div>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  data() {
    return {
      listeningStats: null,
      windowWidth: 0
    }
  },
  watch: {
    currentLibraryId(newVal) {
      if (newVal) {
        this.init()
      }
    }
  },
  computed: {
    user() {
      return this.$store.state.user.user
    },
    username() {
      return this.user ? this.user.username : ''
    },
    currentLibraryId() {
      return this.$store.state.libraries.currentLibraryId
    },
    userMediaProgress() {
      return this.user ? this.user.mediaProgress : []
    },
    userItemsFinished() {
      return this.userMediaProgress.filter((lip) => !!lip.isFinished)
    },
    mostRecentListeningSessions() {
      if (!this.listeningStats) return []
      return this.listeningStats.recentSessions || []
    },
    totalMinutesListening() {
      if (!this.listeningStats) return 0
      return Math.round(this.listeningStats.totalTime / 60)
    },
    totalDaysListened() {
      if (!this.listeningStats) return 0
      return Object.values(this.listeningStats.days).length
    }
  },
  methods: {
    async init() {
      this.listeningStats = await this.$axios.$get(`/api/me/listening-stats`).catch((err) => {
        console.error('Failed to load listening sesions', err)
        return []
      })
      console.log('Loaded user listening data', this.listeningStats)
    }
  },
  mounted() {
    this.init()
  }
}
</script>
