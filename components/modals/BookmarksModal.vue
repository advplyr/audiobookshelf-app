<template>
  <modals-modal v-model="show" :width="300" height="100%">
    <template #outer>
      <div class="absolute top-5 left-4 z-40">
        <p class="text-white text-2xl truncate">Bookmarks</p>
      </div>
    </template>
    <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" @click="show = false">
      <div ref="container" class="w-full rounded-lg bg-primary border border-white border-opacity-20 overflow-y-auto overflow-x-hidden" style="max-height: 80vh" @click.stop.prevent>
        <div class="w-full h-full p-4" v-show="showBookmarkTitleInput">
          <div class="flex mb-4 items-center">
            <div class="w-9 h-9 flex items-center justify-center rounded-full hover:bg-white hover:bg-opacity-10 cursor-pointer" @click.stop="showBookmarkTitleInput = false">
              <span class="material-icons text-3xl">arrow_back</span>
            </div>
            <p class="text-xl pl-2">{{ selectedBookmark ? 'Edit Bookmark' : 'New Bookmark' }}</p>
            <div class="flex-grow" />
            <p class="text-xl font-mono">
              {{ this.$secondsToTimestamp(currentTime) }}
            </p>
          </div>

          <ui-text-input-with-label v-model="newBookmarkTitle" label="Note" />
          <div class="flex justify-end mt-6">
            <ui-btn color="success" class="w-full" @click.stop="submitBookmark">{{ selectedBookmark ? 'Update' : 'Create' }}</ui-btn>
          </div>
        </div>
        <div class="w-full h-full" v-show="!showBookmarkTitleInput">
          <template v-for="bookmark in bookmarks">
            <modals-bookmarks-bookmark-item :key="bookmark.id" :highlight="currentTime === bookmark.time" :bookmark="bookmark" @click="clickBookmark" @edit="editBookmark" @delete="deleteBookmark" />
          </template>
          <div v-if="!bookmarks.length" class="flex h-32 items-center justify-center">
            <p class="text-xl">No Bookmarks</p>
          </div>
          <div v-show="canCreateBookmark" class="flex px-4 py-2 items-center text-center justify-between border-b border-white border-opacity-10 bg-blue-500 bg-opacity-20 cursor-pointer text-white text-opacity-80 hover:bg-opacity-40 hover:text-opacity-100" @click.stop="createBookmark">
            <span class="material-icons">add</span>
            <p class="text-base pl-2">Create Bookmark</p>
            <p class="text-sm font-mono">
              {{ this.$secondsToTimestamp(currentTime) }}
            </p>
          </div>
        </div>
      </div>
    </div>
  </modals-modal>
</template>

<script>
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
    audiobookId: String
  },
  data() {
    return {
      selectedBookmark: null,
      showBookmarkTitleInput: false,
      newBookmarkTitle: ''
    }
  },
  watch: {
    show(newVal) {
      if (newVal) {
        this.showBookmarkTitleInput = false
        this.newBookmarkTitle = ''
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
    isConnected() {
      return this.$store.state.socketConnected
    },
    canCreateBookmark() {
      if (!this.isConnected) return false
      return !this.bookmarks.find((bm) => bm.time === this.currentTime)
    }
  },
  methods: {
    editBookmark(bm) {
      this.selectedBookmark = bm
      this.newBookmarkTitle = bm.title
      this.showBookmarkTitleInput = true
    },
    deleteBookmark(bm) {
      var bookmark = { ...bm, audiobookId: this.audiobookId }
      this.$server.socket.emit('delete_bookmark', bookmark)
    },
    clickBookmark(bm) {
      this.$emit('select', bm)
    },
    createBookmark() {
      this.selectedBookmark = null
      this.newBookmarkTitle = this.$formatDate(Date.now(), 'MMM dd, yyyy HH:mm')
      this.showBookmarkTitleInput = true
    },
    submitBookmark() {
      console.log(`[BookmarksModal] Submit Bookmark ${this.newBookmarkTitle}/${this.audiobookId}`)
      if (this.selectedBookmark) {
        if (this.selectedBookmark.title !== this.newBookmarkTitle) {
          var bookmark = { ...this.selectedBookmark }
          bookmark.audiobookId = this.audiobookId
          bookmark.title = this.newBookmarkTitle
          console.log(`[BookmarksModal] Update Bookmark ${JSON.stringify(bookmark)}`)
          this.$server.socket.emit('update_bookmark', bookmark)
        }
      } else {
        var bookmark = {
          audiobookId: this.audiobookId,
          title: this.newBookmarkTitle,
          time: this.currentTime
        }
        console.log(`[BookmarksModal] Create Bookmark ${JSON.stringify(bookmark)}`)
        this.$server.socket.emit('create_bookmark', bookmark)
      }
      this.newBookmarkTitle = ''
      this.showBookmarkTitleInput = false
      this.show = false
    }
  },
  mounted() {}
}
</script>
