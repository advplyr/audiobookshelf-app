<template>
  <modals-fullscreen-modal v-model="show" :processing="processing">
    <div class="flex items-end justify-between h-24 px-4 pb-2">
      <h1 class="text-lg">{{ $strings.LabelAddToPlaylist }}</h1>
    </div>

    <!-- swipe-to-close wrapper -->
    <div @touchstart.passive="onTouchStart" @touchmove.passive="onTouchMove" @touchend.passive="onTouchEnd">
      <!-- create new playlist form -->
      <div v-if="showPlaylistNameInput" class="w-full h-full max-h-[calc(100vh-176px)] flex items-center" :style="contentPaddingStyle">
        <div class="w-full px-4">
          <div class="flex mb-4 items-center">
            <div class="w-9 h-9 flex items-center justify-center rounded-full cursor-pointer" @click.stop="onBackFromCreate">
              <span class="material-symbols text-3xl text-on-surface">arrow_back</span>
            </div>
            <p class="text-xl pl-2 leading-none">{{ $strings.HeaderNewPlaylist }}</p>
            <div class="flex-grow" />
          </div>

          <ui-text-input-with-label v-model="newPlaylistName" :label="$strings.LabelName" />
          <div class="flex justify-end mt-6">
            <ui-btn color="primary" :loading="processing" class="w-full h-12" @click.stop="submitCreatePlaylist">
              <p class="text-base text-on-primary">{{ $strings.ButtonCreate }}</p>
            </ui-btn>
          </div>
        </div>
      </div>

      <!-- playlists list -->
      <div v-if="!showPlaylistNameInput" class="w-full overflow-y-auto overflow-x-hidden h-full max-h-[calc(100vh-176px)]" :style="contentPaddingStyle">
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
      <div v-if="!showPlaylistNameInput" class="flex flex-col items-stretch gap-2 h-28 pt-2 absolute bottom-0 left-0 w-full px-4 pb-4" :style="playlistButtonStyle">
        <ui-btn :loading="processing" color="primary" class="w-full h-12" @click.stop="createPlaylist">
          <p class="text-base text-on-primary">{{ $strings.ButtonCreateNewPlaylist }}</p>
        </ui-btn>
        <ui-btn variant="text" class="w-full h-12" @click.stop="show = false">{{ $strings.ButtonCancel }}</ui-btn>
      </div>
    </div>
    <!-- end swipe wrapper -->
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
  created() {
    // Handle Android native back to step back inside modal or close it
    this.$eventBus.$on('modal-back', this._onModalBack)
  },
  beforeDestroy() {
    this.$eventBus.$off('modal-back', this._onModalBack)
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
    },
    contentPaddingStyle() {
      return this.$store.getters['getIsPlayerOpen'] ? { paddingBottom: '120px' } : {}
    },
    playlistButtonStyle() {
      return this.$store.getters['getIsPlayerOpen'] ? { bottom: '120px' } : {}
    }
  },
  methods: {
    _onModalBack() {
      if (this.showPlaylistNameInput) {
        this.showPlaylistNameInput = false
        return
      }
      // otherwise close modal
      this.show = false
    },
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
    onBackFromCreate() {
      // return from create playlist view
      this.showPlaylistNameInput = false
    },
    // Simple swipe-to-close: vertical swipe down closes the modal
    onTouchStart(e) {
      if (!e.touches || !e.touches.length) return
      this._swipeStartY = e.touches[0].clientY
      this._swipeActive = true
    },
    onTouchMove(e) {
      if (!this._swipeActive || !e.touches || !e.touches.length) return
      const currentY = e.touches[0].clientY
      const delta = currentY - this._swipeStartY
      // Optionally, we could animate modal based on delta
      if (delta > 120) {
        this._swipeActive = false
        this.show = false
      }
    },
    onTouchEnd() {
      this._swipeActive = false
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
        .then(() => {
          // Close modal after removal
          this.show = false
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
        .then(() => {
          // Close modal after add
          this.show = false
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
          // Close modal after successful creation
          this.show = false
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

