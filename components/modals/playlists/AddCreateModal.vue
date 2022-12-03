<template>
  <modals-modal v-model="show" :width="360" height="100%" :processing="processing">
    <template #outer>
      <div class="absolute top-5 left-4 z-40">
        <p class="text-white text-2xl truncate">Add to Playlist</p>
      </div>
    </template>
    <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" @click="show = false">
      <div ref="container" class="w-full rounded-lg bg-primary border border-white border-opacity-20 overflow-y-auto overflow-x-hidden" style="max-height: 80vh" @click.stop.prevent>
        <div class="w-full h-full p-4" v-show="showPlaylistNameInput">
          <div class="flex mb-4 items-center">
            <div class="w-9 h-9 flex items-center justify-center rounded-full hover:bg-white hover:bg-opacity-10 cursor-pointer" @click.stop="showPlaylistNameInput = false">
              <span class="material-icons text-3xl">arrow_back</span>
            </div>
            <p class="text-xl pl-2">New Playlist</p>
            <div class="flex-grow" />
          </div>

          <ui-text-input-with-label v-model="newPlaylistName" label="Name" />
          <div class="flex justify-end mt-6">
            <ui-btn color="success" :loading="processing" class="w-full" @click.stop="submitCreatePlaylist">Create</ui-btn>
          </div>
        </div>
        <div class="w-full h-full" v-show="!showPlaylistNameInput">
          <template v-for="playlist in sortedPlaylists">
            <modals-playlists-playlist-row :key="playlist.id" :in-playlist="playlist.isItemIncluded" :playlist="playlist" @click="clickPlaylist" />
          </template>
          <div v-if="!playlists.length" class="flex h-32 items-center justify-center">
            <p class="text-xl">{{ loading ? 'Loading..' : 'No Playlists' }}</p>
          </div>
          <ui-btn :loading="processing" color="success" class="w-full flex items-center justify-center" @click.stop="createPlaylist">
            <span class="material-icons text-xl">add</span>
            <p class="text-base pl-2">New Playlist</p>
          </ui-btn>
        </div>
      </div>
    </div>
  </modals-modal>
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
      this.$axios
        .$get(`/api/libraries/${this.currentLibraryId}/playlists`)
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
    clickPlaylist(playlist) {
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
      this.$axios
        .$post(`/api/playlists/${playlist.id}/batch/remove`, { items: itemObjects })
        .then((updatedPlaylist) => {
          console.log(`Items removed from playlist`, updatedPlaylist)
          this.$toast.success('Playlist item(s) removed')
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
      this.$axios
        .$post(`/api/playlists/${playlist.id}/batch/add`, { items: itemObjects })
        .then((updatedPlaylist) => {
          console.log(`Items added to playlist`, updatedPlaylist)
          this.$toast.success('Items added to playlist')
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
    submitCreatePlaylist() {
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

      this.$axios
        .$post('/api/playlists', newPlaylist)
        .then((data) => {
          console.log('New playlist created', data)
          this.$toast.success(`Playlist "${data.name}" created`)
          this.newPlaylistName = ''
          this.showPlaylistNameInput = false
        })
        .catch((error) => {
          console.error('Failed to create playlist', error)
          var errMsg = error.response ? error.response.data || '' : ''
          this.$toast.error(`Failed to create playlist: ${errMsg}`)
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
