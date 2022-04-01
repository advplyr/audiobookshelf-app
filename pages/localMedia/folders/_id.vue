<template>
  <div class="w-full h-full py-6 px-2">
    <div class="flex justify-between mb-4">
      <ui-btn to="/localMedia/folders">Back</ui-btn>
      <ui-btn :loading="isScanning" @click="searchFolder">Scan</ui-btn>
    </div>
    <p class="text-lg mb-0.5 text-white text-opacity-75">Folder: {{ folderId }}</p>
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
      isScanning: false
    }
  },
  computed: {},
  methods: {
    play(mediaItem) {
      this.$eventBus.$emit('play-local-item', mediaItem.id)
    },
    async searchFolder() {
      this.isScanning = true
      var response = await StorageManager.searchFolder({ folderId: this.folderId })

      if (response && response.localMediaItems) {
        this.localMediaItems = response.localMediaItems.map((mi) => {
          if (mi.coverPath) {
            mi.coverPathSrc = Capacitor.convertFileSrc(mi.coverPath)
          }
          return mi
        })
        console.log('Set Local Media Items', this.localMediaItems.length)
      } else {
        console.log('No Local media items found')
      }

      this.isScanning = false
    },
    async init() {
      var items = (await this.$db.loadLocalMediaItemsInFolder(this.folderId)) || []
      console.log('Init folder', this.folderId, items)
      this.localMediaItems = items.map((lmi) => {
        return {
          ...lmi,
          coverPathSrc: lmi.coverPath ? Capacitor.convertFileSrc(lmi.coverPath) : null
        }
      })
      if (this.shouldScan) {
        this.searchFolder()
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