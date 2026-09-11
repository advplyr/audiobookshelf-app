<template>
  <div class="w-full h-full overflow-y-auto pb-24">
    <!-- Header -->
    <div class="flex items-center px-4 pt-6 pb-2">
      <p class="text-base text-fg flex-grow">
        {{ $strings.HeaderDownloads }} ({{ localLibraryItems.length }})
      </p>

      <!-- Sort buttons -->
      <button
        id="btn-sort-desc"
        :class="sortOrder === 'desc' ? 'text-fg' : 'text-fg-muted'"
        class="w-9 h-9 flex items-center justify-center"
        :title="$strings.LabelSortBySize"
        @click="toggleSort('desc')"
      >
        <span class="material-symbols text-xl">arrow_downward</span>
      </button>
      <button
        id="btn-sort-asc"
        :class="sortOrder === 'asc' ? 'text-fg' : 'text-fg-muted'"
        class="w-9 h-9 flex items-center justify-center"
        :title="$strings.LabelSortBySize"
        @click="toggleSort('asc')"
      >
        <span class="material-symbols text-xl">arrow_upward</span>
      </button>

      <!-- Select / Cancel button -->
      <button
        id="btn-select-mode"
        class="ml-1 px-2 py-1 text-xs rounded-md border border-fg/30 text-fg"
        @click="toggleSelectMode"
      >
        {{ selectMode ? $strings.ButtonCancel : $strings.ButtonSelect }}
      </button>
    </div>

    <!-- Sort label -->
    <p v-if="sortOrder !== 'none'" class="px-4 mb-2 text-xs text-fg-muted">
      {{ $strings.LabelSortBySize }}: {{ sortOrder === 'desc' ? '↓ Büyükten küçüğe' : '↑ Küçükten büyüğe' }}
    </p>

    <!-- Select All row -->
    <div v-if="selectMode" class="flex items-center px-4 py-2 border-b border-fg/10">
      <button id="btn-select-all" class="flex items-center gap-2 text-sm text-fg" @click="selectAll">
        <span class="material-symbols text-lg">{{ allSelected ? 'check_box' : 'check_box_outline_blank' }}</span>
        {{ $strings.ButtonSelectAll }}
      </button>
      <span class="ml-auto text-xs text-fg-muted">{{ selectedItemIds.length }} / {{ sortedItems.length }}</span>
    </div>

    <!-- List -->
    <div class="w-full px-4">
      <template v-for="(mediaItem, num) in sortedItems">
        <div :key="mediaItem.id" class="w-full">
          <div class="flex items-center">
            <!-- Checkbox (select mode) -->
            <button
              v-if="selectMode"
              :id="`btn-select-${mediaItem.id}`"
              class="w-10 h-16 flex items-center justify-center flex-none"
              @click="toggleSelectItem(mediaItem.id)"
            >
              <span class="material-symbols text-xl" :class="isSelected(mediaItem.id) ? 'text-success' : 'text-fg-muted'">
                {{ isSelected(mediaItem.id) ? 'check_box' : 'check_box_outline_blank' }}
              </span>
            </button>

            <!-- Item row (nuxt-link disabled in select mode) -->
            <component
              :is="selectMode ? 'div' : 'nuxt-link'"
              :to="selectMode ? undefined : `/localMedia/item/${mediaItem.id}`"
              class="flex items-center flex-grow"
              :class="{ 'cursor-pointer': selectMode }"
              @click.native="selectMode ? toggleSelectItem(mediaItem.id) : null"
            >
              <div class="w-16 h-16 min-w-16 min-h-16 flex-none bg-primary relative">
                <img v-if="mediaItem.coverPathSrc" :src="mediaItem.coverPathSrc" class="w-full h-full object-contain" />
              </div>
              <div class="px-2 flex-grow">
                <p class="text-sm">{{ mediaItem.media.metadata.title }}</p>
                <p v-if="mediaItem.mediaType === 'book'" class="text-xs text-fg-muted">
                  {{ mediaItem.media.tracks.length }} {{ $strings.LabelTracks }}
                </p>
                <p v-else-if="mediaItem.mediaType === 'podcast'" class="text-xs text-fg-muted">
                  {{ mediaItem.media.episodes.length }} {{ $strings.HeaderEpisodes }}
                </p>
                <p v-if="mediaItem.size" class="text-xs text-fg-muted">{{ $bytesPretty(mediaItem.size) }}</p>
              </div>
              <div v-if="!selectMode" class="w-12 h-12 flex items-center justify-center">
                <span class="material-symbols text-2xl text-fg-muted">chevron_right</span>
              </div>
            </component>
          </div>

          <div v-if="num + 1 < sortedItems.length" class="flex border-t border-fg/10 my-3" />
        </div>
      </template>

      <!-- Total size -->
      <div v-if="localLibraryItems.length" class="mt-4 text-sm text-fg-muted">
        {{ $strings.LabelTotalSize }}: {{ $bytesPretty(totalStorageSize) }}
      </div>
    </div>

    <!-- Bottom action bar (select mode) -->
    <div
      v-if="selectMode"
      class="fixed bottom-0 left-0 w-full px-4 py-3 bg-bg border-t border-fg/20 flex items-center gap-3"
      :style="{ bottom: isPlayerOpen ? '120px' : '0px' }"
    >
      <div class="flex-grow">
        <p class="text-sm text-fg">{{ selectedItemIds.length }} {{ $strings.LabelSelected || 'selected' }}</p>
        <p v-if="selectedItemIds.length" class="text-xs text-fg-muted">
          {{ $strings.LabelTotalSize }}: {{ $bytesPretty(selectedTotalSize) }}
        </p>
      </div>
      <ui-btn
        id="btn-delete-selected"
        small
        color="error"
        :disabled="!selectedItemIds.length"
        @click="deleteSelected"
      >
        {{ $strings.ButtonDeleteSelected.replace('{0}', selectedItemIds.length) }}
      </ui-btn>
    </div>
  </div>
</template>

<script>
import { Capacitor } from '@capacitor/core'
import { Dialog } from '@capacitor/dialog'

export default {
  data() {
    return {
      localLibraryItems: [],
      sortOrder: 'none', // 'none' | 'asc' | 'desc'
      selectMode: false,
      selectedItemIds: [],
      isDeleting: false
    }
  },
  computed: {
    isPlayerOpen() {
      return this.$store.getters['getIsPlayerOpen']
    },
    sortedItems() {
      if (this.sortOrder === 'none') return this.localLibraryItems
      return [...this.localLibraryItems].sort((a, b) => {
        return this.sortOrder === 'desc' ? b.size - a.size : a.size - b.size
      })
    },
    totalStorageSize() {
      return this.localLibraryItems.reduce((acc, item) => acc + item.size, 0)
    },
    selectedItems() {
      return this.localLibraryItems.filter((item) => this.selectedItemIds.includes(item.id))
    },
    selectedTotalSize() {
      return this.selectedItems.reduce((acc, item) => acc + item.size, 0)
    },
    allSelected() {
      return this.localLibraryItems.length > 0 && this.selectedItemIds.length === this.localLibraryItems.length
    }
  },
  methods: {
    toggleSort(direction) {
      this.sortOrder = this.sortOrder === direction ? 'none' : direction
    },
    toggleSelectMode() {
      this.selectMode = !this.selectMode
      this.selectedItemIds = []
    },
    isSelected(id) {
      return this.selectedItemIds.includes(id)
    },
    toggleSelectItem(id) {
      const idx = this.selectedItemIds.indexOf(id)
      if (idx >= 0) {
        this.selectedItemIds.splice(idx, 1)
      } else {
        this.selectedItemIds.push(id)
      }
    },
    selectAll() {
      if (this.allSelected) {
        this.selectedItemIds = []
      } else {
        this.selectedItemIds = this.localLibraryItems.map((item) => item.id)
      }
    },
    async deleteSelected() {
      if (!this.selectedItemIds.length || this.isDeleting) return

      // AbsFileSystem is a native Capacitor plugin, not available on web
      if (this.$platform === 'web') {
        this.$toast.error('Deleting local items is only supported on mobile devices')
        return
      }

      const count = this.selectedItemIds.length
      const confirmMessage = this.$strings.MessageConfirmDeleteSelectedItems
        ? this.$strings.MessageConfirmDeleteSelectedItems.replace('{0}', count)
        : `Remove ${count} local item(s) from your device? The files on the server and your progress will be unaffected.`

      const { value } = await Dialog.confirm({
        title: this.$strings.HeaderConfirm,
        message: confirmMessage
      })
      if (!value) return

      this.isDeleting = true
      const { AbsFileSystem } = await import('@/plugins/capacitor')
      let successCount = 0
      let failCount = 0

      for (const id of [...this.selectedItemIds]) {
        const item = this.localLibraryItems.find((i) => i.id === id)
        if (!item) continue
        const res = await AbsFileSystem.deleteItem(item)
        if (res && res.success) {
          successCount++
          const idx = this.localLibraryItems.findIndex((i) => i.id === id)
          if (idx >= 0) this.localLibraryItems.splice(idx, 1)
          const selIdx = this.selectedItemIds.indexOf(id)
          if (selIdx >= 0) this.selectedItemIds.splice(selIdx, 1)
        } else {
          failCount++
        }
      }

      this.isDeleting = false
      if (successCount) this.$toast.success(`Deleted ${successCount} item(s) successfully`)
      if (failCount) this.$toast.error(`Failed to delete ${failCount} item(s)`)

      if (!this.selectedItemIds.length) {
        this.selectMode = false
      }
    },
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
      const items = (await this.$db.getLocalLibraryItems()) || []
      this.localLibraryItems = items.map((lmi) => {
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

