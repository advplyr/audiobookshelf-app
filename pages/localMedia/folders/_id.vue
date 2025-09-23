<template>
  <div class="w-full h-full py-6 px-4" :style="contentPaddingStyle">
    <div class="flex items-center mb-2">
      <p class="text-base font-semibold">{{ $strings.LabelFolder }}: {{ folderName }}</p>
      <div class="flex-grow" />

      <button v-if="dialogItems.length" class="w-8 h-8 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center hover:bg-secondary-container-hover active:scale-95" @click="showDialog = true">
        <span class="material-symbols text-lg text-on-surface">more_vert</span>
      </button>
    </div>

    <p class="text-sm mb-4 text-on-surface-variant">{{ $strings.LabelMediaType }}: {{ mediaType }}</p>

    <p class="mb-2 text-base text-on-surface">{{ $strings.HeaderLocalLibraryItems }} ({{ localLibraryItems.length }})</p>

    <div class="w-full media-item-container overflow-y-auto">
      <template v-for="localLibraryItem in localLibraryItems">
        <nuxt-link :to="`/localMedia/item/${localLibraryItem.id}`" :key="localLibraryItem.id" class="flex my-1 p-3 bg-surface-container rounded-2xl hover:bg-surface-container-hover active:scale-95 transition-all">
          <div class="w-12 h-12 min-w-12 min-h-12 bg-surface-container rounded-lg flex items-center justify-center">
            <img v-if="localLibraryItem.coverPathSrc" :src="localLibraryItem.coverPathSrc" class="w-full h-full object-contain rounded-lg" />
            <span v-else class="material-symbols text-2xl text-on-surface-variant">music_note</span>
          </div>
          <div class="flex-grow px-3">
            <p class="text-sm text-on-surface">{{ localLibraryItem.media.metadata.title }}</p>
            <p class="text-xs text-on-surface-variant">{{ getLocalLibraryItemSubText(localLibraryItem) }}</p>
          </div>
          <div class="w-8 h-8 flex items-center justify-center">
            <span class="material-symbols text-xl text-on-surface-variant">arrow_right</span>
          </div>
        </nuxt-link>
      </template>
    </div>

    <modals-dialog v-model="showDialog" :items="dialogItems" @action="dialogAction" />
    <modals-confirm-dialog v-model="showConfirmDialog" :title="confirmDialogTitle" :message="confirmDialogMessage" @confirm="handleConfirm" @cancel="handleCancel" />
  </div>
</template>

<script>
import { Capacitor } from '@capacitor/core'
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
      showConfirmDialog: false,
      confirmDialogTitle: '',
      confirmDialogMessage: '',
      pendingConfirmAction: null
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
    contentPaddingStyle() {
      return this.$store.getters['getIsPlayerOpen'] ? { paddingBottom: '120px' } : {}
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
      if (action == 'remove') {
        this.removeFolder()
      }
      this.showDialog = false
    },
    handleConfirm() {
      if (this.pendingConfirmAction === 'removeFolder') {
        this.executeRemoveFolder()
      }
      this.pendingConfirmAction = null
    },
    handleCancel() {
      this.pendingConfirmAction = null
    },
    async executeRemoveFolder() {
      this.removingFolder = true
      await AbsFileSystem.removeFolder({ folderId: this.folderId })
      this.removingFolder = false
      this.$router.replace('/localMedia/folders')
    },
    async removeFolder() {
      var deleteMessage = 'Are you sure you want to remove this folder? (does not delete anything in your file system)'
      if (this.localLibraryItems.length) {
        deleteMessage = `Are you sure you want to remove this folder and ${this.localLibraryItems.length} items? (does not delete anything in your file system)`
      }
      this.confirmDialogTitle = 'Confirm'
      this.confirmDialogMessage = deleteMessage
      this.pendingConfirmAction = 'removeFolder'
      this.showConfirmDialog = true
    },
    async init() {
      var folder = await this.$db.getLocalFolder(this.folderId)
      this.folder = folder

      var items = (await this.$db.getLocalLibraryItemsInFolder(this.folderId)) || []
      this.localLibraryItems = items.map((lmi) => {
        return {
          ...lmi,
          coverPathSrc: lmi.coverContentUrl ? Capacitor.convertFileSrc(lmi.coverContentUrl) : null
        }
      })
    },
    newLocalLibraryItem(item) {
      if (item.folderId == this.folderId) {
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
  height: calc(100vh - 210px); /* Same as regular container - no extra padding for player */
  max-height: calc(100vh - 210px);
}
</style>
