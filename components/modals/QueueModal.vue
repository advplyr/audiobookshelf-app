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
          <ui-btn small @click="show = false">
            <span class="material-symbols">close</span>
          </ui-btn>
        </div>
      </div>

      <div v-if="queue.length === 0" class="flex flex-col items-center justify-center py-12">
        <span class="material-symbols text-6xl text-fg-muted mb-4">queue_music</span>
        <p class="text-fg-muted text-center">{{ $strings.MessageQueueEmpty }}</p>
      </div>

      <div v-else class="space-y-2">
        <draggable 
          v-model="localQueue" 
          group="queue" 
          @end="onDragEnd"
          class="space-y-2"
        >
          <div 
            v-for="(item, index) in localQueue" 
            :key="item.id"
            class="flex items-center p-3 bg-primary bg-opacity-10 rounded-lg border border-primary border-opacity-30 hover:bg-opacity-20 transition-colors group"
          >
            <div class="flex-shrink-0 mr-3">
              <covers-book-cover 
                v-if="item.coverPath"
                :library-item="{ media: { coverPath: item.coverPath } }"
                :width="40"
                :book-cover-aspect-ratio="1.6"
              />
              <div v-else class="w-10 h-16 bg-primary bg-opacity-20 rounded flex items-center justify-center">
                <span class="material-symbols text-primary">{{ item.episodeId ? 'podcast' : 'book' }}</span>
              </div>
            </div>
            
            <div class="flex-grow min-w-0">
              <h3 class="font-medium truncate">{{ item.title }}</h3>
              <p class="text-sm text-fg-muted truncate">{{ item.author }}</p>
              <p v-if="item.duration" class="text-xs text-fg-muted">{{ $secondsToTimestamp(item.duration) }}</p>
            </div>
            
            <div class="flex items-center space-x-2 opacity-0 group-hover:opacity-100 transition-opacity">
              <ui-icon-btn icon="play_arrow" size="sm" @click="playItem(item)" />
              <ui-icon-btn icon="delete" size="sm" color="error" @click="removeItem(index)" />
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
  props: {
    value: Boolean
  },
  data() {
    return {
      localQueue: []
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
    onDragEnd(evt) {
      if (evt.oldIndex !== evt.newIndex) {
        this.$store.dispatch('moveQueueItem', {
          fromIndex: evt.oldIndex,
          toIndex: evt.newIndex
        })
      }
    },
    removeItem(index) {
      this.$store.dispatch('removeFromQueue', index)
    },
    clearQueue() {
      this.$store.dispatch('clearQueue')
    },
    playItem(item) {
      // Remove from queue and play immediately
      const index = this.localQueue.findIndex(queueItem => queueItem.id === item.id)
      if (index >= 0) {
        this.$store.dispatch('removeFromQueue', index)
        this.$store.dispatch('playQueueItem', item)
        this.show = false
      }
    }
  }
}
</script>