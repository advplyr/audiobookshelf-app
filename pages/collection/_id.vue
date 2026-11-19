<template>
  <div class="w-full h-full">
    <div class="w-full h-9 bg-bg relative z-20 flex items-center px-2 border-b border-fg/10">
      <p class="pt-1 text-sm truncate flex-grow">{{ collectionName }} ({{ $formatNumber(filteredBooks.length) }})</p>
      <div class="relative flex items-center px-2">
        <span class="material-symbols text-2xl" @click="showFilterModal = true">filter_alt</span>
        <div v-show="hasFilters" class="absolute top-0 right-2 w-2 h-2 rounded-full bg-success border border-green-300 shadow-sm z-10 pointer-events-none" />
      </div>
      <span class="material-symbols text-2xl px-2" @click="showSortModal = true">sort</span>
    </div>

    <div class="w-full overflow-y-auto px-2 py-6 md:p-8" style="height: calc(100% - 36px)">
      <div class="w-full flex justify-center md:block sm:w-32 md:w-52" style="min-width: 240px">
        <div class="relative" style="height: fit-content">
          <covers-collection-cover :book-items="bookItems" :width="240" :height="120 * bookCoverAspectRatio" :book-cover-aspect-ratio="bookCoverAspectRatio" />
        </div>
      </div>
      <div class="flex-grow py-6">
        <div class="flex items-center px-2">
          <h1 class="text-xl font-sans">
            {{ collectionName }}
          </h1>
          <div class="flex-grow" />
          <ui-btn v-if="showPlayButton" color="success" :padding-x="4" :loading="playerIsStartingForThisMedia" small class="flex items-center justify-center mx-1 w-24" @click="playClick">
            <span class="material-symbols text-2xl fill">{{ playerIsPlaying ? 'pause' : 'play_arrow' }}</span>
            <span class="px-1 text-sm">{{ playerIsPlaying ? $strings.ButtonPause : $strings.ButtonPlay }}</span>
          </ui-btn>
        </div>

        <div class="my-8 max-w-2xl px-2">
          <p class="text-base text-fg">{{ description }}</p>
        </div>

        <div v-if="!filteredBooks.length" class="py-8 text-center text-fg-muted">
          <p class="mb-4">{{ $strings.MessageNoItemsFound }}</p>
          <ui-btn v-if="hasFilters" @click="clearFilter">{{ $strings.ButtonClearFilter }}</ui-btn>
        </div>
        <tables-collection-books-table v-else :books="filteredBooks" :collection-id="collection.id" />
      </div>
    </div>
    <div v-show="processingRemove" class="absolute top-0 left-0 w-full h-full z-10 bg-black bg-opacity-40 flex items-center justify-center">
      <ui-loading-indicator />
    </div>

    <modals-order-modal v-model="showSortModal" :order-by.sync="settings.collectionBooksOrderBy" :descending.sync="settings.collectionBooksOrderDesc" @change="saveSettings" />
    <modals-filter-modal v-model="showFilterModal" :filter-by.sync="settings.collectionFilterBy" filter-context="collections" @change="saveSettings" />
  </div>
</template>

<script>
import { filterAndSortBooks } from '@/utils/bookFilters'

export default {
  async asyncData({ store, params, app, redirect, route }) {
    if (!store.state.user.user) {
      return redirect(`/connect?redirect=${route.path}`)
    }

    var collection = await app.$nativeHttp.get(`/api/collections/${params.id}`).catch((error) => {
      console.error('Failed', error)
      return false
    })

    if (!collection) {
      return redirect('/bookshelf')
    }

    // Lookup matching local items and attach to collection items
    if (collection.books.length) {
      const localLibraryItems = (await app.$db.getLocalLibraryItems('book')) || []
      if (localLibraryItems.length) {
        collection.books.forEach((collectionItem) => {
          const matchingLocalLibraryItem = localLibraryItems.find((lli) => lli.libraryItemId === collectionItem.id)
          if (!matchingLocalLibraryItem) return
          collectionItem.localLibraryItem = matchingLocalLibraryItem
        })
      }
    }

    return {
      collection
    }
  },
  data() {
    return {
      mediaIdStartingPlayback: null,
      processingRemove: false,
      showSortModal: false,
      showFilterModal: false,
      settings: {}
    }
  },
  computed: {
    bookCoverAspectRatio() {
      return this.$store.getters['libraries/getBookCoverAspectRatio']
    },
    bookItems() {
      return this.collection.books || []
    },
    filterData() {
      return this.$store.state.libraries.filterData || {}
    },
    hasFilters() {
      return (this.settings.collectionFilterBy || 'all') !== 'all'
    },
    filteredBooks() {
      const getProgress = (id) => this.$store.getters['user/getUserMediaProgress'](id)
      return filterAndSortBooks(
        this.bookItems,
        this.settings.collectionFilterBy || 'all',
        this.settings.collectionBooksOrderBy || 'media.metadata.title',
        !!this.settings.collectionBooksOrderDesc,
        this.$decode,
        getProgress,
        this.filterData
      )
    },
    collectionName() {
      return this.collection.name || ''
    },
    description() {
      return this.collection.description || ''
    },
    playableItems() {
      return this.filteredBooks.filter((book) => {
        return !book.isMissing && !book.isInvalid && book.media?.tracks?.length
      })
    },
    playerIsPlaying() {
      return this.$store.state.playerIsPlaying && this.isOpenInPlayer
    },
    isOpenInPlayer() {
      return !!this.playableItems.find((i) => {
        if (i.localLibraryItem && this.$store.getters['getIsMediaStreaming'](i.localLibraryItem.id)) return true
        return this.$store.getters['getIsMediaStreaming'](i.id)
      })
    },
    playerIsStartingPlayback() {
      // Play has been pressed and waiting for native play response
      return this.$store.state.playerIsStartingPlayback
    },
    playerIsStartingForThisMedia() {
      if (!this.mediaIdStartingPlayback) return false
      const mediaId = this.$store.state.playerStartingPlaybackMediaId
      return mediaId === this.mediaIdStartingPlayback
    },
    showPlayButton() {
      return this.playableItems.length
    }
  },
  methods: {
    saveSettings() {
      this.$store.dispatch('user/updateUserSettings', this.settings)
    },
    clearFilter() {
      this.settings.collectionFilterBy = 'all'
      this.saveSettings()
    },
    settingsUpdated(settings) {
      for (const key in settings) {
        this.settings[key] = settings[key]
      }
    },
    async playClick() {
      if (this.playerIsStartingPlayback) return
      await this.$hapticsImpact()

      if (this.playerIsPlaying) {
        this.$eventBus.$emit('pause-item')
      } else {
        this.playNextItem()
      }
    },
    playNextItem() {
      const nextBookNotRead = this.playableItems.find((pb) => {
        const prog = this.$store.getters['user/getUserMediaProgress'](pb.id)
        return !prog?.isFinished
      })
      if (nextBookNotRead) {
        this.mediaIdStartingPlayback = nextBookNotRead.id
        this.$store.commit('setPlayerIsStartingPlayback', nextBookNotRead.id)

        if (nextBookNotRead.localLibraryItem) {
          this.$eventBus.$emit('play-item', { libraryItemId: nextBookNotRead.localLibraryItem.id, serverLibraryItemId: nextBookNotRead.id })
        } else {
          this.$eventBus.$emit('play-item', { libraryItemId: nextBookNotRead.id })
        }
      }
    },
    libraryChanged(libraryId) {
      // A collection belongs to a single library, so leave this page when a different library is
      // selected rather than showing a collection that is not in the current library
      if (!libraryId || libraryId !== this.collection.libraryId) {
        this.$router.replace('/bookshelf/collections')
      }
    }
  },
  mounted() {
    this.settings = { ...this.$store.state.user.settings }
    this.$eventBus.$on('library-changed', this.libraryChanged)
    this.$eventBus.$on('user-settings', this.settingsUpdated)
  },
  beforeDestroy() {
    this.$eventBus.$off('library-changed', this.libraryChanged)
    this.$eventBus.$off('user-settings', this.settingsUpdated)
  }
}
</script>
