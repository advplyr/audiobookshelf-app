<template>
  <div class="w-full h-full py-6 px-4">
    <div class="flex items-center justify-between mb-2">
      <p class="text-base font-semibold">{{ $strings.LabelFolder }}: {{ folderName }}</p>

      <div v-if="selectedItems.length > 0" class="flex items-center gap-2">
        <p class="text-sm text-fg-muted">{{ selectedItems.length }} selected</p>
        <button @click.stop="bulkDelete" type="button" class="px-3 py-1 bg-error text-white rounded-md text-sm">
          Delete Selected
        </button>
        <button @click.stop="clearSelection" type="button" class="px-3 py-1 bg-bg-secondary text-fg rounded-md text-sm">
          Cancel
        </button>
      </div>
      <div v-else class="flex items-center gap-2">
        <button v-if="localLibraryItems.length > 0" @click="toggleSelectMode" class="px-3 py-1 bg-bg-secondary text-fg rounded-md text-sm">
          Select Multiple
        </button>
        <span v-if="dialogItems.length" class="material-symbols text-2xl" @click="showDialog = true">more_vert</span>
      </div>
    </div>

    <p class="text-sm mb-4 text-fg-muted">{{ $strings.LabelMediaType }}: {{ mediaType }}</p>

    <p class="mb-2 text-base text-fg">{{ $strings.HeaderLocalLibraryItems }} ({{ localLibraryItems.length }})</p>

    <div class="w-full media-item-container overflow-y-auto">
      <template v-for="localLibraryItem in localLibraryItems">
        <div :key="localLibraryItem.id" class="flex my-1" :class="{ 'bg-bg-secondary rounded-lg': selectedItems.includes(localLibraryItem.id) }">
          <div v-if="selectMode" class="w-10 flex items-center justify-center" @click="toggleSelection(localLibraryItem.id)">
            <input type="checkbox" :checked="selectedItems.includes(localLibraryItem.id)" class="w-5 h-5" />
          </div>
          <nuxt-link v-if="!selectMode" :to="`/localMedia/item/${localLibraryItem.id}`" class="flex flex-grow">
            <div class="w-12 h-12 min-w-12 min-h-12 bg-primary">
              <img v-if="localLibraryItem.coverPathSrc" :src="localLibraryItem.coverPathSrc" class="w-full h-full object-contain" />
            </div>
            <div class="flex-grow px-2">
              <p class="text-sm">{{ localLibraryItem.media.metadata.title }}</p>
              <p class="text-xs text-fg-muted">{{ getLocalLibraryItemSubText(localLibraryItem) }}</p>
            </div>
            <div class="w-12 h-12 flex items-center justify-center">
              <span class="material-symbols text-xl text-fg-muted">arrow_right</span>
            </div>
          </nuxt-link>
          <div v-else class="flex flex-grow" @click="toggleSelection(localLibraryItem.id)">
            <div class="w-12 h-12 min-w-12 min-h-12 bg-primary">
              <img v-if="localLibraryItem.coverPathSrc" :src="localLibraryItem.coverPathSrc" class="w-full h-full object-contain" />
            </div>
            <div class="flex-grow px-2">
              <p class="text-sm">{{ localLibraryItem.media.metadata.title }}</p>
              <p class="text-xs text-fg-muted">{{ getLocalLibraryItemSubText(localLibraryItem) }}</p>
            </div>
            <div class="w-12 h-12 flex items-center justify-center">
              <span class="material-symbols text-xl text-fg-muted">arrow_right</span>
            </div>
          </div>
        </div>
      </template>
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
      selectMode: false,
      selectedItems: []
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
    }
  },
  methods: {
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
    bulkDelete() {
      if (this.selectedItems.length === 0) return

      // Use native confirm instead of $confirm
      if (!confirm(`Delete ${this.selectedItems.length} item(s)? This will permanently delete the selected downloads.`)) {
        return
      }

      console.log('=== BULK DELETE START ===')
      console.log('Starting delete...')
      this.$hapticsImpact()

      const itemsToDelete = this.selectedItems.map(id => {
        const item = this.localLibraryItems.find(li => li.id === id)
        return {
          id: item.id,
          absolutePath: item.absolutePath,
          contentUrl: item.contentUrl
        }
      })

      console.log('Calling deleteMultipleItems with', itemsToDelete.length, 'items')
      console.log('First item:', JSON.stringify(itemsToDelete[0], null, 2))
      console.log('All items:', JSON.stringify(itemsToDelete, null, 2))

      AbsFileSystem.deleteMultipleItems({ items: itemsToDelete }).then(result => {
        console.log('Got result:', JSON.stringify(result, null, 2))
        if (result.success) {
          console.log('SUCCESS: Deleted', result.deleted, 'items')
          this.$toast.success(`Deleted ${result.deleted} items`)
          this.localLibraryItems = this.localLibraryItems.filter(item => !this.selectedItems.includes(item.id))
        } else {
          console.log('PARTIAL: Deleted', result.deleted, 'items,', result.failed, 'failed')
          this.$toast.warning(`Deleted ${result.deleted} items, ${result.failed} failed`)
          const failedIds = result.failedItems ? result.failedItems.split(',') : []
          const deletedIds = this.selectedItems.filter(id => !failedIds.includes(id))
          this.localLibraryItems = this.localLibraryItems.filter(item => !deletedIds.includes(item.id))
        }
        this.clearSelection()
        console.log('=== BULK DELETE END ===')
      }).catch(error => {
        console.error('=== BULK DELETE ERROR ===')
        console.error('Error:', error)
        console.error('Error message:', error.message)
        console.error('Error stack:', error.stack)
        this.$toast.error('Failed to delete items')
        this.clearSelection()
      })
    },
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
      console.log('Init folder', this.folderId, items)
      this.localLibraryItems = items.map((lmi) => {
        console.log('Local library item', JSON.stringify(lmi))
        return {
          ...lmi,
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
  height: calc(100vh - 220px);
  max-height: calc(100vh - 220px);
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
}
</style>
