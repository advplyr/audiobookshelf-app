<template>
  <div class="w-full h-full px-0 py-4 overflow-y-auto" :style="contentPaddingStyle">
    <!-- Year in review banner shown at the top in December and January -->
    <stats-year-in-review-banner v-if="showYearInReviewBanner" />

    <h1 class="text-xl px-4">
      {{ $strings.HeaderYourStats }}
    </h1>

    <div class="flex text-center justify-center">
      <div class="flex p-2">
        <div class="px-3">
          <p class="text-4xl md:text-5xl font-bold">{{ $formatNumber(userItemsFinished.length) }}</p>
          <p class="text-xs md:text-sm text-on-surface-variant">{{ $strings.LabelStatsItemsFinished }}</p>
        </div>
      </div>

      <div class="flex p-2">
        <div class="px-1">
          <p class="text-4xl md:text-5xl font-bold">{{ $formatNumber(totalDaysListened) }}</p>
          <p class="text-xs md:text-sm text-on-surface-variant">{{ $strings.LabelStatsDaysListened }}</p>
        </div>
      </div>

      <div class="flex p-2">
        <div class="px-1">
          <p class="text-4xl md:text-5xl font-bold">{{ $formatNumber(totalMinutesListening) }}</p>
          <p class="text-xs md:text-sm text-on-surface-variant">{{ $strings.LabelStatsMinutesListening }}</p>
        </div>
      </div>
    </div>
    <div class="flex flex-col md:flex-row overflow-hidden max-w-full">
      <stats-daily-listening-chart :listening-stats="listeningStats" class="lg:scale-100 transform scale-90 px-0" />
      <div class="w-80 my-6 mx-auto">
        <div class="flex mb-4 items-center">
          <h1 class="text-2xl">{{ $strings.HeaderStatsRecentSessions }}</h1>
          <div class="flex-grow" />
        </div>
        <p v-if="!mostRecentListeningSessions.length">{{ $strings.MessageNoListeningSessions }}</p>
        <template v-for="(item, index) in mostRecentListeningSessions">
          <div :key="item.id" class="w-full py-0.5">
            <div class="flex items-center mb-1">
              <p class="text-sm text-on-surface-variant w-8 min-w-8">{{ index + 1 }}.&nbsp;</p>
              <div class="w-56">
                <p class="text-sm text-on-surface truncate">{{ item.mediaMetadata ? item.mediaMetadata.title : '' }}</p>
                <p class="text-xs text-on-surface-variant">{{ $dateDistanceFromNow(item.updatedAt) }}</p>
              </div>
              <div class="w-16 min-w-16 text-right">
                <p class="text-xs font-bold">{{ $elapsedPretty(item.timeListening) }}</p>
              </div>
            </div>
          </div>
        </template>
      </div>
    </div>

    <!-- Year in review banner shown at the bottom Feb - Nov -->
    <stats-year-in-review-banner v-if="!showYearInReviewBanner" />
  </div>
</template>

<script>
export default {
  data() {
    return {
      listeningStats: null,
      windowWidth: 0,
      showYearInReviewBanner: false
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
    },
    contentPaddingStyle() {
      return this.$store.getters['getIsPlayerOpen'] ? { paddingBottom: '120px' } : {}
    }
  },
  methods: {
    async init() {
      this.listeningStats = await this.$nativeHttp.get(`/api/me/listening-stats`).catch((err) => {
        console.error('Failed to load listening sesions', err)
        return []
      })

      let month = new Date().getMonth()
      // January and December show year in review banner
      if (month === 11 || month === 0) {
        this.showYearInReviewBanner = true
      }
    }
  },
  mounted() {
    this.init()
  }
}
</script>
