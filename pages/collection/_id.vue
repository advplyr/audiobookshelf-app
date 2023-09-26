<template>
  <div class="w-full h-full">
    <div class="w-full h-full overflow-y-auto px-2 py-6 md:p-8">
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
          <ui-btn v-if="showPlayButton" :disabled="streaming" color="success" :padding-x="4" small class="flex items-center justify-center h-9 mr-2 w-24" @click="clickPlay">
            <span v-show="!streaming" class="material-icons -ml-2 pr-1 text-white">play_arrow</span>
            {{ streaming ? $strings.ButtonStreaming : $strings.ButtonPlay }}
          </ui-btn>
        </div>

        <div class="my-8 max-w-2xl px-2">
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

    var collection = await app.$nativeHttp.get(`/api/collections/${params.id}`).catch((error) => {
      console.error('Failed', error)
      return false
    })

    if (!collection) {
      return redirect('/bookshelf')
    }

    return {
      collection
    }
  },
  data() {
    return {
      processingRemove: false
    }
  },
  computed: {
    bookCoverAspectRatio() {
      return this.$store.getters['libraries/getBookCoverAspectRatio']
    },
    bookItems() {
      return this.collection.books || []
    },
    collectionName() {
      return this.collection.name || ''
    },
    description() {
      return this.collection.description || ''
    },
    playableBooks() {
      return this.bookItems.filter((book) => {
        return !book.isMissing && !book.isInvalid && book.media.tracks.length
      })
    },
    streaming() {
      return !!this.playableBooks.find((b) => this.$store.getters['getIsMediaStreaming'](b.id))
    },
    showPlayButton() {
      return this.playableBooks.length
    }
  },
  methods: {
    clickPlay() {
      var nextBookNotRead = this.playableBooks.find((pb) => {
        var prog = this.$store.getters['user/getUserMediaProgress'](pb.id)
        return !prog || !prog.isFinished
      })
      if (nextBookNotRead) {
        this.$eventBus.$emit('play-item', { libraryItemId: nextBookNotRead.id })
      }
    }
  },
  mounted() {}
}
</script>