<template>
  <modals-modal v-model="show" width="100%" height="100%">
    <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" @click="show = false">
      <p class="absolute top-6 left-2 text-2xl">Downloads</p>

      <div class="absolute top-16 left-0 right-0 w-full px-2 py-1" :class="hasStoragePermission ? '' : 'text-error'">
        <div class="flex items-center">
          <span class="material-icons" @click="changeDownloadFolderClick">{{ hasStoragePermission ? 'folder' : 'error' }}</span>
          <p v-if="hasStoragePermission" class="text-sm px-4" @click="changeDownloadFolderClick">{{ downloadFolderSimplePath || 'No Download Folder Selected' }}</p>
          <p v-else class="text-sm px-4" @click="changeDownloadFolderClick">No Storage Permissions. Click here</p>
        </div>
        <!-- <p v-if="hasStoragePermission" class="text-xs text-gray-400 break-all max-w-full">{{ downloadFolderUri }}</p> -->
      </div>

      <div v-if="totalSize" class="absolute bottom-0 left-0 right-0 w-full py-3 text-center">
        <p class="text-sm text-center text-gray-300">Total: {{ $bytesPretty(totalSize) }}</p>
      </div>

      <div v-if="downloadFolder && hasStoragePermission" class="w-full relative mt-10" @click.stop>
        <div class="w-full h-10 relative">
          <div class="absolute top-px left-0 z-10 w-full h-full flex">
            <div class="flex-grow h-full bg-primary rounded-t-md mr-px" @click="showingDownloads = true">
              <div class="flex items-center justify-center rounded-t-md border-t border-l border-r border-white border-opacity-20 h-full" :class="showingDownloads ? 'text-gray-100' : 'border-b bg-black bg-opacity-20 text-gray-400'">
                <p>Downloads</p>
              </div>
            </div>
            <div class="flex-grow h-full bg-primary rounded-t-md ml-px" @click="showingDownloads = false">
              <div class="flex items-center justify-center h-full rounded-t-md border-t border-l border-r border-white border-opacity-20" :class="!showingDownloads ? 'text-gray-100' : 'border-b bg-black bg-opacity-20 text-gray-400'">
                <p>Files</p>
              </div>
            </div>
          </div>
        </div>

        <div class="list-content-body relative w-full overflow-x-hidden overflow-y-auto bg-primary rounded-b-lg border border-white border-opacity-20" style="max-height: 70vh; height: 70vh">
          <template v-if="showingDownloads">
            <div v-if="!totalDownloads" class="flex items-center justify-center h-40">
              <p>No Downloads</p>
            </div>
            <ul v-else class="h-full w-full" role="listbox" aria-labelledby="listbox-label">
              <template v-for="download in downloadsDownloading">
                <li :key="download.id" class="text-gray-400 select-none relative px-4 py-5 border-b border-white border-opacity-10 bg-black bg-opacity-10">
                  <div class="flex items-center justify-center">
                    <div class="w-3/4">
                      <span class="text-xs">({{ downloadingProgress[download.id] || 0 }}%) {{ download.isPreparing ? 'Preparing' : 'Downloading' }}...</span>
                      <p class="font-normal truncate text-sm">{{ download.audiobook.book.title }}</p>
                    </div>
                    <div class="flex-grow" />

                    <div class="shadow-sm text-white flex items-center justify-center rounded-full animate-spin">
                      <span class="material-icons">refresh</span>
                    </div>
                  </div>
                </li>
              </template>
              <template v-for="download in downloadsReady">
                <li :key="download.id" class="text-gray-50 select-none relative pr-4 pl-2 py-5 border-b border-white border-opacity-10" @click="jumpToAudiobook(download)">
                  <modals-downloads-download-item :download="download" @play="playDownload" @delete="clickDeleteDownload" />
                </li>
              </template>
            </ul>
          </template>
          <template v-else>
            <div class="w-full h-full">
              <div class="w-full flex justify-around py-4 px-2">
                <ui-btn small @click="searchFolder">Re-Scan</ui-btn>
                <ui-btn small @click="changeDownloadFolderClick">Change Folder</ui-btn>
                <ui-btn small color="error" @click="resetFolder">Reset</ui-btn>
              </div>
              <p v-if="isScanning" class="text-center my-8">Scanning Folder..</p>
              <p v-else-if="!mediaScanResults" class="text-center my-8">No Files Found</p>
              <template v-else>
                <template v-for="mediaFolder in mediaScanResults.folders">
                  <div :key="mediaFolder.uri" class="w-full px-2 py-2">
                    <div class="flex items-center">
                      <span class="material-icons text-base text-white text-opacity-50">folder</span>
                      <p class="ml-1 py-0.5">{{ mediaFolder.name }}</p>
                    </div>
                    <div v-for="mediaFile in mediaFolder.files" :key="mediaFile.uri" class="ml-3 flex items-center">
                      <span class="material-icons text-base text-white text-opacity-50">{{ mediaFile.isAudio ? 'music_note' : 'image' }}</span>
                      <p class="ml-1 py-0.5">{{ mediaFile.name }}</p>
                    </div>
                  </div>
                </template>
                <template v-for="mediaFile in mediaScanResults.files">
                  <div :key="mediaFile.uri" class="w-full px-2 py-2">
                    <div class="flex items-center">
                      <span class="material-icons text-base text-white text-opacity-50">{{ mediaFile.isAudio ? 'music_note' : 'image' }}</span>
                      <p class="ml-1 py-0.5">{{ mediaFile.name }}</p>
                    </div>
                  </div>
                </template>
              </template>
            </div>
          </template>
        </div>
      </div>
      <div v-else class="list-content-body relative w-full overflow-x-hidden overflow-y-auto bg-primary rounded-b-lg border border-white border-opacity-20 py-8 px-4" @click.stop>
        <ui-btn class="w-full" color="info" @click="changeDownloadFolderClick">Select Folder</ui-btn>
      </div>
    </div>
  </modals-modal>
</template>

<script>
import { Dialog } from '@capacitor/dialog'
import AudioDownloader from '@/plugins/audio-downloader'
import StorageManager from '@/plugins/storage-manager'

export default {
  data() {
    return {
      downloadingProgress: {},
      totalSize: 0,
      showingDownloads: true,
      isScanning: false
    }
  },
  watch: {
    async show(newValue) {
      if (newValue) {
        await this.$localStore.getDownloadFolder()
        this.setTotalSize()
      }
    }
  },
  computed: {
    show: {
      get() {
        return this.$store.state.downloads.showModal
      },
      set(val) {
        this.$store.commit('downloads/setShowModal', val)
      }
    },
    hasStoragePermission() {
      return this.$store.state.hasStoragePermission
    },
    downloadFolder() {
      return this.$store.state.downloadFolder
    },
    downloadFolderSimplePath() {
      return this.downloadFolder ? this.downloadFolder.simplePath : null
    },
    downloadFolderUri() {
      return this.downloadFolder ? this.downloadFolder.uri : null
    },
    totalDownloads() {
      return this.downloadsReady.length + this.downloadsDownloading.length
    },
    downloadsDownloading() {
      return this.downloads.filter((d) => d.isDownloading || d.isPreparing)
    },
    downloadsReady() {
      return this.downloads.filter((d) => !d.isDownloading && !d.isPreparing)
    },
    downloads() {
      return this.$store.state.downloads.downloads
    },
    mediaScanResults() {
      return this.$store.state.downloads.mediaScanResults
    }
  },
  methods: {
    setTotalSize() {
      var totalSize = 0
      this.downloadsReady.forEach((dl) => {
        totalSize += dl.size && !isNaN(dl.size) ? Number(dl.size) : 0
      })
      this.totalSize = totalSize
    },
    async changeDownloadFolderClick() {
      if (!this.hasStoragePermission) {
        console.log('Requesting Storage Permission')
        StorageManager.requestStoragePermission()
      } else {
        var folderObj = await StorageManager.selectFolder()
        if (folderObj.error) {
          return this.$toast.error(`Error: ${folderObj.error || 'Unknown Error'}`)
        }

        var permissionsGood = await StorageManager.checkFolderPermissions({ folderUrl: folderObj.uri })
        console.log('Storage Permission check folder ' + permissionsGood)

        if (!permissionsGood) {
          this.$toast.error('Folder permissions failed')
          return
        } else {
          this.$toast.success('Folder permission success')
        }

        await this.$localStore.setDownloadFolder(folderObj)

        this.searchFolder()
      }
    },
    async searchFolder() {
      this.isScanning = true
      var response = await StorageManager.searchFolder({ folderUrl: this.downloadFolderUri })
      var searchResults = response
      searchResults.folders = JSON.parse(searchResults.folders)
      searchResults.files = JSON.parse(searchResults.files)

      if (searchResults.folders.length) {
        console.log('Search results folders length', searchResults.folders.length)

        searchResults.folders = searchResults.folders.map((sr) => {
          if (sr.files) {
            sr.files = JSON.parse(sr.files)
          }
          return sr
        })
        this.$store.commit('downloads/setMediaScanResults', searchResults)
      } else {
        this.$toast.warning('No audio or image files found')
      }
      this.isScanning = false
    },
    async resetFolder() {
      await this.$localStore.setDownloadFolder(null)
      this.$store.commit('downloads/setMediaScanResults', {})
      this.$toast.info('Unlinked Folder')
    },
    updateDownloadProgress({ audiobookId, progress }) {
      this.$set(this.downloadingProgress, audiobookId, progress)
    },
    jumpToAudiobook(download) {
      this.show = false
      this.$router.push(`/audiobook/${download.id}`)
    },
    async clickDeleteDownload(download) {
      const { value } = await Dialog.confirm({
        title: 'Confirm',
        message: 'Delete this download?'
      })
      if (value) {
        this.$emit('deleteDownload', download)
      }
    },
    playDownload(download) {
      this.$store.commit('setPlayOnLoad', true)
      this.$store.commit('setPlayingDownload', download)
      this.show = false
    }
  },
  mounted() {}
}
</script>

<style>
.list-content-body {
  max-height: calc(75% - 40px);
}
</style>