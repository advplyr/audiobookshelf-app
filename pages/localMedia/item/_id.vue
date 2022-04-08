<template>
  <div class="w-full h-full py-6 px-4">
    <div v-if="localLibraryItem" class="w-full h-full">
      <div class="flex items-center mb-2">
        <p class="text-base font-book font-semibold">{{ mediaMetadata.title }}</p>
        <div class="flex-grow" />

        <button v-if="audioTracks.length" class="shadow-sm text-accent flex items-center justify-center rounded-full mx-2" @click.stop="play">
          <span class="material-icons" style="font-size: 2rem">play_arrow</span>
        </button>
        <span class="material-icons" @click="showItemDialog">more_vert</span>
      </div>

      <p class="text-sm mb-0.5 text-white text-opacity-75">Folder: {{ folderName }}</p>

      <p class="mb-4 text-xs text-gray-400">{{ libraryItemId ? 'Linked to item on server ' + liServerAddress : 'Not linked to server item' }}</p>

      <div v-if="isScanning" class="w-full text-center p-4">
        <p>Scanning...</p>
      </div>
      <div v-else class="w-full media-item-container overflow-y-auto">
        <p class="text-base mb-2">Audio Tracks ({{ audioTracks.length }})</p>
        <template v-for="track in audioTracks">
          <div :key="track.localFileId" class="flex items-center my-1">
            <div class="w-12 h-12 flex items-center justify-center" style="min-width: 48px">
              <p class="font-mono font-bold text-xl">{{ track.index }}</p>
            </div>
            <div class="flex-grow px-2">
              <p class="text-sm">{{ track.title }}</p>
            </div>
            <div class="w-20 text-center text-gray-300" style="min-width: 80px">
              <p class="text-xs">{{ track.mimeType }}</p>
              <p class="text-sm">{{ $elapsedPretty(track.duration) }}</p>
            </div>
            <div class="w-12 h-12 flex items-center justify-center" style="min-width: 48px">
              <span class="material-icons" @click="showTrackDialog(track)">more_vert</span>
            </div>
          </div>
        </template>

        <p v-if="otherFiles.length" class="text-lg mb-2 pt-8">Other Files</p>
        <template v-for="file in otherFiles">
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
      </div>
    </div>
    <div v-else class="w-full h-full">
      <p class="text-lg text-center px-8">{{ failed ? 'Failed to get local library item ' + localLibraryItemId : 'Loading..' }}</p>
    </div>

    <modals-dialog v-model="showDialog" :items="dialogItems" @action="dialogAction" />
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
      isScanning: false,
      showDialog: false,
      selectedAudioTrack: null
    }
  },
  computed: {
    basePath() {
      return this.localLibraryItem ? this.localLibraryItem.basePath : null
    },
    localFiles() {
      return this.localLibraryItem ? this.localLibraryItem.localFiles : []
    },
    otherFiles() {
      if (!this.localFiles.filter) {
        console.error('Invalid local files', this.localFiles)
        return []
      }
      return this.localFiles.filter((lf) => {
        return !this.audioTracks.find((at) => at.localFileId == lf.id)
      })
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
    liServerAddress() {
      return this.localLibraryItem ? this.localLibraryItem.serverAddress : null
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
    },
    dialogItems() {
      if (this.selectedAudioTrack) {
        return [
          {
            text: 'Hard Delete',
            value: 'track-delete'
          }
        ]
      } else {
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
          },
          {
            text: 'Hard Delete',
            value: 'delete'
          }
        ]
      }
    }
  },
  methods: {
    showItemDialog() {
      this.selectedAudioTrack = null
      this.showDialog = true
    },
    showTrackDialog(track) {
      this.selectedAudioTrack = track
      this.showDialog = true
    },
    play() {
      this.$eventBus.$emit('play-item', this.localLibraryItemId)
    },
    getCapImageSrc(contentUrl) {
      return Capacitor.convertFileSrc(contentUrl)
    },
    dialogAction(action) {
      console.log('Dialog action', action)
      if (action == 'scan') {
        this.scanItem()
      } else if (action == 'rescan') {
        this.scanItem(true)
      } else if (action == 'remove') {
        this.removeItem()
      } else if (action == 'delete') {
        this.deleteItem()
      } else if (action == 'track-delete') {
        this.deleteTrack()
      }
      this.showDialog = false
    },
    getLocalFileForTrack(localFileId) {
      return this.localFiles.find((lf) => lf.id == localFileId)
    },
    async deleteTrack() {
      if (!this.selectedAudioTrack) {
        return
      }
      var localFile = this.getLocalFileForTrack(this.selectedAudioTrack.localFileId)
      if (!localFile) {
        this.$toast.error('Audio track does not have matching local file..')
        return
      }
      var trackPath = localFile ? localFile.basePath : this.selectedAudioTrack.title
      const { value } = await Dialog.confirm({
        title: 'Confirm',
        message: `Warning! This will delete the audio file "${trackPath}" from your file system. Are you sure?`
      })
      if (value) {
        var res = await AbsFileSystem.deleteTrackFromItem({ id: this.localLibraryItem.id, trackLocalFileId: this.selectedAudioTrack.localFileId, trackContentUrl: this.selectedAudioTrack.contentUrl })
        if (res && res.id) {
          this.$toast.success('Deleted track successfully')
          this.localLibraryItem = res
        } else this.$toast.error('Failed to delete')
      }
    },
    async deleteItem() {
      const { value } = await Dialog.confirm({
        title: 'Confirm',
        message: `Warning! This will delete the folder "${this.basePath}" and all contents. Are you sure?`
      })
      if (value) {
        var res = await AbsFileSystem.deleteItem(this.localLibraryItem)
        if (res && res.success) {
          this.$toast.success('Deleted Successfully')
          this.$router.replace(`/localMedia/folders/${this.folderId}`)
        } else this.$toast.error('Failed to delete')
      }
    },
    async removeItem() {
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
    async scanItem(forceAudioProbe = false) {
      if (this.isScanning) return

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