<template>
  <div class="w-full h-full py-6 px-4">
    <div class="flex items-center mb-2">
      <p class="text-base font-semibold">{{ $strings.LabelFolder }}: {{ folderName }}</p>
      <div class="flex-grow" />

      <span v-if="dialogItems.length" class="material-symbols text-2xl" @click="showDialog = true">more_vert</span>
    </div>

    <p class="text-sm mb-4 text-fg-muted">{{ $strings.LabelMediaType }}: {{ mediaType }}</p>

    <div class="flex items-center mb-2">
      <p class="text-base text-fg">{{ $strings.HeaderLocalLibraryItems }} ({{ localLibraryItems.length }})</p>
      <div class="flex-grow" />
      <div class="flex items-center gap-2">
        <button class="w-8 h-8 rounded-full hover:bg-bg-hover flex items-center justify-center transition-colors" :class="sortBySize === 1 ? 'bg-primary/20 text-primary' : 'text-fg-muted hover:text-fg'" @click="toggleSort" :title="$strings.LabelSortBySize">
          <span class="material-symbols fill text-xl">arrow_downward</span>
        </button>
        <button class="w-8 h-8 rounded-full hover:bg-bg-hover flex items-center justify-center transition-colors" :class="sortBySize === -1 ? 'bg-primary/20 text-primary' : 'text-fg-muted hover:text-fg'" @click="toggleSortAsc" :title="$strings.LabelSortBySize">
          <span class="material-symbols fill text-xl">arrow_upward</span>
        </button>
        <button class="text-sm px-3 py-1 rounded bg-bg-hover hover:bg-bg-hover/80 transition-colors" @click="toggleSelectMode">
          {{ isSelectMode ? $strings.ButtonCancel || 'Cancel' : $strings.ButtonSelect || 'Select' }}
        </button>
      </div>
    </div>

    <div class="w-full media-item-container overflow-y-auto" :class="isSelectMode ? 'pb-24' : ''">
      <template v-for="localLibraryItem in sortedItems">
        <div :key="localLibraryItem.id" class="flex my-1 cursor-pointer" @click="handleItemClick(localLibraryItem)">
          <div v-if="isSelectMode" class="w-10 min-w-10 flex items-center justify-center">
            <span class="material-symbols text-2xl" :class="selectedItemIds.includes(localLibraryItem.id) ? 'text-primary fill' : 'text-fg-muted'">
              {{ selectedItemIds.includes(localLibraryItem.id) ? 'check_circle' : 'radio_button_unchecked' }}
            </span>
          </div>
          <div class="w-12 h-12 min-w-12 min-h-12 bg-primary">
            <img v-if="localLibraryItem.coverPathSrc" :src="localLibraryItem.coverPathSrc" class="w-full h-full object-contain" />
          </div>
          <div class="flex-grow px-2">
            <p class="text-sm">{{ localLibraryItem.media.metadata.title }}</p>
            <p class="text-xs text-fg-muted">
              {{ getLocalLibraryItemSubText(localLibraryItem) }}
              <span v-if="localLibraryItem.size" class="ml-1 px-1 bg-primary/10 text-primary rounded-sm">{{ $bytesPretty(localLibraryItem.size) }}</span>
            </p>
          </div>
          <div class="w-12 h-12 flex items-center justify-center">
            <span class="material-symbols text-xl text-fg-muted">arrow_right</span>
          </div>
        </div>
      </template>
    </div>

    <div v-if="isSelectMode" class="absolute bottom-0 left-0 w-full bg-bg shadow-[0_-4px_6px_-1px_rgba(0,0,0,0.1)] p-4 flex items-center z-10 border-t border-bg-hover">
      <button class="text-sm px-4 py-2 rounded border border-fg-muted/30 hover:bg-bg-hover transition-colors" @click="selectAll">
        {{ $strings.ButtonSelectAll || 'Select All' }}
      </button>
      <div class="flex-grow text-center">
        <p class="text-xs text-fg-muted">{{ selectedItemIds.length }} selected</p>
        <p v-if="selectedItemIds.length > 0" class="text-xs text-primary">{{ selectedItemsSize }}</p>
      </div>
      <button class="text-sm px-4 py-2 rounded bg-error text-white hover:bg-error/90 transition-colors disabled:opacity-50" :disabled="!selectedItemIds.length || isDeleting" @click="deleteSelected">
        <span v-if="isDeleting" class="material-symbols animate-spin text-sm align-middle mr-1">progress_activity</span>
        {{ $strings.ButtonDeleteSelected ? $strings.ButtonDeleteSelected.replace('{0}', selectedItemIds.length) : `Delete (${selectedItemIds.length})` }}
      </button>
    </div>

    <modals-dialog v-model="showDialog" :items="dialogItems" @action="dialogAction" />
  </div>
</template>

<script>
import { Capacitor } from '@capacitor/core'
import { Dialog } from '@capacitor/dialog'
import { AbsFileSystem } from '@/plugins/capacitor'

export default {
  asyncData({ params, query }) {
    return {
      folderId: params.id
    }
  },
  data() {
    return {
      localLibraryItems: [],
      folder: null,
      removingFolder: false,
      showDialog: false,
      sortBySize: 0,
      isSelectMode: false,
      selectedItemIds: [],
      isDeleting: false
    }
  },
  computed: {
    folderName() {
      return this.folder?.name || null
    },
    mediaType() {
      return this.folder?.mediaType
    },
    isInternalStorage() {
      return this.folder?.id.startsWith('internal-')
    },
    dialogItems() {
      if (this.isInternalStorage) return []
      const items = []
      items.push({
        text: this.$strings.ButtonRemove,
        value: 'remove'
      })
      return items
    },
    sortedItems() {
      let items = [...this.localLibraryItems]
      if (this.sortBySize === 1) {
        items.sort((a, b) => (b.size || 0) - (a.size || 0))
      } else if (this.sortBySize === -1) {
        items.sort((a, b) => (a.size || 0) - (b.size || 0))
      }
      return items
    },
    selectedItemsSize() {
      const selected = this.localLibraryItems.filter((item) => this.selectedItemIds.includes(item.id))
      const totalBytes = selected.reduce((acc, item) => acc + (item.size || 0), 0)
      return this.$bytesPretty(totalBytes)
    }
  },
  methods: {
    getLocalLibraryItemSubText(localLibraryItem) {
      if (!localLibraryItem) return ''
      if (localLibraryItem.mediaType == 'book') {
        const txts = []
        if (localLibraryItem.media.ebookFile) {
          txts.push(`${localLibraryItem.media.ebookFile.ebookFormat} ${this.$strings.LabelEbook}`)
        }
        if (localLibraryItem.media.tracks?.length) {
          txts.push(`${localLibraryItem.media.tracks.length} ${this.$strings.LabelTracks}`)
        }
        return txts.join(' • ')
      } else {
        return `${localLibraryItem.media.episodes?.length || 0} ${this.$strings.HeaderEpisodes}`
      }
    },
    dialogAction(action) {
      console.log('Dialog action', action)
      if (action == 'remove') {
        this.removeFolder()
      }
      this.showDialog = false
    },
    toggleSort() {
      if (this.sortBySize === 1) this.sortBySize = 0
      else this.sortBySize = 1
    },
    toggleSortAsc() {
      if (this.sortBySize === -1) this.sortBySize = 0
      else this.sortBySize = -1
    },
    toggleSelectMode() {
      this.isSelectMode = !this.isSelectMode
      if (!this.isSelectMode) {
        this.selectedItemIds = []
      }
    },
    handleItemClick(item) {
      if (this.isSelectMode) {
        this.toggleSelect(item)
      } else {
        this.$router.push(`/localMedia/item/${item.id}`)
      }
    },
    toggleSelect(item) {
      const index = this.selectedItemIds.indexOf(item.id)
      if (index === -1) {
        this.selectedItemIds.push(item.id)
      } else {
        this.selectedItemIds.splice(index, 1)
      }
    },
    selectAll() {
      if (this.selectedItemIds.length === this.localLibraryItems.length) {
        this.selectedItemIds = []
      } else {
        this.selectedItemIds = this.localLibraryItems.map((item) => item.id)
      }
    },
    async deleteSelected() {
      if (!this.selectedItemIds.length || this.isDeleting) return

      const count = this.selectedItemIds.length
      const confirmMessage = this.$strings.MessageConfirmDeleteSelectedItems
        ? this.$strings.MessageConfirmDeleteSelectedItems.replace('{0}', count)
        : `Remove ${count} local item(s) from your device?`

      const { value } = await Dialog.confirm({
        title: this.$strings.HeaderConfirm,
        message: confirmMessage
      })
      if (!value) return

      this.isDeleting = true
      let successCount = 0
      let failCount = 0

      for (const id of this.selectedItemIds) {
        const item = this.localLibraryItems.find((i) => i.id === id)
        if (item) {
          try {
            await AbsFileSystem.deleteItem(item)
            successCount++
          } catch (error) {
            console.error('Failed to delete item', item.id, error)
            failCount++
          }
        }
      }

      this.$toast.success(`Removed ${successCount} items from device`)
      if (failCount > 0) {
        this.$toast.error(`Failed to remove ${failCount} items`)
      }

      this.isDeleting = false
      this.selectedItemIds = []
      this.isSelectMode = false
      await this.init()
    },
    getSize(item) {
      if (!item || !item.localFiles) return 0
      let size = 0
      for (let i = 0; i < item.localFiles.length; i++) {
        size += item.localFiles[i].size
      }
      return size
    },
    async removeFolder() {
      var deleteMessage = 'Are you sure you want to remove this folder? (does not delete anything in your file system)'
      if (this.localLibraryItems.length) {
        deleteMessage = `Are you sure you want to remove this folder and ${this.localLibraryItems.length} items? (does not delete anything in your file system)`
      }
      const { value } = await Dialog.confirm({
        title: this.$strings.HeaderConfirm,
        message: deleteMessage
      })
      if (value) {
        this.removingFolder = true
        await AbsFileSystem.removeFolder({ folderId: this.folderId })
        this.removingFolder = false
        this.$router.replace('/localMedia/folders')
      }
    },
    async init() {
      var folder = await this.$db.getLocalFolder(this.folderId)
      this.folder = folder

      var items = (await this.$db.getLocalLibraryItemsInFolder(this.folderId)) || []
      this.localLibraryItems = items.map((lmi) => {
        return {
          ...lmi,
          size: this.getSize(lmi),
          coverPathSrc: lmi.coverContentUrl ? Capacitor.convertFileSrc(lmi.coverContentUrl) : null
        }
      })
    },
    newLocalLibraryItem(item) {
      if (item.folderId == this.folderId) {
        console.log('New local library item', item.id)
        if (this.localLibraryItems.find((li) => li.id == item.id)) {
          console.warn('Item already added', item.id)
          return
        }

        var _item = {
          ...item,
          coverPathSrc: item.coverContentUrl ? Capacitor.convertFileSrc(item.coverContentUrl) : null
        }
        this.localLibraryItems.push(_item)
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

<style scoped>
.media-item-container {
  height: calc(100vh - 210px);
  max-height: calc(100vh - 210px);
}
.playerOpen .media-item-container {
  height: calc(100vh - 310px);
  max-height: calc(100vh - 310px);
}
</style>
