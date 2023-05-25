<template>
  <div class="w-full h-full py-6 px-4">
    <div class="flex items-center mb-2">
      <p class="text-base font-semibold">Folder: {{ folderName }}</p>
      <div class="flex-grow" />

      <span class="material-icons" @click="showDialog = true">more_vert</span>
    </div>

    <p class="text-sm mb-4 text-white text-opacity-60">Media Type: {{ mediaType }}</p>

    <p class="mb-2 text-base text-white">Local Library Items ({{ localLibraryItems.length }})</p>

    <div v-if="isScanning" class="w-full text-center p-4">
      <p>Scanning...</p>
    </div>
    <div v-else class="w-full media-item-container overflow-y-auto">
      <template v-for="localLibraryItem in localLibraryItems">
        <nuxt-link :to="`/localMedia/item/${localLibraryItem.id}`" :key="localLibraryItem.id" class="flex my-1">
          <div class="w-12 h-12 min-w-12 min-h-12 bg-primary">
            <img v-if="localLibraryItem.coverPathSrc" :src="localLibraryItem.coverPathSrc" class="w-full h-full object-contain" />
          </div>
          <div class="flex-grow px-2">
            <p class="text-sm">{{ localLibraryItem.media.metadata.title }}</p>
            <p class="text-xs text-gray-300">{{ getLocalLibraryItemSubText(localLibraryItem) }}</p>
          </div>
          <div class="w-12 h-12 flex items-center justify-center">
            <span class="material-icons text-xl text-gray-300">arrow_right</span>
          </div>
        </nuxt-link>
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
      folderId: params.id,
      shouldScan: !!query.scan
    }
  },
  data() {
    return {
      localLibraryItems: [],
      folder: null,
      isScanning: false,
      removingFolder: false,
      showDialog: false
    }
  },
  computed: {
    folderName() {
      return this.folder ? this.folder.name : null
    },
    mediaType() {
      return this.folder ? this.folder.mediaType : null
    },
    dialogItems() {
      return [
        {
          text: 'Scan',
          value: 'scan'
        },
        {
          text: 'Force Re-Scan',
          value: 'rescan'
        },
        {
          text: 'Remove',
          value: 'remove'
        }
      ].filter((i) => i.value != 'rescan' || this.localLibraryItems.length) // Filter out rescan if there are no local library items
    }
  },
  methods: {
    getLocalLibraryItemSubText(localLibraryItem) {
      if (!localLibraryItem) return ''
      if (localLibraryItem.mediaType == 'book') {
        const txts = []
        if (localLibraryItem.media.ebookFile) {
          txts.push(`${localLibraryItem.media.ebookFile.ebookFormat} EBook`)
        }
        if (localLibraryItem.media.tracks?.length) {
          txts.push(`${localLibraryItem.media.tracks.length} Tracks`)
        }
        return txts.join(' • ')
      } else {
        return `${localLibraryItem.media.episodes?.length || 0} Episodes`
      }
    },
    dialogAction(action) {
      console.log('Dialog action', action)
      if (action == 'scan') {
        this.scanFolder()
      } else if (action == 'rescan') {
        this.scanFolder(true)
      } else if (action == 'remove') {
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
        title: 'Confirm',
        message: deleteMessage
      })
      if (value) {
        this.removingFolder = true
        await AbsFileSystem.removeFolder({ folderId: this.folderId })
        this.removingFolder = false
        this.$router.replace('/localMedia/folders')
      }
    },
    play(mediaItem) {
      this.$eventBus.$emit('play-item', { libraryItemId: mediaItem.id })
    },
    async scanFolder(forceAudioProbe = false) {
      this.isScanning = true
      var response = await AbsFileSystem.scanFolder({ folderId: this.folderId, forceAudioProbe })

      if (response && response.localLibraryItems) {
        var itemsAdded = response.itemsAdded
        var itemsUpdated = response.itemsUpdated
        var itemsRemoved = response.itemsRemoved
        var itemsUpToDate = response.itemsUpToDate
        var toastMessages = []
        if (itemsAdded) toastMessages.push(`${itemsAdded} Added`)
        if (itemsUpdated) toastMessages.push(`${itemsUpdated} Updated`)
        if (itemsRemoved) toastMessages.push(`${itemsRemoved} Removed`)
        if (itemsUpToDate) toastMessages.push(`${itemsUpToDate} Up-to-date`)
        this.$toast.info(`Folder scan complete:\n${toastMessages.join(' | ')}`)

        // When all items are up-to-date then local media items are not returned
        if (response.localLibraryItems.length) {
          this.localLibraryItems = response.localLibraryItems.map((mi) => {
            if (mi.coverContentUrl) {
              mi.coverPathSrc = Capacitor.convertFileSrc(mi.coverContentUrl)
            }
            return mi
          })
          console.log('Set Local Media Items', this.localLibraryItems.length)
        }
      } else {
        console.log('No Local media items found')
      }
      this.isScanning = false
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

      if (this.shouldScan) {
        this.scanFolder()
      }
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