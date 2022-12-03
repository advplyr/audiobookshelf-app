<template>
  <div class="w-full h-full">
    <div class="w-full h-full overflow-y-auto px-2 py-6 md:p-8">
      <div class="w-full flex justify-center md:block sm:w-32 md:w-52" style="min-width: 240px">
        <div class="relative" style="height: fit-content">
          <covers-playlist-cover :items="playlistItems" :width="240" :height="120 * bookCoverAspectRatio" :book-cover-aspect-ratio="bookCoverAspectRatio" />
        </div>
      </div>
      <div class="flex-grow py-6">
        <div class="flex items-center px-2">
          <h1 class="text-xl font-sans">
            {{ playlistName }}
          </h1>
          <div class="flex-grow" />
          <ui-btn v-if="showPlayButton" :disabled="streaming" color="success" :padding-x="4" small class="flex items-center justify-center text-center h-9 mr-2 w-24" @click="clickPlay">
            <span v-show="!streaming" class="material-icons -ml-2 pr-1 text-white">play_arrow</span>
            {{ streaming ? 'Streaming' : 'Play' }}
          </ui-btn>
        </div>

        <div class="my-8 max-w-2xl px-2">
          <p class="text-base text-gray-100">{{ description }}</p>
        </div>

        <tables-playlist-items-table :items="playlistItems" :playlist-id="playlist.id" />
      </div>
    </div>
  </div>
</template>

<script>
export default {
  async asyncData({ store, params, app, redirect, route }) {
    if (!store.state.user.user) {
      return redirect(`/connect?redirect=${route.path}`)
    }

    const playlist = await app.$axios.$get(`/api/playlists/${params.id}`).catch((error) => {
      console.error('Failed', error)
      return false
    })

    if (!playlist) {
      return redirect('/bookshelf/playlists')
    }

    return {
      playlist
    }
  },
  data() {
    return {}
  },
  computed: {
    bookCoverAspectRatio() {
      return this.$store.getters['libraries/getBookCoverAspectRatio']
    },
    playlistItems() {
      return this.playlist.items || []
    },
    playlistName() {
      return this.playlist.name || ''
    },
    description() {
      return this.playlist.description || ''
    },
    playableItems() {
      return this.playlistItems.filter((item) => {
        const libraryItem = item.libraryItem
        if (libraryItem.isMissing || libraryItem.isInvalid) return false
        if (item.episode) return item.episode.audioFile
        return libraryItem.media.tracks.length
      })
    },
    streaming() {
      return !!this.playableItems.find((i) => this.$store.getters['getIsMediaStreaming'](i.libraryItemId, i.episodeId))
    },
    showPlayButton() {
      return this.playableItems.length
    }
  },
  methods: {
    clickPlay() {
      const nextItem = this.playableItems.find((i) => {
        var prog = this.$store.getters['user/getUserMediaProgress'](i.libraryItemId, i.episodeId)
        return !prog || !prog.isFinished
      })
      if (nextItem) {
        this.$eventBus.$emit('play-item', { libraryItemId: nextItem.libraryItemId, episodeId: nextItem.episodeId })
      }
    }
  },
  mounted() {}
}
</script>