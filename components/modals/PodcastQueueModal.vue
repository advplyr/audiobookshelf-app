<template>
  <modals-modal v-model="show" :width="800" :height="'unset'">
    <template #outer>
      <div class="absolute top-0 left-0 p-5 w-2/3 overflow-hidden">
        <p class="text-3xl text-white truncate">Queue</p>
      </div>
    </template>

    <div class="w-full rounded-lg bg-bg box-shadow-md overflow-y-auto overflow-x-hidden py-4" style="max-height: 80vh">
      <div v-if="show" class="w-full h-full">
        <div class="pb-4 px-4 flex items-center">
          <p class="text-base text-fg">Queue</p>
          <p class="text-base text-fg-muted px-4">{{ queueItems.length }} Items</p>
          <div class="grow" />
        </div>

        <transition-group name="queue" tag="div">
          <div v-for="(item, index) in queueItems" :key="itemKey(item, index)" class="relative overflow-hidden">
            <div class="absolute top-0 right-0 h-full w-60 bg-error/70 pointer-events-none" :style="{ opacity: (offsets[itemKey(item, index)] || 0) < 0 ? 1 : 0, transition: 'opacity 120ms ease' }" />

            <div
              class="w-full flex items-center px-4 py-2 select-none"
              :class="wrapperClass(item, index)"
              :style="rowStyle(item, index)"
              @click.stop="playClick(index)"
              @touchstart.passive="touchStart(item, index, $event)"
              @touchmove="touchMove(item, index, $event)"
              @touchend="touchEnd(item, index)"
              @touchcancel="touchCancel(item, index)"
            >
              <covers-preview-cover :src="coverUrl(item)" :width="48" :book-cover-aspect-ratio="bookCoverAspectRatio" :show-resolution="false" />

              <div class="grow px-2 py-1 truncate" style="max-width: calc(100% - 48px - 112px)">
                <p class="text-gray-200 text-sm truncate">{{ itemTitle(item) }}</p>
              </div>

              <div class="w-24">
                <button class="outline-none w-full flex items-center justify-end" @click.stop="removeClick(item, index)">
                  <span class="material-symbols text-2xl text-error">delete</span>
                </button>
              </div>
            </div>
          </div>
        </transition-group>
      </div>
    </div>
  </modals-modal>
</template>

<script>
export default {
  props: {
    value: Boolean,
    queueItems: {
      type: Array,
      default: () => []
    }
  },
  data() {
    return {
      startX: 0,
      startY: 0,
      swipingKey: null,
      isSwiping: false,
      offsets: {},
      removeToastId: null
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
    bookCoverAspectRatio() {
      return this.$store.getters['libraries/getBookCoverAspectRatio']
    }
  },
  methods: {
    itemKey(item, index) {
      return `${item?.serverLibraryItemId || item?.libraryItemId || 'x'}:${item?.serverEpisodeId || item?.episodeId || 'y'}:${index}`
    },
    isOpenInPlayer(item) {
      if (!item?.libraryItemId) return false
      return this.$store.getters['getIsMediaStreaming'](item.libraryItemId, item.episodeId)
    },
    wrapperClass(item, index) {
      if (this.isOpenInPlayer(item)) return 'bg-yellow-400/10'
      if (index % 2 === 0) return 'bg-gray-300/5 hover:bg-gray-300/10'
      return 'bg-bg hover:bg-gray-300/10'
    },
    rowStyle(item, index) {
      const key = this.itemKey(item, index)
      const x = this.offsets[key] || 0
      const dragging = this.swipingKey === key && this.isSwiping
      return {
        transform: `translate3d(${x}px, 0, 0)`,
        transition: dragging ? 'none' : 'transform 180ms ease'
      }
    },
    itemTitle(item) {
      return item?.queueText || item?.title || item?.episodeTitle || item?.episodeId || item?.serverEpisodeId || 'Item'
    },
    coverUrl(item) {
      const lid = item?.serverLibraryItemId || item?.libraryItemId
      return this.$store.getters['globals/getLibraryItemCoverSrcById'](lid)
    },
    playClick(index) {
      this.$emit('play', index)
    },
    removeClick(item, index) {
      const key = this.itemKey(item, index)

      const name = this.itemTitle(item) || 'Item'
      if (this.removeToastId) this.$toast.dismiss(this.removeToastId)
      this.removeToastId = this.$toast.error(`${name} removed`)

      this.$set(this.offsets, key, -240)
      setTimeout(() => {
        this.$emit('remove', index)
      }, 180)
    },
    touchStart(item, index, evt) {
      const t = evt.touches?.[0]
      if (!t) return
      this.startX = t.clientX
      this.startY = t.clientY
      this.swipingKey = this.itemKey(item, index)
      this.isSwiping = false
    },
    touchMove(item, index, evt) {
      const key = this.itemKey(item, index)
      if (this.swipingKey !== key) return

      const t = evt.touches?.[0]
      if (!t) return
      const dx = t.clientX - this.startX
      const dy = t.clientY - this.startY

      if (!this.isSwiping) {
        if (Math.abs(dx) < 10) return
        if (Math.abs(dy) > Math.abs(dx)) {
          this.swipingKey = null
          return
        }
        this.isSwiping = true
      }

      evt.preventDefault()
      const x = Math.max(-240, Math.min(0, dx))
      this.$set(this.offsets, key, x)
    },
    touchEnd(item, index) {
      const key = this.itemKey(item, index)
      if (this.swipingKey !== key) return

      const x = this.offsets[key] || 0
      this.swipingKey = null

      if (!this.isSwiping) return
      this.isSwiping = false

      if (x <= -160) {
        this.removeClick(item, index)
      } else {
        this.$set(this.offsets, key, 0)
      }
    },
    touchCancel(item, index) {
      const key = this.itemKey(item, index)
      if (this.swipingKey === key) {
        this.swipingKey = null
        this.isSwiping = false
        this.$set(this.offsets, key, 0)
      }
    }
  }
}
</script>

<style scoped>
.queue-enter-active,
.queue-leave-active {
  transition: opacity 180ms ease, transform 180ms ease, max-height 180ms ease;
}
.queue-enter {
  opacity: 0;
  transform: translate3d(0, 6px, 0);
}
.queue-leave-to {
  opacity: 0;
  transform: translate3d(0, -6px, 0);
  max-height: 0;
}
</style>
