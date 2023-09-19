<template>
  <div class="w-full h-full">
    <div class="w-full h-full overflow-y-auto px-2 py-6 md:p-8">
      <div class="w-full flex justify-center">
        <covers-playlist-cover :items="playlistItems" :width="180" :height="180" />
      </div>
      <div class="flex-grow py-6">
        <div class="flex items-center px-2">
          <h1 class="text-xl font-sans">
            {{ playlistName }}
          </h1>
          <div class="flex-grow" />
          <ui-btn v-if="showPlayButton" :disabled="streaming" color="success" :padding-x="4" small class="flex items-center justify-center text-center h-9 mr-2 w-24" @click="clickPlay">
            <span v-show="!streaming" class="material-icons -ml-2 pr-1 text-white">play_arrow</span>
            {{ streaming ? $strings.ButtonStreaming : $strings.ButtonPlay }}
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

    // Lookup matching local items & episodes and attach to playlist items
    if (playlist.items.length) {
      const localLibraryItems = (await app.$db.getLocalLibraryItems(playlist.items[0].libraryItem.mediaType)) || []
      if (localLibraryItems.length) {
        playlist.items.forEach((playlistItem) => {
          const matchingLocalLibraryItem = localLibraryItems.find((lli) => lli.libraryItemId === playlistItem.libraryItemId)
          if (!matchingLocalLibraryItem) return
          if (playlistItem.episode) {
            const matchingLocalEpisode = matchingLocalLibraryItem.media.episodes?.find((lep) => lep.serverEpisodeId === playlistItem.episodeId)
            if (matchingLocalEpisode) {
              playlistItem.localLibraryItem = matchingLocalLibraryItem
              playlistItem.localEpisode = matchingLocalEpisode
            }
          } else {
            playlistItem.localLibraryItem = matchingLocalLibraryItem
          }
        })
      }
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
      return !!this.playableItems.find((i) => {
        if (i.localLibraryItem && this.$store.getters['getIsMediaStreaming'](i.localLibraryItem.id, i.localEpisode?.id)) return true
        return this.$store.getters['getIsMediaStreaming'](i.libraryItemId, i.episodeId)
      })
    },
    showPlayButton() {
      return this.playableItems.length
    }
  },
  methods: {
    clickPlay() {
      const nextItem = this.playableItems.find((i) => {
        const prog = this.$store.getters['user/getUserMediaProgress'](i.libraryItemId, i.episodeId)
        return !prog?.isFinished
      })
      if (nextItem) {
        if (nextItem.localLibraryItem) {
          this.$eventBus.$emit('play-item', { libraryItemId: nextItem.localLibraryItem.id, episodeId: nextItem.localEpisode?.id, serverLibraryItemId: nextItem.libraryItemId, serverEpisodeId: nextItem.episodeId })
        } else {
          this.$eventBus.$emit('play-item', { libraryItemId: nextItem.libraryItemId, episodeId: nextItem.episodeId })
        }
      }
    }
  },
  mounted() {}
}
</script>