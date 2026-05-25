<template>
  <div class="w-full h-full py-6 px-4 overflow-y-auto" :class="{ 'pb-24': isSelectionMode }">
    <!-- Header Toolbar -->
    <div class="flex items-center justify-between mb-4 border-b border-fg/10 pb-3">
      <p class="text-base text-fg">{{ $strings.HeaderDownloads }} ({{ localLibraryItems.length }})</p>
      <div class="flex items-center space-x-2">
        <ui-btn v-if="localLibraryItems.length" small :color="isSelectionMode ? 'primary' : 'bg'" @click="toggleSelectionMode">
          {{ isSelectionMode ? $strings.ButtonCancel : 'Select' }}
        </ui-btn>
      </div>
    </div>

    <!-- Sorting Control -->
    <div v-if="localLibraryItems.length && !isSelectionMode" class="flex items-center mb-4 space-x-2">
      <span class="text-xs text-fg-muted">Sort By:</span>
      <div class="w-48">
        <ui-dropdown v-model="sortBy" :items="sortItems" small />
      </div>
    </div>

    <!-- Empty State -->
    <div v-if="!localLibraryItems.length" class="flex flex-col items-center justify-center py-12 text-center">
      <span class="material-symbols text-4xl text-fg-muted mb-2">download</span>
      <p class="text-fg-muted">{{ $strings.MessageNoItems || 'No downloaded items' }}</p>
    </div>

    <!-- List of Local Library Items -->
    <div v-else class="w-full">
      <template v-for="(mediaItem, num) in sortedLibraryItems">
        <div :key="mediaItem.id" class="w-full">
          <div class="flex items-center w-full">
            <!-- Selection Checkbox -->
            <div v-if="isSelectionMode" class="pr-3 flex-none" @click.stop="toggleSelectItem(mediaItem.id)">
              <ui-checkbox :value="selectedItemIds.includes(mediaItem.id)" @input="toggleSelectItem(mediaItem.id)" />
            </div>

            <!-- Item Display -->
            <div v-if="isSelectionMode" @click="toggleSelectItem(mediaItem.id)" class="flex items-center flex-grow cursor-pointer overflow-hidden py-1">
              <div class="w-16 h-16 min-w-16 min-h-16 flex-none bg-primary relative rounded overflow-hidden">
                <img v-if="mediaItem.coverPathSrc" :src="mediaItem.coverPathSrc" class="w-full h-full object-contain" />
              </div>
              <div class="px-3 flex-grow overflow-hidden">
                <p class="text-sm font-medium truncate">{{ mediaItem.media.metadata.title }}</p>
                <p v-if="getSeriesDisplay(mediaItem)" class="text-xs text-success truncate font-semibold">
                  {{ getSeriesDisplay(mediaItem) }}
                </p>
                <p v-if="mediaItem.mediaType == 'book'" class="text-xs text-fg-muted">{{ mediaItem.media.tracks.length }} {{ $strings.LabelTracks }}</p>
                <p v-else-if="mediaItem.mediaType == 'podcast'" class="text-xs text-fg-muted">{{ mediaItem.media.episodes.length }} {{ $strings.HeaderEpisodes }}</p>
                <p v-if="mediaItem.size" class="text-xs text-fg-muted font-mono">{{ $bytesPretty(mediaItem.size) }}</p>
              </div>
            </div>

            <nuxt-link v-else :to="`/localMedia/item/${mediaItem.id}`" class="flex items-center flex-grow overflow-hidden py-1">
              <div class="w-16 h-16 min-w-16 min-h-16 flex-none bg-primary relative rounded overflow-hidden">
                <img v-if="mediaItem.coverPathSrc" :src="mediaItem.coverPathSrc" class="w-full h-full object-contain" />
              </div>
              <div class="px-3 flex-grow overflow-hidden">
                <p class="text-sm font-medium truncate">{{ mediaItem.media.metadata.title }}</p>
                <p v-if="getSeriesDisplay(mediaItem)" class="text-xs text-success truncate font-semibold">
                  {{ getSeriesDisplay(mediaItem) }}
                </p>
                <p v-if="mediaItem.mediaType == 'book'" class="text-xs text-fg-muted">{{ mediaItem.media.tracks.length }} {{ $strings.LabelTracks }}</p>
                <p v-else-if="mediaItem.mediaType == 'podcast'" class="text-xs text-fg-muted">{{ mediaItem.media.episodes.length }} {{ $strings.HeaderEpisodes }}</p>
                <p v-if="mediaItem.size" class="text-xs text-fg-muted font-mono">{{ $bytesPretty(mediaItem.size) }}</p>
              </div>
              <div class="w-12 h-12 flex items-center justify-center flex-none">
                <span class="material-symbols text-2xl text-fg-muted">chevron_right</span>
              </div>
            </nuxt-link>
          </div>
          <div v-if="num + 1 < localLibraryItems.length" class="flex border-t border-fg/10 my-3" />
        </div>
      </template>
    </div>

    <!-- Total Size Display -->
    <div v-if="localLibraryItems.length && !isSelectionMode" class="mt-6 text-sm text-fg-muted">
      {{ $strings.LabelTotalSize }}: <span class="font-mono">{{ $bytesPretty(localLibraryItems.reduce((acc, item) => acc + item.size, 0)) }}</span>
    </div>

    <!-- Sticky Bottom Action Bar in Selection Mode -->
    <div v-if="isSelectionMode" class="fixed bottom-0 left-0 w-full bg-bg border-t border-fg/10 py-4 px-6 flex items-center justify-between box-shadow-book z-40" :style="{ bottom: isPlayerOpen ? '120px' : '0px' }">
      <div class="text-xs text-fg-muted">
        Selected: {{ selectedItemIds.length }} / {{ localLibraryItems.length }}
      </div>
      <div class="flex items-center space-x-2">
        <ui-btn small color="bg" @click="selectAllItems">
          {{ selectedItemIds.length === localLibraryItems.length ? 'Clear All' : 'Select All' }}
        </ui-btn>
        <ui-btn small color="error" :disabled="!selectedItemIds.length" @click="bulkDelete">
          {{ $strings.ButtonDelete || 'Delete' }}
        </ui-btn>
      </div>
    </div>
  </div>
</template>

<script>
import { Capacitor } from '@capacitor/core'
import { Dialog } from '@capacitor/dialog'
import { AbsFileSystem } from '@/plugins/capacitor'

export default {
  data() {
    return {
      localLibraryItems: [],
      isSelectionMode: false,
      selectedItemIds: [],
      sortBy: 'title-asc',
      sortItems: [
        { value: 'title-asc', text: 'Title (A-Z)' },
        { value: 'title-desc', text: 'Title (Z-A)' },
        { value: 'size-desc', text: 'Size (Largest)' },
        { value: 'size-asc', text: 'Size (Smallest)' },
        { value: 'series', text: 'Series' }
      ]
    }
  },
  computed: {
    isPlayerOpen() {
      return this.$store.getters['getIsPlayerOpen']
    },
    sortedLibraryItems() {
      const items = [...this.localLibraryItems]
      if (this.sortBy === 'title-asc') {
        items.sort((a, b) => {
          const tA = (a.media?.metadata?.title || '').toLowerCase()
          const tB = (b.media?.metadata?.title || '').toLowerCase()
          return tA.localeCompare(tB)
        })
      } else if (this.sortBy === 'title-desc') {
        items.sort((a, b) => {
          const tA = (a.media?.metadata?.title || '').toLowerCase()
          const tB = (b.media?.metadata?.title || '').toLowerCase()
          return tB.localeCompare(tA)
        })
      } else if (this.sortBy === 'size-desc') {
        items.sort((a, b) => (b.size || 0) - (a.size || 0))
      } else if (this.sortBy === 'size-asc') {
        items.sort((a, b) => (a.size || 0) - (b.size || 0))
      } else if (this.sortBy === 'series') {
        items.sort((a, b) => {
          const sA = this.getSeriesName(a).toLowerCase()
          const sB = this.getSeriesName(b).toLowerCase()
          
          if (sA && !sB) return -1
          if (!sA && sB) return 1
          if (!sA && !sB) {
            const tA = (a.media?.metadata?.title || '').toLowerCase()
            const tB = (b.media?.metadata?.title || '').toLowerCase()
            return tA.localeCompare(tB)
          }
          
          const sComp = sA.localeCompare(sB)
          if (sComp !== 0) return sComp
          
          const seqA = parseFloat(this.getSeriesSequence(a)) || 0
          const seqB = parseFloat(this.getSeriesSequence(b)) || 0
          return seqA - seqB
        })
      }
      return items
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
        return {
          ...lmi,
          size: this.getSize(lmi),
          coverPathSrc: lmi.coverContentUrl ? Capacitor.convertFileSrc(lmi.coverContentUrl) : null
        }
      })
    },
    getSeriesName(item) {
      const series = item?.media?.metadata?.series
      if (!series) return ''
      if (Array.isArray(series)) {
        return series[0]?.name || ''
      }
      return series.name || ''
    },
    getSeriesSequence(item) {
      const series = item?.media?.metadata?.series
      if (!series) return ''
      if (Array.isArray(series)) {
        return series[0]?.sequence || ''
      }
      return series.sequence || ''
    },
    getSeriesDisplay(item) {
      const name = this.getSeriesName(item)
      if (!name) return ''
      const seq = this.getSeriesSequence(item)
      return seq ? `${name} #${seq}` : name
    },
    toggleSelectionMode() {
      this.isSelectionMode = !this.isSelectionMode
      if (!this.isSelectionMode) {
        this.selectedItemIds = []
      }
    },
    toggleSelectItem(id) {
      const idx = this.selectedItemIds.indexOf(id)
      if (idx >= 0) {
        this.selectedItemIds.splice(idx, 1)
      } else {
        this.selectedItemIds.push(id)
      }
    },
    selectAllItems() {
      if (this.selectedItemIds.length === this.localLibraryItems.length) {
        this.selectedItemIds = []
      } else {
        this.selectedItemIds = this.localLibraryItems.map((item) => item.id)
      }
    },
    async bulkDelete() {
      if (!this.selectedItemIds.length) return
      
      const count = this.selectedItemIds.length
      const confirmMessage = `Remove ${count} selected local items from your device? This will free up space.`
      
      const { value } = await Dialog.confirm({
        title: this.$strings.HeaderConfirm,
        message: confirmMessage
      })
      
      if (value) {
        this.$toast.info(`Deleting ${count} items...`)
        
        let successCount = 0
        let failCount = 0
        
        for (const id of this.selectedItemIds) {
          const item = this.localLibraryItems.find(i => i.id === id)
          if (item) {
            try {
              const res = await AbsFileSystem.deleteItem(item)
              if (res && res.success) {
                successCount++
              } else {
                failCount++
              }
            } catch (err) {
              console.error('Failed to delete item', id, err)
              failCount++
            }
          }
        }
        
        if (successCount > 0) {
          this.$toast.success(`Successfully deleted ${successCount} items`)
        }
        if (failCount > 0) {
          this.$toast.error(`Failed to delete ${failCount} items`)
        }
        
        this.selectedItemIds = []
        this.isSelectionMode = false
        
        await this.init()
      }
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
