<template>
  <div class="w-full h-full px-3 py-4 overflow-y-auto relative bg-bg">
    <p class="mb-4 text-lg font-semibold">History for {{ displayTitle }}</p>

    <div v-if="!mediaEvents.length" class="text-center py-8">
      <p class="text-gray-200">No History</p>
    </div>

    <div v-for="(events, name) in groupedMediaEvents" :key="name" class="py-2">
      <p class="my-2 text-gray-400 font-semibold">{{ name }}</p>
      <div v-for="(evt, index) in events" :key="index" class="py-3 flex items-center">
        <p class="text-sm text-gray-400 w-12">{{ $formatDate(evt.timestamp, 'HH:mm') }}</p>
        <span class="material-icons px-1" :class="`text-${getEventColor(evt.name)}`">{{ getEventIcon(evt.name) }}</span>
        <p class="text-sm text-white px-1">{{ evt.name }}</p>

        <span v-if="evt.serverSyncAttempted && evt.serverSyncSuccess" class="material-icons-outlined px-1 text-base text-success">cloud_done</span>
        <span v-if="evt.serverSyncAttempted && !evt.serverSyncSuccess" class="material-icons px-1 text-base text-error">error_outline</span>

        <p v-if="evt.num" class="text-sm text-gray-400 italic px-1">+{{ evt.num }}</p>

        <div class="flex-grow" />
        <p class="text-base text-white" @click="clickPlaybackTime(evt.currentTime)">{{ $secondsToTimestampFull(evt.currentTime) }}</p>
      </div>
    </div>
  </div>
</template>

<script>
import { AbsAudioPlayer } from '@/plugins/capacitor'

export default {
  async asyncData({ params, store, redirect, app, query }) {
    const mediaItemHistory = await app.$db.getMediaItemHistory(params.id)

    if (!mediaItemHistory) {
      return redirect('/?error=Media Item Not Found')
    }

    return {
      title: query.title || 'Unknown',
      mediaItemHistory
    }
  },
  data() {
    return {
      onMediaItemHistoryUpdatedListener: null,
      startingPlayback: false
    }
  },
  computed: {
    displayTitle() {
      if (!this.mediaItemHistory) return this.title
      return this.mediaItemHistory.mediaDisplayTitle
    },
    mediaEvents() {
      if (!this.mediaItemHistory) return []
      return (this.mediaItemHistory.events || []).sort((a, b) => b.timestamp - a.timestamp)
    },
    mediaItemIsLocal() {
      return this.mediaItemHistory && this.mediaItemHistory.isLocal
    },
    mediaItemLibraryItemId() {
      if (!this.mediaItemHistory) return null
      return this.mediaItemHistory.libraryItemId
    },
    mediaItemEpisodeId() {
      if (!this.mediaItemHistory) return null
      return this.mediaItemHistory.episodeId
    },
    groupedMediaEvents() {
      const groups = {}

      const today = this.$formatDate(new Date(), 'MMM dd, yyyy')
      const yesterday = this.$formatDate(Date.now() - 1000 * 60 * 60 * 24, 'MMM dd, yyyy')

      let lastKey = null
      let numSaves = 0
      let numSyncs = 0

      this.mediaEvents.forEach((evt) => {
        const date = this.$formatDate(evt.timestamp, 'MMM dd, yyyy')
        let include = true
        let keyUpdated = false

        let key = date
        if (date === today) key = 'Today'
        else if (date === yesterday) key = 'Yesterday'

        if (!groups[key]) groups[key] = []

        if (!lastKey || lastKey !== key) {
          lastKey = key
          keyUpdated = true
        }

        // Collapse saves
        if (evt.name === 'Save') {
          if (numSaves > 0 && !keyUpdated) {
            include = false
            const totalInGroup = groups[key].length
            groups[key][totalInGroup - 1].num = numSaves
            numSaves++
          } else {
            numSaves = 1
          }
        } else {
          numSaves = 0
        }

        // Collapse syncs
        if (evt.name === 'Sync') {
          if (numSyncs > 0 && !keyUpdated) {
            include = false
            const totalInGroup = groups[key].length
            groups[key][totalInGroup - 1].num = numSyncs
            numSyncs++
          } else {
            numSyncs = 1
          }
        } else {
          numSyncs = 0
        }

        if (include) {
          groups[key].push(evt)
        }
      })

      return groups
    }
  },
  methods: {
    async clickPlaybackTime(time) {
      if (this.startingPlayback) return
      this.startingPlayback = true
      await this.$hapticsImpact()
      console.log('Click playback time', time)
      this.playAtTime(time)

      setTimeout(() => {
        this.startingPlayback = false
      }, 1000)
    },
    playAtTime(startTime) {
      if (this.mediaItemIsLocal) {
        // Local only
        this.$eventBus.$emit('play-item', { libraryItemId: this.mediaItemLibraryItemId, episodeId: this.mediaItemEpisodeId, startTime })
      } else {
        // Server may have local
        const localProg = this.$store.getters['globals/getLocalMediaProgressByServerItemId'](this.mediaItemLibraryItemId, this.mediaItemEpisodeId)
        if (localProg) {
          // Has local copy so prefer
          this.$eventBus.$emit('play-item', { libraryItemId: localProg.localLibraryItemId, episodeId: localProg.localEpisodeId, serverLibraryItemId: this.mediaItemLibraryItemId, serverEpisodeId: this.mediaItemEpisodeId, startTime })
        } else {
          // Only on server
          this.$eventBus.$emit('play-item', { libraryItemId: this.mediaItemLibraryItemId, episodeId: this.mediaItemEpisodeId, startTime })
        }
      }
    },
    getEventIcon(name) {
      switch (name) {
        case 'Play':
          return 'play_circle_filled'
        case 'Pause':
          return 'pause_circle_filled'
        case 'Stop':
          return 'stop_circle'
        case 'Save':
          return 'sync'
        case 'Seek':
          return 'commit'
        case 'Sync':
          return 'cloud_download'
        default:
          return 'info'
      }
    },
    getEventColor(name) {
      switch (name) {
        case 'Play':
          return 'success'
        case 'Pause':
          return 'gray-300'
        case 'Stop':
          return 'error'
        case 'Save':
          return 'info'
        case 'Seek':
          return 'gray-200'
        case 'Sync':
          return 'accent'
        default:
          return 'info'
      }
    },
    onMediaItemHistoryUpdated(mediaItemHistory) {
      if (!mediaItemHistory || !mediaItemHistory.id) {
        console.error('Invalid media item history', mediaItemHistory)
        return
      }
      if (mediaItemHistory.id !== this.mediaItemHistory.id) {
        return
      }
      console.log('Media Item History updated')

      this.mediaItemHistory = mediaItemHistory
    }
  },
  async mounted() {
    this.onMediaItemHistoryUpdatedListener = await AbsAudioPlayer.addListener('onMediaItemHistoryUpdated', this.onMediaItemHistoryUpdated)
  },
  beforeDestroy() {
    if (this.onMediaItemHistoryUpdatedListener) this.onMediaItemHistoryUpdatedListener.remove()
  }
}
</script>