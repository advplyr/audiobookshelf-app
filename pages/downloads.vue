<template>
  <div class="w-full h-full py-6 px-4 overflow-y-auto">
    <div class="flex items-center justify-between mb-2">
      <p class="text-base text-fg">{{ $strings.HeaderDownloads }} ({{ localLibraryItems.length }})</p>
      <div v-if="selectedItems.length > 0" class="flex items-center gap-2">
        <p class="text-sm text-fg-muted">{{ selectedItems.length }} selected</p>
        <button @click="bulkDelete" class="px-3 py-1 bg-error text-white rounded-md text-sm">
          Delete Selected
        </button>
        <button @click="clearSelection" class="px-3 py-1 bg-bg-secondary text-fg rounded-md text-sm">
          Cancel
        </button>
      </div>
      <button v-else @click="toggleSelectMode" class="px-3 py-1 bg-bg-secondary text-fg rounded-md text-sm">
        Select Multiple
      </button>
    </div>

    <div class="w-full">
      <template v-for="(mediaItem, num) in localLibraryItems">
        <div :key="mediaItem.id" class="w-full">
          <div class="flex items-center" :class="{ 'bg-bg-secondary rounded-lg': selectedItems.includes(mediaItem.id) }">
            <div v-if="selectMode" class="w-10 flex items-center justify-center" @click="toggleSelection(mediaItem.id)">
              <input type="checkbox" :checked="selectedItems.includes(mediaItem.id)" class="w-5 h-5" />
            </div>
            <nuxt-link :to="`/localMedia/item/${mediaItem.id}`" class="flex items-center flex-grow">
              <div class="w-16 h-16 min-w-16 min-h-16 flex-none bg-primary relative">
                <img v-if="mediaItem.coverPathSrc" :src="mediaItem.coverPathSrc" class="w-full h-full object-contain" />
              </div>
              <div class="px-2 flex-grow">
                <p class="text-sm">{{ mediaItem.media.metadata.title }}</p>
                <p v-if="mediaItem.mediaType == 'book'" class="text-xs text-fg-muted">{{ mediaItem.media.tracks.length }} {{ $strings.LabelTracks }}</p>
                <p v-else class="text-xs text-fg-muted">{{ mediaItem.media.episodes.length }} {{ $strings.LabelEpisodes }}</p>
                <p class="text-xs text-fg-muted">{{ $bytesPretty(mediaItem.size) }}</p>
              </div>
            </nuxt-link>
          </div>
          <div v-if="num < localLibraryItems.length - 1" class="w-full h-px bg-border my-2" />
        </div>
      </template>
    </div>
  </div>
</template>

<script>
import { Capacitor } from '@capacitor/core'
import { AbsFileSystem } from '@/plugins/capacitor'

export default {
  data() {
    return {
      localLibraryItems: [],
      selectMode: false,
      selectedItems: []
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
    toggleSelectMode() {
      this.selectMode = !this.selectMode
      if (!this.selectMode) {
        this.selectedItems = []
      }
    },
    toggleSelection(itemId) {
      const index = this.selectedItems.indexOf(itemId)
      if (index >= 0) {
        this.selectedItems.splice(index, 1)
      } else {
        this.selectedItems.push(itemId)
      }
    },
    clearSelection() {
      this.selectedItems = []
      this.selectMode = false
    },
    async bulkDelete() {
      if (this.selectedItems.length === 0) return

      const confirmed = await this.$confirm(
        `Delete ${this.selectedItems.length} item(s)?`,
        'This will permanently delete the selected downloads.'
      )
      if (!confirmed) return

      await this.$hapticsImpact()

      const itemsToDelete = this.selectedItems.map(id => {
        const item = this.localLibraryItems.find(li => li.id === id)
        return {
          id: item.id,
          absolutePath: item.absolutePath,
          contentUrl: item.contentUrl
        }
      })

      try {
        const result = await AbsFileSystem.deleteMultipleItems({ items: itemsToDelete })

        if (result.success) {
          this.$toast.success(`Deleted ${result.deleted} items`)
          // Remove deleted items from local list
          this.localLibraryItems = this.localLibraryItems.filter(item => !this.selectedItems.includes(item.id))
        } else {
          this.$toast.warning(`Deleted ${result.deleted} items, ${result.failed} failed`)
          // Remove successfully deleted items
          const failedIds = result.failedItems ? result.failedItems.split(',') : []
          const deletedIds = this.selectedItems.filter(id => !failedIds.includes(id))
          this.localLibraryItems = this.localLibraryItems.filter(item => !deletedIds.includes(item.id))
        }
      } catch (error) {
        console.error('Bulk delete error:', error)
        this.$toast.error('Failed to delete items')
      }

      this.clearSelection()
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
