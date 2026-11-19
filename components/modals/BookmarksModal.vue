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

          <ui-text-input-with-label v-model="newBookmarkTitle" :placeholder="bookmarkPlaceholder()" :autofocus="false" ref="noteInput" label="Title" />
          <label class="block mt-4 text-sm">Description (saved on this device)</label>
          <textarea v-model="newBookmarkDescription" class="w-full mt-1 p-2 rounded bg-bg text-fg border border-border" rows="2" />
          <div class="flex justify-end mt-6">
            <ui-btn color="success" class="w-full" @click.stop="submitBookmark">{{ selectedBookmark ? 'Update' : 'Create' }}</ui-btn>
          </div>
        </div>
        <div class="w-full h-full" v-else>
          <template v-for="bookmark in bookmarks">
            <modals-bookmarks-bookmark-item :key="`${bookmark.libraryItemId}:${bookmark.time}`" :highlight="Math.floor(currentTime) === bookmark.time" :bookmark="bookmark" :playback-rate="_playbackRate" @click="clickBookmark" @edit="editBookmark" @delete="deleteBookmark" />
          </template>
          <div v-for="conflict in conflicts" :key="conflict.id" class="p-3 border-t border-warning text-sm">
            <p>Bookmark sync needs your choice: {{ conflict.bookmark.title }}. {{ conflictMessage(conflict.conflict) }}</p>
            <div class="flex gap-4 mt-2 underline">
              <button @click.stop="resolveConflict(conflict.id, 'server')">Use server version</button>
              <button @click.stop="resolveConflict(conflict.id, 'local')">Keep mine</button>
            </div>
          </div>
          <div v-if="!bookmarks.length && !conflicts.length" class="flex h-32 items-center justify-center">
            <p class="text-xl">{{ $strings.MessageNoBookmarks }}</p>
          </div>
        </div>
        <div v-if="canCreateBookmark && !showBookmarkTitleInput" class="flex px-4 py-2 items-center text-center justify-between border-b border-fg/10 bg-success cursor-pointer text-white text-opacity-80 sticky bottom-0 left-0 w-full" @click.stop="createBookmark">
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
      newBookmarkDescription: ''
    }
  },
  watch: {
    show(newVal) {
      if (newVal) {
        this.showBookmarkTitleInput = false
        this.newBookmarkTitle = ''
        this.newBookmarkDescription = ''
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
      return !this.bookmarks.find((bm) => bm.time === Math.floor(this.currentTime))
    },
    conflicts() {
      return this.$store.state.bookmarks.pending.filter((operation) => operation.bookmark.libraryItemId === this.libraryItemId && operation.conflict)
    },
    _playbackRate() {
      if (!this.playbackRate || isNaN(this.playbackRate)) return 1
      return this.playbackRate
    }
  },
  methods: {
    conflictMessage(reason) {
      return {
        collision: 'Another bookmark exists at this second. Keeping yours will replace it.',
        'possible-duplicate': 'A similar bookmark is within five seconds. Keeping yours will create another.',
        'remote-changed': 'This bookmark changed on another device.',
        'remote-deleted': 'This bookmark was deleted on another device.',
        'server-rejected': 'The server rejected this change.'
      }[reason] || 'The server version differs.'
    },
    bookmarkPlaceholder() {
      // using a method prevents caching the date
      return this.$formatDate(Date.now(), 'MMM dd, yyyy HH:mm')
    },
    editBookmark(bm) {
      this.selectedBookmark = bm
      this.newBookmarkTitle = bm.title
      this.newBookmarkDescription = bm.description || ''
      this.showBookmarkTitleInput = true
    },
    async deleteBookmark(bm) {
      await this.$hapticsImpact()
      const { value } = await Dialog.confirm({
        title: 'Remove Bookmark',
        message: this.$strings.MessageConfirmRemoveBookmark
      })
      if (!value) return

      this.$store.dispatch('bookmarks/change', { type: 'delete', bookmark: bm })
        .catch((error) => {
          this.$toast.error(this.$strings.ToastBookmarkRemoveFailed)
          console.error(error)
        })
    },
    async clickBookmark(bm) {
      await this.$hapticsImpact()
      this.$emit('select', bm)
    },
    resolveConflict(id, choice) {
      this.$store.dispatch('bookmarks/resolveConflict', { id, choice }).catch((error) => {
        this.$toast.error(error.message || 'Could not resolve bookmark conflict')
      })
    },
    submitUpdateBookmark(updatedBookmark) {
      this.$store.dispatch('bookmarks/change', { type: 'update', bookmark: updatedBookmark })
        .then(() => {
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
        libraryItemId: this.libraryItemId,
        title: this.newBookmarkTitle,
        description: this.newBookmarkDescription,
        time: Math.floor(this.currentTime)
      }
      this.$store.dispatch('bookmarks/change', { type: 'create', bookmark })
        .then(() => {
          this.$toast.success('Bookmark added')
          this.newBookmarkTitle = ''
          this.newBookmarkDescription = ''
          this.showBookmarkTitleInput = false
          this.show = false
        })
        .catch((error) => {
          this.$toast.error(this.$strings.ToastBookmarkCreateFailed)
          console.error(error)
        })

    },
    createBookmark() {
      this.selectedBookmark = null
      this.newBookmarkTitle = ''
      this.newBookmarkDescription = ''
      this.showBookmarkTitleInput = true
    },
    async submitBookmark() {
      await this.$hapticsImpact()
      if (this.selectedBookmark) {
        var updatePayload = {
          ...this.selectedBookmark,
          title: this.newBookmarkTitle,
          description: this.newBookmarkDescription
        }
        this.submitUpdateBookmark(updatePayload)
      } else {
        this.submitCreateBookmark()
      }
    }
  },
  mounted() {}
}
</script>
