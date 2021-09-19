<template>
  <div id="bookshelf" ref="wrapper" class="w-full overflow-y-auto">
    <template v-for="(shelf, index) in groupedBooks">
      <div :key="index" class="border-b border-opacity-10 w-full bookshelfRow py-4 flex justify-around relative">
        <template v-for="audiobook in shelf">
          <cards-book-card :key="audiobook.id" :audiobook="audiobook" :width="cardWidth" :user-progress="userAudiobooks[audiobook.id]" :local-user-progress="localUserAudiobooks[audiobook.id]" />
        </template>
        <div class="bookshelfDivider h-4 w-full absolute bottom-0 left-0 right-0 z-10" />
      </div>
    </template>
    <div v-show="!groupedBooks.length" class="w-full py-16 text-center text-xl">
      <div class="py-4">No Audiobooks</div>
      <ui-btn v-if="hasFilters" @click="clearFilter">Clear Filter</ui-btn>
    </div>
  </div>
</template>

<script>
export default {
  data() {
    return {
      currFilterOrderKey: null,
      groupedBooks: [],
      pageWidth: 0
    }
  },
  computed: {
    cardWidth() {
      return 140
    },
    cardHeight() {
      return this.cardWidth * 2
    },
    filterOrderKey() {
      return this.$store.getters['user/getFilterOrderKey']
    },
    hasFilters() {
      return this.$store.getters['user/getUserSetting']('filterBy') !== 'all'
    },
    userAudiobooks() {
      return this.$store.state.user.user ? this.$store.state.user.user.audiobooks || {} : {}
    },
    localUserAudiobooks() {
      return this.$store.state.user.localUserAudiobooks || {}
    }
  },
  methods: {
    clearFilter() {
      this.$store.dispatch('user/updateUserSettings', {
        filterBy: 'all'
      })
    },
    calcShelves() {
      var booksPerShelf = Math.floor(this.pageWidth / (this.cardWidth + 32))
      var groupedBooks = []

      var audiobooksSorted = this.$store.getters['audiobooks/getFilteredAndSorted']()
      this.currFilterOrderKey = this.filterOrderKey

      var numGroups = Math.ceil(audiobooksSorted.length / booksPerShelf)
      for (let i = 0; i < numGroups; i++) {
        var group = audiobooksSorted.slice(i * booksPerShelf, i * booksPerShelf + 2)
        groupedBooks.push(group)
      }
      this.groupedBooks = groupedBooks
    },
    audiobooksUpdated() {
      this.calcShelves()
    },
    init() {
      if (this.$refs.wrapper) {
        this.pageWidth = this.$refs.wrapper.clientWidth
        this.calcShelves()
      }
    },
    resize() {
      this.init()
    },
    settingsUpdated() {
      if (this.currFilterOrderKey !== this.filterOrderKey) {
        this.calcShelves()
      }
    },
    socketConnected(isConnected) {
      if (isConnected) {
        console.log('Connected - Load from server')
        this.$store.dispatch('audiobooks/load')
      } else {
        console.log('Disconnected - Reset to local storage')
        this.$store.commit('audiobooks/reset')
        this.$store.dispatch('audiobooks/useDownloaded')
        // this.calcShelves()
        // this.$store.dispatch('downloads/loadFromStorage')
      }
    }
  },
  mounted() {
    this.$store.commit('audiobooks/addListener', { id: 'bookshelf', meth: this.audiobooksUpdated })
    this.$store.commit('user/addSettingsListener', { id: 'bookshelf', meth: this.settingsUpdated })
    window.addEventListener('resize', this.resize)

    if (!this.$server) {
      console.error('Bookshelf mounted no server')
      return
    }

    this.$server.on('connected', this.socketConnected)
    if (this.$server.connected) {
      this.$store.dispatch('audiobooks/load')
    } else {
      console.log('Bookshelf - Server not connected using downloaded')
    }
    this.init()
  },
  beforeDestroy() {
    this.$store.commit('audiobooks/removeListener', 'bookshelf')
    this.$store.commit('user/removeSettingsListener', 'bookshelf')
    window.removeEventListener('resize', this.resize)

    if (!this.$server) {
      console.error('Bookshelf beforeDestroy no server')
      return
    }
    this.$server.off('connected', this.socketConnected)
  }
}
</script>

<style>
#bookshelf {
  height: calc(100% - 48px);
}
.bookshelfRow {
  background-image: url(/wood_panels.jpg);
}
.bookshelfDivider {
  background: rgb(149, 119, 90);
  background: linear-gradient(180deg, rgba(149, 119, 90, 1) 0%, rgba(103, 70, 37, 1) 17%, rgba(103, 70, 37, 1) 88%, rgba(71, 48, 25, 1) 100%);
  box-shadow: 2px 14px 8px #111111aa;
}
</style>