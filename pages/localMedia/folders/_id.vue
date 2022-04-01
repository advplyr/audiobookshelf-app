<template>
  <div class="w-full h-full py-6 px-2">
    <div class="flex items-center mb-4">
      <div class="flex-grow" />
      <ui-btn v-if="!removingFolder" :loading="isScanning" small @click="clickScan">Scan</ui-btn>
      <ui-btn v-if="!removingFolder && localMediaItems.length" :loading="isScanning" small class="ml-2" color="warning" @click="clickForceRescan">Force Re-Scan</ui-btn>
      <ui-icon-btn class="ml-2" bg-color="error" outlined :loading="removingFolder" icon="delete" @click="clickDeleteFolder" />
    </div>
    <p class="text-lg mb-0.5 text-white text-opacity-75">Folder: {{ folderName }}</p>
    <p class="mb-4 text-xl">Local Media Items ({{ localMediaItems.length }})</p>
    <div v-if="isScanning" class="w-full text-center p-4">
      <p>Scanning...</p>
    </div>
    <div v-else class="w-full media-item-container overflow-y-auto">
      <template v-for="mediaItem in localMediaItems">
        <div :key="mediaItem.id" class="flex my-1">
          <div class="w-12 h-12 bg-primary">
            <img v-if="mediaItem.coverPathSrc" :src="mediaItem.coverPathSrc" class="w-full h-full object-contain" />
          </div>
          <div class="flex-grow px-2">
            <p>{{ mediaItem.name }}</p>
            <p>{{ mediaItem.audioTracks.length }} Tracks</p>
          </div>
          <div class="w-12 h-12 flex items-center justify-center">
            <button v-if="!isMissing" class="shadow-sm text-accent flex items-center justify-center rounded-full" @click.stop="play(mediaItem)">
              <span class="material-icons" style="font-size: 2rem">play_arrow</span>
            </button>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<script>
import { Capacitor } from '@capacitor/core'
import { Dialog } from '@capacitor/dialog'
import StorageManager from '@/plugins/storage-manager'

export default {
  asyncData({ params, query }) {
    return {
      folderId: params.id,
      shouldScan: !!query.scan
    }
  },
  data() {
    return {
      localMediaItems: [],
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
      if (this.localMediaItems.length) {
        deleteMessage = `Are you sure you want to remove this folder and ${this.localMediaItems.length} media items? (does not delete anything in your file system)`
      }
      const { value } = await Dialog.confirm({
        title: 'Confirm',
        message: deleteMessage
      })
      if (value) {
        this.removingFolder = true
        await StorageManager.removeFolder({ folderId: this.folderId })
        this.removingFolder = false
        this.$router.replace('/localMedia/folders')
      }
    },
    play(mediaItem) {
      this.$eventBus.$emit('play-local-item', mediaItem.id)
    },
    async scanFolder(forceAudioProbe = false) {
      this.isScanning = true
      var response = await StorageManager.scanFolder({ folderId: this.folderId, forceAudioProbe })

      if (response && response.localMediaItems) {
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
        if (response.localMediaItems.length) {
          this.localMediaItems = response.localMediaItems.map((mi) => {
            if (mi.coverPath) {
              mi.coverPathSrc = Capacitor.convertFileSrc(mi.coverPath)
            }
            return mi
          })
          console.log('Set Local Media Items', this.localMediaItems.length)
        }
      } else {
        console.log('No Local media items found')
      }
      this.isScanning = false
    },
    async init() {
      var folder = await this.$db.getLocalFolder(this.folderId)
      this.folder = folder

      var items = (await this.$db.getLocalMediaItemsInFolder(this.folderId)) || []
      console.log('Init folder', this.folderId, items)
      this.localMediaItems = items.map((lmi) => {
        return {
          ...lmi,
          coverPathSrc: lmi.coverPath ? Capacitor.convertFileSrc(lmi.coverPath) : null
        }
      })
      if (this.shouldScan) {
        this.scanFolder()
      }
    }
  },
  mounted() {
    this.init()
  }
}
</script>

<style scoped>
.media-item-container {
  height: calc(100vh - 200px);
  max-height: calc(100vh - 200px);
}
</style>