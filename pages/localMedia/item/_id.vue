<template>
  <div class="w-full h-full py-6 px-2">
    <div v-if="localLibraryItem" class="w-full h-full">
      <div class="flex items-center mb-4">
        <button v-if="audioTracks.length" class="shadow-sm text-accent flex items-center justify-center rounded-full" @click.stop="play">
          <span class="material-icons" style="font-size: 2rem">play_arrow</span>
        </button>
        <div class="flex-grow" />
        <ui-btn v-if="!removingItem" :loading="isScanning" small @click="clickScan">Scan</ui-btn>
        <ui-btn v-if="!removingItem" :loading="isScanning" small class="ml-2" color="warning" @click="clickForceRescan">Force Re-Scan</ui-btn>
        <ui-icon-btn class="ml-2" bg-color="error" outlined :loading="removingItem" icon="delete" @click="clickDeleteItem" />
      </div>
      <p class="text-lg mb-0.5 text-white text-opacity-75">Folder: {{ folderName }}</p>
      <p class="mb-4 text-xl">{{ mediaMetadata.title }}</p>

       <p class="mb-4 text-xs text-gray-400">{{ libraryItemId || 'Not linked to server library item' }}</p>

      <div v-if="isScanning" class="w-full text-center p-4">
        <p>Scanning...</p>
      </div>
      <div v-else class="w-full media-item-container overflow-y-auto">
        <p class="text-lg mb-2">Audio Tracks</p>
        <template v-for="track in audioTracks">
          <div :key="track.localFileId" class="flex items-center my-1">
            <div class="w-12 h-12 flex items-center justify-center">
              <p class="font-mono font-bold text-xl">{{ track.index }}</p>
            </div>
            <div class="flex-grow px-2">
              <p class="text-sm">{{ track.title }}</p>
            </div>
            <div class="w-20 text-center text-gray-300">
              <p class="text-xs">{{ track.mimeType }}</p>
              <p class="text-sm">{{ $elapsedPretty(track.duration) }}</p>
            </div>
          </div>
        </template>

        <p class="text-lg mb-2 pt-8">Local Files</p>
        <template v-for="file in localFiles">
          <div :key="file.id" class="flex items-center my-1">
            <div class="w-12 h-12 flex items-center justify-center">
              <img v-if="(file.mimeType || '').startsWith('image')" :src="getCapImageSrc(file.contentUrl)" class="w-full h-full object-contain" />
              <span v-else class="material-icons">music_note</span>
            </div>
            <div class="flex-grow px-2">
              <p class="text-sm">{{ file.filename }}</p>
            </div>
            <div class="w-20 text-center text-gray-300">
              <p class="text-xs">{{ file.mimeType }}</p>
              <p class="text-sm">{{ $bytesPretty(file.size) }}</p>
            </div>
          </div>
        </template>

        <p class="py-4">{{ audioTracks.length }} Audio Tracks</p>
      </div>
    </div>
    <div v-else class="w-full h-full">
      <p class="text-lg text-center px-8">{{ failed ? 'Failed to get local library item ' + localLibraryItemId : 'Loading..' }}</p>
    </div>
  </div>
</template>

<script>
import { Capacitor } from '@capacitor/core'
import { Dialog } from '@capacitor/dialog'
import { AbsFileSystem } from '@/plugins/capacitor'

export default {
  asyncData({ params }) {
    return {
      localLibraryItemId: params.id
    }
  },
  data() {
    return {
      failed: false,
      localLibraryItem: null,
      removingItem: false,
      folderId: null,
      folder: null,
      isScanning: false
    }
  },
  computed: {
    localFiles() {
      return this.localLibraryItem ? this.localLibraryItem.localFiles : []
    },
    folderName() {
      return this.folder ? this.folder.name : null
    },
    mediaType() {
      return this.localLibraryItem ? this.localLibraryItem.mediaType : null
    },
        libraryItemId() {
      return this.localLibraryItem ? this.localLibraryItem.libraryItemId : null
    },
    media() {
      return this.localLibraryItem ? this.localLibraryItem.media : null
    },
    mediaMetadata() {
      return this.media ? this.media.metadata || {} : {}
    },
    audioTracks() {
      if (!this.media) return []
      if (this.mediaType == 'book') {
        return this.media.tracks || []
      } else {
        return this.media.episodes || []
      }
    }
  },
  methods: {
    play() {
      this.$eventBus.$emit('play-item', this.localLibraryItemId)
    },
    getCapImageSrc(contentUrl) {
      return Capacitor.convertFileSrc(contentUrl)
    },
    clickScan() {
      this.scanItem()
    },
    clickForceRescan() {
      this.scanItem(true)
    },
    async clickDeleteItem() {
      var deleteMessage = 'Are you sure you want to remove this local library item? (does not delete anything in your file system)'
      const { value } = await Dialog.confirm({
        title: 'Confirm',
        message: deleteMessage
      })
      if (value) {
        this.removingItem = true
        await AbsFileSystem.removeLocalLibraryItem({ localLibraryItemId: this.localLibraryItemId })
        this.removingItem = false
        this.$router.replace(`/localMedia/folders/${this.folderId}`)
      }
    },
    play(mediaItem) {
      this.$eventBus.$emit('play-item', mediaItem.id)
    },
    async scanItem(forceAudioProbe = false) {
      this.isScanning = true
      var response = await AbsFileSystem.scanLocalLibraryItem({ localLibraryItemId: this.localLibraryItemId, forceAudioProbe })

      if (response && response.localLibraryItem) {
        if (response.updated) {
          this.$toast.success('Local item was updated')
          this.localLibraryItem = response.localLibraryItem
        } else {
          this.$toast.info('Local item was up to date')
        }
      } else {
        console.log('Failed')
        this.$toast.error('Something went wrong..')
      }
      this.isScanning = false
    },
    async init() {
      this.localLibraryItem = await this.$db.getLocalLibraryItem(this.localLibraryItemId)
      if (!this.localLibraryItem) {
        console.error('Failed to get local library item', this.localLibraryItemId)
        this.failed = true
        return
      }

      console.log('Got local library item', JSON.stringify(this.localLibraryItem))
      this.folderId = this.localLibraryItem.folderId
      this.folder = await this.$db.getLocalFolder(this.folderId)
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