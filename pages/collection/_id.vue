<template>
  <div class="w-full h-full">
    <div class="w-full h-full overflow-y-auto px-2 py-6 md:p-8">
      <div class="w-full flex justify-center md:block sm:w-32 md:w-52" style="min-width: 240px">
        <div class="relative" style="height: fit-content">
          <cards-collection-cover :book-items="bookItems" :width="240" :height="120 * 1.6" />
        </div>
      </div>
      <div class="flex-grow px-2 py-6 md:py-0 md:px-10">
        <div class="flex items-center">
          <h1 class="text-xl font-sans">
            {{ collectionName }}
          </h1>
          <div class="flex-grow" />
          <ui-btn v-if="showPlayButton" :disabled="streaming" color="success" :padding-x="4" small class="flex items-center h-9 mr-2 w-20" @click="clickPlay">
            <span v-show="!streaming" class="material-icons -ml-2 pr-1 text-white">play_arrow</span>
            {{ streaming ? 'Streaming' : 'Play' }}
          </ui-btn>
        </div>

        <!-- <ui-icon-btn icon="edit" class="mx-0.5" @click="editClick" />

            <ui-icon-btn icon="delete" class="mx-0.5" @click="removeClick" /> -->

        <div class="my-8 max-w-2xl">
          <p class="text-base text-gray-100">{{ description }}</p>
        </div>

        <tables-collection-books-table :books="bookItems" :collection-id="collection.id" />
      </div>
    </div>
    <div v-show="processingRemove" class="absolute top-0 left-0 w-full h-full z-10 bg-black bg-opacity-40 flex items-center justify-center">
      <ui-loading-indicator />
    </div>
  </div>
</template>

<script>
export default {
  async asyncData({ store, params, app, redirect, route }) {
    if (!store.state.user.user) {
      return redirect(`/connect?redirect=${route.path}`)
    }

    var collection = await app.$axios.$get(`/api/collection/${params.id}`).catch((error) => {
      console.error('Failed', error)
      return false
    })

    if (!collection) {
      return redirect('/')
    }

    store.commit('user/addUpdateCollection', collection)
    collection.books.forEach((book) => {
      store.commit('audiobooks/addUpdate', book)
    })
    return {
      collectionId: collection.id
    }
  },
  data() {
    return {
      processingRemove: false
    }
  },
  computed: {
    bookItems() {
      return this.collection.books || []
    },
    collectionName() {
      return this.collection.name || ''
    },
    description() {
      return this.collection.description || ''
    },
    collection() {
      return this.$store.getters['user/getCollection'](this.collectionId)
    },
    playableBooks() {
      return this.bookItems.filter((book) => {
        return !book.isMissing && !book.isIncomplete && book.numTracks
      })
    },
    streaming() {
      return !!this.playableBooks.find((b) => b.id === this.$store.getters['getAudiobookIdStreaming'])
    },
    showPlayButton() {
      return this.playableBooks.length
    },
    userAudiobooks() {
      return this.$store.state.user.user ? this.$store.state.user.user.audiobooks || {} : {}
    }
  },
  methods: {
    clickPlay() {
      var nextBookNotRead = this.playableBooks.find((pb) => !this.userAudiobooks[pb.id] || !this.userAudiobooks[pb.id].isRead)
      if (nextBookNotRead) {
        var dlObj = this.$store.getters['downloads/getDownload'](nextBookNotRead.id)

        this.$store.commit('setPlayOnLoad', true)
        if (dlObj && !dlObj.isDownloading && !dlObj.isPreparing) {
          // Local
          console.log('[PLAYCLICK] Set Playing Local Download ' + nextBookNotRead.book.title)
          this.$store.commit('setPlayingDownload', dlObj)
        } else {
          // Stream
          console.log('[PLAYCLICK] Set Playing STREAM ' + nextBookNotRead.book.title)
          this.$store.commit('setStreamAudiobook', nextBookNotRead)
          this.$server.socket.emit('open_stream', nextBookNotRead.id)
        }
      }
    }
  },
  mounted() {}
}
</script>