<template>
  <div class="w-full h-full py-6 px-4 overflow-y-auto">
    <p class="mb-2 text-base text-fg">{{ $strings.HeaderDownloads }} ({{ localLibraryItems.length }})</p>

    <div class="w-full">
      <template v-for="(mediaItem, num) in localLibraryItems">
        <div :key="mediaItem.id" class="w-full">
          <nuxt-link :to="`/localMedia/item/${mediaItem.id}`" class="flex items-center">
            <div class="w-16 h-16 min-w-16 min-h-16 flex-none bg-primary relative">
              <img v-if="mediaItem.coverPathSrc" :src="mediaItem.coverPathSrc" class="w-full h-full object-contain" />
            </div>
            <div class="px-2 flex-grow">
              <p class="text-sm">{{ mediaItem.media.metadata.title }}</p>
              <p v-if="mediaItem.mediaType == 'book'" class="text-xs text-fg-muted">{{ mediaItem.media.tracks.length }} {{ $strings.LabelTracks }}</p>
              <p v-else-if="mediaItem.mediaType == 'podcast'" class="text-xs text-fg-muted">{{ mediaItem.media.episodes.length }} {{ $strings.HeaderEpisodes }}</p>
              <p v-if="mediaItem.size" class="text-xs text-fg-muted">{{ $bytesPretty(mediaItem.size) }}</p>
            </div>
            <div class="w-12 h-12 flex items-center justify-center">
              <span class="material-icons text-2xl text-fg-muted">chevron_right</span>
            </div>
          </nuxt-link>
          <div v-if="num + 1 < localLibraryItems.length" class="flex border-t border-fg/10 my-3" />
        </div>
      </template>
    </div>
    <div v-if="localLibraryItems.length" class="mt-4 text-sm text-fg-muted">
      {{ $strings.LabelTotalSize }}: {{ $bytesPretty(localLibraryItems.reduce((acc, item) => acc + item.size, 0)) }}
    </div>
  </div>
</template>

<script>
import { Capacitor } from '@capacitor/core'

export default {
  data() {
    return {
      localLibraryItems: []
    }
  },
  methods: {
    getSize(item) {
      if (!item || !item.localFiles) return 0
      let size = 0
      for (let i = 0; i < item.localFiles.length; i++) {
        size += item.localFiles[i].size
      }
      return size
    },
    newLocalLibraryItem(item) {
      if (!item) return
      const itemIndex = this.localLibraryItems.findIndex((li) => li.id === item.id)
      const newItemObj = {
        ...item,
        size: this.getSize(item),
        coverPathSrc: item.coverContentUrl ? Capacitor.convertFileSrc(item.coverContentUrl) : null
      }
      if (itemIndex >= 0) {
        this.localLibraryItems.splice(itemIndex, 1, newItemObj)
      } else {
        this.localLibraryItems.push(newItemObj)
      }
    },
    async init() {
      var items = (await this.$db.getLocalLibraryItems()) || []
      this.localLibraryItems = items.map((lmi) => {
        console.log('Local library item', JSON.stringify(lmi))
        return {
          ...lmi,
          size: this.getSize(lmi),
          coverPathSrc: lmi.coverContentUrl ? Capacitor.convertFileSrc(lmi.coverContentUrl) : null
        }
      })
    }
  },
  mounted() {
    this.$eventBus.$on('new-local-library-item', this.newLocalLibraryItem)
    this.init()
  },
  beforeDestroy() {
    this.$eventBus.$off('new-local-library-item', this.newLocalLibraryItem)
  }
}
</script>

