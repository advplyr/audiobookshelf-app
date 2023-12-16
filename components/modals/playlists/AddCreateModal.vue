<template>
  <modals-fullscreen-modal v-model="show" :processing="processing">
    <div class="flex items-end justify-between h-24 px-4 pb-2">
      <h1 class="text-lg">{{ $strings.LabelAddToPlaylist }}</h1>
      <button class="flex" @click="show = false">
        <span class="material-icons">close</span>
      </button>
    </div>

    <!-- create new playlist form -->
    <div v-if="showPlaylistNameInput" class="w-full h-full max-h-[calc(100vh-176px)] flex items-center">
      <div class="w-full px-4">
        <div class="flex mb-4 items-center">
          <div class="w-9 h-9 flex items-center justify-center rounded-full cursor-pointer" @click.stop="showPlaylistNameInput = false">
            <span class="material-icons text-3xl">arrow_back</span>
          </div>
          <p class="text-xl pl-2 leading-none">{{ $strings.HeaderNewPlaylist }}</p>
          <div class="flex-grow" />
        </div>

        <ui-text-input-with-label v-model="newPlaylistName" :label="$strings.LabelName" />
        <div class="flex justify-end mt-6">
          <ui-btn color="success" :loading="processing" class="w-full" @click.stop="submitCreatePlaylist">{{ $strings.ButtonCreate }}</ui-btn>
        </div>
      </div>
    </div>

    <!-- playlists list -->
    <div v-if="!showPlaylistNameInput" class="w-full overflow-y-auto overflow-x-hidden h-full max-h-[calc(100vh-176px)]">
      <div class="w-full h-full" v-show="!showPlaylistNameInput">
        <template v-for="playlist in sortedPlaylists">
          <modals-playlists-playlist-row :key="playlist.id" :in-playlist="playlist.isItemIncluded" :playlist="playlist" @click="clickPlaylist" @close="show = false" />
        </template>
        <div v-if="!playlists.length" class="flex h-full items-center justify-center">
          <p class="text-xl">{{ loading ? $strings.MessageLoading : $strings.MessageNoUserPlaylists }}</p>
        </div>
      </div>
    </div>

    <!-- create playlist btn -->
    <div v-if="!showPlaylistNameInput" class="flex items-start justify-between h-20 pt-2 absolute bottom-0 left-0 w-full">
      <ui-btn :loading="processing" color="success" class="w-full h-14 flex items-center justify-center" @click.stop="createPlaylist">
        <p class="text-base">{{ $strings.ButtonCreateNewPlaylist }}</p>
      </ui-btn>
    </div>
  </modals-fullscreen-modal>
</template>

<script>
export default {
  props: {
    libraryItemId: String
  },
  data() {
    return {
      showPlaylistNameInput: false,
      newPlaylistName: '',
      playlists: [],
      processing: false,
      loading: false
    }
  },
  watch: {
    show(newVal) {
      if (newVal) {
        this.setListeners()
        this.showPlaylistNameInput = false
        this.newPlaylistName = ''
        this.loadPlaylists()
      } else {
        this.unsetListeners()
      }
    }
  },
  computed: {
    show: {
      get() {
        return this.$store.state.globals.showPlaylistsAddCreateModal
      },
      set(val) {
        this.$store.commit('globals/setShowPlaylistsAddCreateModal', val)
      }
    },
    currentLibraryId() {
      return this.$store.state.libraries.currentLibraryId
    },
    selectedPlaylistItems() {
      return this.$store.state.globals.selectedPlaylistItems || []
    },
    sortedPlaylists() {
      return this.playlists
        .map((playlist) => {
          const includesItem = !this.selectedPlaylistItems.some((item) => !this.checkIsItemInPlaylist(playlist, item))

          return {
            isItemIncluded: includesItem,
            ...playlist
          }
        })
        .sort((a, b) => (a.isItemIncluded ? -1 : 1))
    }
  },
  methods: {
    checkIsItemInPlaylist(playlist, item) {
      if (item.episode) {
        return playlist.items.some((i) => i.libraryItemId === item.libraryItem.id && i.episodeId === item.episode.id)
      }
      return playlist.items.some((i) => i.libraryItemId === item.libraryItem.id)
    },
    loadPlaylists() {
      this.loading = true
      this.$nativeHttp
        .get(`/api/libraries/${this.currentLibraryId}/playlists`)
        .then((data) => {
          this.playlists = data.results || []
        })
        .catch((error) => {
          console.error('Failed', error)
          this.$toast.error('Failed to load playlists')
        })
        .finally(() => {
          this.loading = false
        })
    },
    async clickPlaylist(playlist) {
      await this.$hapticsImpact()
      if (playlist.isItemIncluded) {
        this.removeFromPlaylist(playlist)
      } else {
        this.addToPlaylist(playlist)
      }
    },
    removeFromPlaylist(playlist) {
      if (!this.selectedPlaylistItems.length) return
      this.processing = true

      const itemObjects = this.selectedPlaylistItems.map((pi) => ({ libraryItemId: pi.libraryItem.id, episodeId: pi.episode ? pi.episode.id : null }))
      this.$nativeHttp
        .post(`/api/playlists/${playlist.id}/batch/remove`, { items: itemObjects })
        .then((updatedPlaylist) => {
          console.log(`Items removed from playlist`, updatedPlaylist)
        })
        .catch((error) => {
          console.error('Failed to remove items from playlist', error)
          this.$toast.error('Failed to remove playlist item(s)')
        })
        .finally(() => {
          this.processing = false
        })
    },
    addToPlaylist(playlist) {
      if (!this.selectedPlaylistItems.length) return
      this.processing = true

      const itemObjects = this.selectedPlaylistItems.map((pi) => ({ libraryItemId: pi.libraryItem.id, episodeId: pi.episode ? pi.episode.id : null }))
      this.$nativeHttp
        .post(`/api/playlists/${playlist.id}/batch/add`, { items: itemObjects })
        .then((updatedPlaylist) => {
          console.log(`Items added to playlist`, updatedPlaylist)
        })
        .catch((error) => {
          console.error('Failed to add items to playlist', error)
          this.$toast.error('Failed to add items to playlist')
        })
        .finally(() => {
          this.processing = false
        })
    },
    createPlaylist() {
      this.newPlaylistName = ''
      this.showPlaylistNameInput = true
    },
    async submitCreatePlaylist() {
      await this.$hapticsImpact()
      if (!this.newPlaylistName || !this.selectedPlaylistItems.length) {
        return
      }
      this.processing = true

      const itemObjects = this.selectedPlaylistItems.map((pi) => ({ libraryItemId: pi.libraryItem.id, episodeId: pi.episode ? pi.episode.id : null }))
      const newPlaylist = {
        items: itemObjects,
        libraryId: this.currentLibraryId,
        name: this.newPlaylistName
      }

      this.$nativeHttp
        .post('/api/playlists', newPlaylist)
        .then((data) => {
          console.log('New playlist created', data)
          this.newPlaylistName = ''
          this.showPlaylistNameInput = false
        })
        .catch((error) => {
          console.error('Failed to create playlist', error)
          this.$toast.error(this.$strings.ToastPlaylistCreateFailed)
        })
        .finally(() => {
          this.processing = false
        })
    },
    playlistAdded(playlist) {
      if (!this.playlists.some((p) => p.id === playlist.id)) {
        this.playlists.push(playlist)
      }
    },
    playlistUpdated(playlist) {
      const index = this.playlists.findIndex((p) => p.id === playlist.id)
      if (index >= 0) {
        this.playlists.splice(index, 1, playlist)
      } else {
        this.playlists.push(playlist)
      }
    },
    playlistRemoved(playlist) {
      this.playlists = this.playlists.filter((p) => p.id !== playlist.id)
    },
    setListeners() {
      this.$socket.$on('playlist_added', this.playlistAdded)
      this.$socket.$on('playlist_updated', this.playlistUpdated)
      this.$socket.$on('playlist_removed', this.playlistRemoved)
    },
    unsetListeners() {
      this.$socket.$off('playlist_added', this.playlistAdded)
      this.$socket.$off('playlist_updated', this.playlistUpdated)
      this.$socket.$off('playlist_removed', this.playlistRemoved)
    }
  },
  mounted() {}
}
</script>
