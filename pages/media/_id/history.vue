<template>
  <div class="w-full h-full px-3 py-4 overflow-y-auto relative bg-bg">
    <p class="mb-4 text-lg font-semibold">History for {{ displayTitle }}</p>

    <div v-if="!mediaEvents.length" class="text-center py-8">
      <p class="text-fg">No History</p>
    </div>

    <div v-for="(events, name) in groupedMediaEvents" :key="name" class="py-2">
      <p class="my-2 text-fg-muted font-semibold">{{ name }}</p>
      <div v-for="(evt, index) in events" :key="index" class="py-3 flex items-center">
        <p class="text-sm text-fg-muted w-12">{{ $formatDate(evt.timestamp, 'HH:mm') }}</p>
        <span class="material-symbols fill px-2" :class="`text-${getEventColor(evt.name)}`">{{ getEventIcon(evt.name) }}</span>
        <p class="text-sm text-fg px-1">{{ evt.name }}</p>

        <span v-if="evt.serverSyncAttempted && evt.serverSyncSuccess" class="material-symbols px-1 text-base text-success">cloud_done</span>
        <span v-if="evt.serverSyncAttempted && !evt.serverSyncSuccess" class="material-symbols px-1 text-base text-error">error_outline</span>

        <p v-if="evt.num" class="text-sm text-fg-muted italic px-1">+{{ evt.num }}</p>

        <div class="flex-grow" />
        <p
          v-if="chapterTitle(evt)"
          class="text-xs text-fg-muted px-2 truncate max-w-xs text-right"
          :title="chapterTitle(evt)"
        >
          {{ chapterTitle(evt) }}
        </p>
        <p class="text-base text-fg" @click="clickPlaybackTime(evt.currentTime)">{{ $secondsToTimestampFull(evt.currentTime) }}</p>
      </div>
    </div>
  </div>
</template>

<script>
import { AbsAudioPlayer } from '@/plugins/capacitor'

const extractChapters = (libraryItem, episodeId) => {
  if (!libraryItem || !libraryItem.media) return []

  if (episodeId && Array.isArray(libraryItem.media.episodes)) {
    const episode = libraryItem.media.episodes.find((ep) => {
      return ep.id === episodeId || ep.episodeId === episodeId || ep.serverEpisodeId === episodeId || ep.localEpisodeId === episodeId
    })
    return (episode && episode.chapters) || []
  }

  return libraryItem.media.chapters || []
}

const fetchHistoryChapters = async ({ db, nativeHttp }, mediaItemHistory) => {
  if (!mediaItemHistory || !mediaItemHistory.libraryItemId) return []

  const libraryItemId = mediaItemHistory.libraryItemId
  const episodeId = mediaItemHistory.episodeId

  if (libraryItemId.startsWith('local_')) {
    const localLibraryItem = await db.getLocalLibraryItem(libraryItemId).catch((error) => {
      console.error('Failed to load local library item for history chapters', error)
      return null
    })
    return extractChapters(localLibraryItem, episodeId)
  }

  const localLibraryItem = await db.getLocalLibraryItemByLId(libraryItemId).catch((error) => {
    console.error('Failed to load local library item for history chapters', error)
    return null
  })
  const localChapters = extractChapters(localLibraryItem, episodeId)
  if (localChapters.length) return localChapters

  const libraryItem = await nativeHttp.get(`/api/items/${libraryItemId}?expanded=1`, { connectTimeout: 5000 }).catch((error) => {
    console.error('Failed to load library item for history chapters', error)
    return null
  })
  return extractChapters(libraryItem, episodeId)
}

export default {
  async asyncData({ params, store, redirect, app, query }) {
    const mediaItemHistory = await app.$db.getMediaItemHistory(params.id)

    return {
      title: query.title || 'Unknown',
      mediaItemHistory
    }
  },
  data() {
    return {
      onMediaItemHistoryUpdatedListener: null,
      historyChapters: []
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
      let lastSaveName = null

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
          let saveName = evt.name + '-' + evt.serverSyncAttempted + '-' + evt.serverSyncSuccess
          if (lastSaveName === saveName && numSaves > 0 && !keyUpdated) {
            include = false
            const totalInGroup = groups[key].length
            groups[key][totalInGroup - 1].num = numSaves
            numSaves++
          } else {
            numSaves = 1
          }
          lastSaveName = saveName
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
    },
    playerIsStartingPlayback() {
      // Play has been pressed and waiting for native play response
      return this.$store.state.playerIsStartingPlayback
    }
  },
  methods: {
    async clickPlaybackTime(time) {
      if (this.playerIsStartingPlayback) return

      await this.$hapticsImpact()
      this.playAtTime(time)
    },
    playAtTime(startTime) {
      this.$store.commit('setPlayerIsStartingPlayback', this.mediaItemEpisodeId || this.mediaItemLibraryItemId)
      // Server may have local
      const localProg = this.$store.getters['globals/getLocalMediaProgressByServerItemId'](this.mediaItemLibraryItemId, this.mediaItemEpisodeId)
      if (localProg) {
        // Has local copy so prefer
        this.$eventBus.$emit('play-item', { libraryItemId: localProg.localLibraryItemId, episodeId: localProg.localEpisodeId, serverLibraryItemId: this.mediaItemLibraryItemId, serverEpisodeId: this.mediaItemEpisodeId, startTime })
      } else {
        // Only on server
        this.$eventBus.$emit('play-item', { libraryItemId: this.mediaItemLibraryItemId, episodeId: this.mediaItemEpisodeId, startTime })
      }
    },
    chapterTitle(evt) {
      if (!evt || evt.currentTime == null) return null
      const chapters = this.historyChapters || []
      if (!chapters.length) return null
      const time = Number(evt.currentTime)

      // Find chapter where start <= time < end; if no end match, use last with start <= time
      const chapter =
        chapters.find((ch) => Number(ch.start) <= time && (ch.end == null || time < Number(ch.end))) ||
        [...chapters].reverse().find((ch) => Number(ch.start) <= time)

      return chapter && chapter.title ? chapter.title : null
    },
    getEventIcon(name) {
      switch (name) {
        case 'Play':
          return 'play_circle'
        case 'Pause':
          return 'pause_circle'
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
      if (!this.historyChapters.length) {
        this.refreshHistoryChapters()
      }
    },
    async refreshHistoryChapters() {
      this.historyChapters = await fetchHistoryChapters({ db: this.$db, nativeHttp: this.$nativeHttp }, this.mediaItemHistory)
    }
  },
  async mounted() {
    this.onMediaItemHistoryUpdatedListener = await AbsAudioPlayer.addListener('onMediaItemHistoryUpdated', this.onMediaItemHistoryUpdated)
    this.refreshHistoryChapters()
  },
  beforeDestroy() {
    this.onMediaItemHistoryUpdatedListener?.remove()
  }
}
</script>
