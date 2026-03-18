<template>
  <modals-modal v-model="show" name="queue" :width="400" :height="500">
    <template #outer>
      <div class="absolute top-0 left-0 p-5 w-2/3 overflow-hidden">
        <p class="text-3xl text-white truncate">{{ $strings.LabelQueue }}</p>
      </div>
    </template>

    <div class="p-4 w-full text-sm py-6 rounded-lg bg-bg shadow-lg border border-black-300 relative overflow-hidden" style="min-height: 400px; max-height: 80vh">
      <div class="flex items-center justify-between mb-4">
        <h2 class="text-lg font-semibold">{{ $strings.LabelQueue }}</h2>
        <div class="flex items-center space-x-2">
          <ui-btn v-if="queue.length > 0" small color="error" @click="clearQueue">{{ $strings.ButtonClearQueue }}</ui-btn>
        </div>
      </div>

      <div v-if="queue.length === 0" class="flex flex-col items-center justify-center py-12">
        <span class="material-symbols text-6xl text-fg-muted mb-4">playlist_play</span>
        <p class="text-fg-muted text-center">{{ $strings.MessageQueueEmpty }}</p>
      </div>

      <div v-else class="space-y-2">
        <draggable
          v-model="localQueue"
          group="queue"
          @end="onDragEnd"
          class="space-y-2"
          handle=".drag-handle"
        >
          <div
            v-for="(item, index) in localQueue"
            :key="item.id"
            class="flex items-center p-3 bg-primary bg-opacity-10 rounded-lg border border-primary border-opacity-30 hover:bg-opacity-20 transition-colors group"
          >
            <!-- Drag handle area -->
            <div class="drag-handle flex-shrink-0 mr-3 cursor-move">
              <covers-preview-cover
                v-if="getCoverSrc(item)"
                :src="getCoverSrc(item)"
                :width="40"
                :book-cover-aspect-ratio="1.6"
                :show-resolution="false"
              />
              <div v-else class="w-10 h-16 bg-primary bg-opacity-20 rounded flex items-center justify-center">
                <span class="material-symbols text-primary">{{ item.episodeId ? 'podcast' : 'book' }}</span>
              </div>
            </div>

            <!-- Content area - also draggable -->
            <div class="drag-handle flex-grow min-w-0 cursor-move">
              <h3 class="font-medium truncate">{{ item.title }}</h3>
              <p class="text-sm text-fg-muted truncate">{{ item.author }}</p>
              <p v-if="item.duration" class="text-xs text-fg-muted">{{ $secondsToTimestamp(item.duration) }}</p>
            </div>

            <!-- Buttons area - NOT draggable -->
            <div class="flex items-center justify-end gap-3 flex-shrink-0" style="pointer-events: auto; position: relative; z-index: 100; min-width: 80px;">
              <!-- Play Button -->
              <div style="pointer-events: auto; position: relative; z-index: 101;">
                <button
                  class="play-btn rounded-md flex items-center justify-center h-8 w-8 bg-primary border border-gray-600 hover:bg-opacity-80 transition-colors"
                  style="pointer-events: auto; position: relative; z-index: 102;"
                  @click.stop.prevent="playItem(item)"
                  @touchstart.stop.prevent="playItem(item)"
                  type="button"
                >
                  <span class="material-symbols text-lg text-white" style="pointer-events: none;">play_arrow</span>
                </button>
              </div>

              <!-- Delete Button -->
              <div style="pointer-events: auto; position: relative; z-index: 101;">
                <button
                  class="delete-btn rounded-md flex items-center justify-center h-8 w-8 bg-red-600 border border-red-700 hover:bg-opacity-80 transition-colors"
                  style="pointer-events: auto; position: relative; z-index: 102;"
                  @click.stop.prevent="removeItem(index)"
                  @touchstart.stop.prevent="removeItem(index)"
                  type="button"
                >
                  <span class="material-symbols text-lg text-white" style="pointer-events: none;">delete</span>
                </button>
              </div>
            </div>
          </div>
        </draggable>
      </div>
    </div>
  </modals-modal>
</template>

<script>
import draggable from 'vuedraggable'

export default {
  components: {
    draggable
  },
  data() {
    return {
      localQueue: []
    }
  },
  computed: {
    show: {
      get() {
        return this.$store.state.globals.showQueueModal
      },
      set(val) {
        this.$store.commit('globals/setShowQueueModal', val)
      }
    },
    queue() {
      return this.$store.getters.getPlaybackQueue
    }
  },
  watch: {
    queue: {
      immediate: true,
      handler(newQueue) {
        this.localQueue = [...newQueue]
      }
    },
    show(newVal) {
      if (newVal) {
        this.localQueue = [...this.queue]
      }
    }
  },
  methods: {
    getCoverSrc(item) {
      // For episodes and audiobooks, use the library item cover
      if (item.libraryItem) {
        // Use the global getter for library item covers
        if (item.isLocal) {
          // For local items, check if we have a direct cover path
          return item.libraryItem.media?.coverPath || null
        } else {
          // For server items, use the store getter
          return this.$store.getters['globals/getLibraryItemCoverSrcById'](item.serverLibraryItemId)
        }
      }

      // Fallback to the coverPath if available
      return item.coverPath || null
    },
    onDragEnd(evt) {
      if (evt.oldIndex !== evt.newIndex) {
        this.$store.dispatch('moveQueueItem', {
          fromIndex: evt.oldIndex,
          toIndex: evt.newIndex
        })
      }
    },
    removeItem(index) {
      // Find the actual index in the store queue by matching IDs
      const item = this.localQueue[index]
      const storeIndex = this.queue.findIndex(queueItem => queueItem.id === item.id)
      if (storeIndex >= 0) {
        this.$store.dispatch('removeFromQueue', storeIndex)
      }
    },
    clearQueue() {
      this.$store.dispatch('clearQueue')
    },
    async playItem(item) {
      // Find the actual index in the store queue by matching IDs
      let storeIndex = this.queue.findIndex(queueItem => queueItem.id === item.id)

      if (storeIndex >= 0) {
        // If something is currently playing, add it to the front of the queue
        const currentSession = this.$store.state.currentPlaybackSession

        if (currentSession) {
          await this.$store.dispatch('addCurrentlyPlayingToQueue')

          // Re-find the index after adding item to front of queue
          storeIndex = this.queue.findIndex(queueItem => queueItem.id === item.id)
        }

        this.$store.dispatch('removeFromQueue', storeIndex)
        this.$store.dispatch('playQueueItem', item)
        this.show = false
      }
    }
  }
}
</script>
