<template>
  <div class="w-full h-full py-6 px-2">
    <div class="flex items-center mb-4">
      <div class="flex-grow" />
      <ui-btn v-if="!removingFolder" :loading="isScanning" small @click="clickScan">Scan</ui-btn>
      <ui-btn v-if="!removingFolder && localLibraryItems.length" :loading="isScanning" small class="ml-2" color="warning" @click="clickForceRescan">Force Re-Scan</ui-btn>
      <ui-icon-btn class="ml-2" bg-color="error" outlined :loading="removingFolder" icon="delete" @click="clickDeleteFolder" />
    </div>
    <p class="text-lg mb-0.5 text-white text-opacity-75">Folder: {{ folderName }}</p>
    <p class="mb-4 text-xl">Local Library Items ({{ localLibraryItems.length }})</p>
    <div v-if="isScanning" class="w-full text-center p-4">
      <p>Scanning...</p>
    </div>
    <div v-else class="w-full media-item-container overflow-y-auto">
      <template v-for="mediaItem in localLibraryItems">
        <nuxt-link :to="`/localMedia/item/${mediaItem.id}`" :key="mediaItem.id" class="flex my-1">
          <div class="w-12 h-12 bg-primary">
            <img v-if="mediaItem.coverPathSrc" :src="mediaItem.coverPathSrc" class="w-full h-full object-contain" />
          </div>
          <div class="flex-grow px-2">
            <p>{{ mediaItem.media.metadata.title }}</p>
            <p v-if="mediaItem.type == 'book'">{{ mediaItem.media.tracks.length }} Tracks</p>
            <p v-else-if="mediaItem.type == 'podcast'">{{ mediaItem.media.episodes.length }} Tracks</p>
          </div>
          <div class="w-12 h-12 flex items-center justify-center">
            <span class="material-icons text-xl text-gray-300">arrow_right</span>
            <!-- <button class="shadow-sm text-accent flex items-center justify-center rounded-full" @click.stop="play(mediaItem)">
              <span class="material-icons" style="font-size: 2rem">play_arrow</span>
            </button> -->
          </div>
        </nuxt-link>
      </template>
    </div>
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
      removingFolder: false
    }
  },
  computed: {
    folderName() {
      return this.folder ? this.folder.name : null
    }
  },
  methods: {
    clickScan() {
      this.scanFolder()
    },
    clickForceRescan() {
      this.scanFolder(true)
    },
    async clickDeleteFolder() {
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
      this.$eventBus.$emit('play-item', mediaItem.id)
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
  height: calc(100vh - 200px);
  max-height: calc(100vh - 200px);
}
</style>