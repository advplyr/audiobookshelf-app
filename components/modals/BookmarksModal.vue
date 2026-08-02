<template>
  <modals-modal v-model="show" :width="400" height="100%">
    <template #outer>
      <div class="absolute top-11 left-4 z-40">
        <p class="text-white text-2xl truncate">{{ $strings.LabelYourBookmarks }}</p>
      </div>
    </template>
    <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" @click="show = false">
      <div class="w-full rounded-lg bg-primary border border-border overflow-y-auto overflow-x-hidden relative mt-16" style="max-height: 80vh" @click.stop.prevent>
        <div class="w-full h-full p-4" v-if="showBookmarkTitleInput">
          <div class="flex mb-4 items-center">
            <div class="w-9 h-9 flex items-center justify-center rounded-full hover:bg-white hover:bg-opacity-10 cursor-pointer" @click.stop="showBookmarkTitleInput = false">
              <span class="material-symbols text-3xl">arrow_back</span>
            </div>
            <p class="text-xl pl-2">{{ selectedBookmark ? 'Edit Bookmark' : 'New Bookmark' }}</p>
            <div class="flex-grow" />
            <p class="text-xl font-mono">{{ this.$secondsToTimestamp(currentTime / _playbackRate) }}</p>
          </div>

          <ui-text-input-with-label v-model="newBookmarkTitle" :placeholder="bookmarkPlaceholder()" :autofocus="false" ref="noteInput" label="Note" />
          <div class="flex justify-end mt-6">
            <ui-btn color="success" class="w-full" @click.stop="submitBookmark">{{ selectedBookmark ? 'Update' : 'Create' }}</ui-btn>
          </div>
        </div>
        <div class="w-full h-full" v-else>
          <!-- Multi-select toolbar -->
          <div v-if="selectMode" class="flex items-center justify-between px-4 py-2 bg-secondary border-b border-fg/10">
            <div class="flex items-center">
              <div class="w-9 h-9 flex items-center justify-center rounded-full hover:bg-white hover:bg-opacity-10 cursor-pointer" @click.stop="exitSelectMode">
                <span class="material-symbols text-2xl">close</span>
              </div>
              <p class="text-sm pl-2">{{ selectedBookmarks.length }} {{ $strings.LabelSelected }}</p>
            </div>
            <div class="flex items-center">
              <div class="w-9 h-9 flex items-center justify-center rounded-full hover:bg-white hover:bg-opacity-10 cursor-pointer mr-1" @click.stop="toggleSelectAll">
                <span class="material-symbols text-2xl" :class="allSelected ? 'text-success fill' : 'text-fg-muted'">{{ allSelected ? 'check_circle' : 'select_all' }}</span>
              </div>
              <div v-if="selectedBookmarks.length > 0" class="w-9 h-9 flex items-center justify-center rounded-full hover:bg-white hover:bg-opacity-10 cursor-pointer" @click.stop="deleteSelectedBookmarks">
                <span class="material-symbols text-2xl text-error">delete</span>
              </div>
            </div>
          </div>

          <template v-for="bookmark in bookmarks">
            <modals-bookmarks-bookmark-item :key="bookmark.id" :highlight="currentTime === bookmark.time" :bookmark="bookmark" :playback-rate="_playbackRate" :select-mode="selectMode" :selected="isBookmarkSelected(bookmark)" @click="clickBookmark" @edit="editBookmark" @delete="deleteBookmark" @toggle-select="toggleBookmarkSelect" @long-press="onBookmarkLongPress" />
          </template>
          <div v-if="!bookmarks.length" class="flex h-32 items-center justify-center">
            <p class="text-xl">{{ $strings.MessageNoBookmarks }}</p>
          </div>
        </div>
        <div v-if="canCreateBookmark && !showBookmarkTitleInput && !selectMode" class="flex px-4 py-2 items-center text-center justify-between border-b border-fg/10 bg-success cursor-pointer text-white text-opacity-80 sticky bottom-0 left-0 w-full" @click.stop="createBookmark">
          <span class="material-symbols">add</span>
          <p class="text-base pl-2">{{ $strings.ButtonCreateBookmark }}</p>
          <p class="text-sm font-mono">{{ this.$secondsToTimestamp(currentTime / _playbackRate) }}</p>
        </div>
      </div>
    </div>
  </modals-modal>
</template>

<script>
import { Dialog } from '@capacitor/dialog'

export default {
  props: {
    value: Boolean,
    bookmarks: {
      type: Array,
      default: () => []
    },
    currentTime: {
      type: Number,
      default: 0
    },
    playbackRate: {
      type: Number,
      default: 1
    },
    libraryItemId: String
  },
  data() {
    return {
      selectedBookmark: null,
      showBookmarkTitleInput: false,
      newBookmarkTitle: '',
      selectMode: false,
      selectedBookmarks: []
    }
  },
  watch: {
    show(newVal) {
      if (newVal) {
        this.showBookmarkTitleInput = false
        this.newBookmarkTitle = ''
        this.exitSelectMode()
      }
    }
  },
  computed: {
    show: {
      get() {
        return this.value
      },
      set(val) {
        this.$emit('input', val)
      }
    },
    canCreateBookmark() {
      return !this.bookmarks.find((bm) => bm.time === this.currentTime)
    },
    _playbackRate() {
      if (!this.playbackRate || isNaN(this.playbackRate)) return 1
      return this.playbackRate
    },
    allSelected() {
      return this.bookmarks.length > 0 && this.selectedBookmarks.length === this.bookmarks.length
    }
  },
  methods: {
    bookmarkPlaceholder() {
      // using a method prevents caching the date
      return this.$formatDate(Date.now(), 'MMM dd, yyyy HH:mm')
    },
    editBookmark(bm) {
      this.selectedBookmark = bm
      this.newBookmarkTitle = bm.title
      this.showBookmarkTitleInput = true
    },
    async deleteBookmark(bm) {
      await this.$hapticsImpact()
      const { value } = await Dialog.confirm({
        title: 'Remove Bookmark',
        message: this.$strings.MessageConfirmRemoveBookmark
      })
      if (!value) return

      this.$nativeHttp
        .delete(`/api/me/item/${this.libraryItemId}/bookmark/${bm.time}`)
        .then(() => {
          this.$store.commit('user/deleteBookmark', { libraryItemId: this.libraryItemId, time: bm.time })
        })
        .catch((error) => {
          this.$toast.error(this.$strings.ToastBookmarkRemoveFailed)
          console.error(error)
        })
    },
    async clickBookmark(bm) {
      await this.$hapticsImpact()
      this.$emit('select', bm)
    },
    submitUpdateBookmark(updatedBookmark) {
      this.$nativeHttp
        .patch(`/api/me/item/${this.libraryItemId}/bookmark`, updatedBookmark)
        .then((bookmark) => {
          this.$store.commit('user/updateBookmark', bookmark)
          this.showBookmarkTitleInput = false
        })
        .catch((error) => {
          this.$toast.error(this.$strings.ToastBookmarkUpdateFailed)
          console.error(error)
        })
    },
    submitCreateBookmark() {
      if (!this.newBookmarkTitle) {
        this.newBookmarkTitle = this.$formatDate(Date.now(), 'MMM dd, yyyy HH:mm')
      }
      const bookmark = {
        title: this.newBookmarkTitle,
        time: Math.floor(this.currentTime)
      }
      this.$nativeHttp
        .post(`/api/me/item/${this.libraryItemId}/bookmark`, bookmark)
        .then(() => {
          this.$toast.success('Bookmark added')
        })
        .catch((error) => {
          this.$toast.error(this.$strings.ToastBookmarkCreateFailed)
          console.error(error)
        })

      this.newBookmarkTitle = ''
      this.showBookmarkTitleInput = false

      this.show = false
    },
    createBookmark() {
      this.selectedBookmark = null
      this.newBookmarkTitle = ''
      this.showBookmarkTitleInput = true
    },
    async submitBookmark() {
      await this.$hapticsImpact()
      if (this.selectedBookmark) {
        var updatePayload = {
          ...this.selectedBookmark,
          title: this.newBookmarkTitle
        }
        this.submitUpdateBookmark(updatePayload)
      } else {
        this.submitCreateBookmark()
      }
    },
    // Multi-select methods
    isBookmarkSelected(bookmark) {
      return this.selectedBookmarks.some((bm) => bm.libraryItemId === bookmark.libraryItemId && bm.time === bookmark.time)
    },
    toggleBookmarkSelect(bookmark) {
      if (this.isBookmarkSelected(bookmark)) {
        this.selectedBookmarks = this.selectedBookmarks.filter((bm) => !(bm.libraryItemId === bookmark.libraryItemId && bm.time === bookmark.time))
      } else {
        this.selectedBookmarks.push(bookmark)
      }
      // Exit select mode if nothing is selected
      if (this.selectedBookmarks.length === 0) {
        this.selectMode = false
      }
    },
    toggleSelectAll() {
      if (this.allSelected) {
        this.selectedBookmarks = []
        this.selectMode = false
      } else {
        this.selectedBookmarks = [...this.bookmarks]
      }
    },
    async onBookmarkLongPress(bookmark) {
      await this.$hapticsImpact()
      if (!this.selectMode) {
        this.selectMode = true
        this.selectedBookmarks = [bookmark]
      }
    },
    exitSelectMode() {
      this.selectMode = false
      this.selectedBookmarks = []
    },
    async deleteSelectedBookmarks() {
      await this.$hapticsImpact()
      const count = this.selectedBookmarks.length
      const { value } = await Dialog.confirm({
        title: this.$strings.HeaderConfirmRemoveBookmarks,
        message: this.$getString('MessageConfirmRemoveBookmarks', [count])
      })
      if (!value) return

      const bookmarksToDelete = [...this.selectedBookmarks]
      let failCount = 0

      for (const bm of bookmarksToDelete) {
        try {
          await this.$nativeHttp.delete(`/api/me/item/${this.libraryItemId}/bookmark/${bm.time}`)
          this.$store.commit('user/deleteBookmark', { libraryItemId: this.libraryItemId, time: bm.time })
        } catch (error) {
          failCount++
          console.error('Failed to delete bookmark', error)
        }
      }

      if (failCount > 0) {
        this.$toast.error(this.$getString('ToastBookmarkRemoveSelectedFailed', [failCount]))
      } else {
        this.$toast.success(this.$getString('ToastBookmarkRemoveSelectedSuccess', [count]))
      }

      this.exitSelectMode()
    }
  },
  mounted() {}
}
</script>
