<template>
  <div class="w-full h-full py-6">
    <h1 class="text-2xl px-4">Downloads</h1>

    <div v-if="!isIos" class="w-full max-w-full px-2 py-2">
      <template v-for="folder in localFolders">
        <nuxt-link :to="`/localMedia/folders/${folder.id}`" :key="folder.id" class="flex items-center px-2 py-4 bg-primary rounded-md border-bg mb-1">
          <span class="material-icons text-xl text-yellow-400">folder</span>
          <p class="ml-2">{{ folder.id }}</p>
          <div class="flex-grow" />
          <p class="text-sm italic text-gray-300 px-2 capitalize">{{ folder.mediaType }}s</p>
          <span class="material-icons text-base text-gray-300">arrow_right</span>
        </nuxt-link>
      </template>
      <div v-if="!localFolders.length" class="flex justify-center">
        <p class="text-center">No Media Folders</p>
      </div>
      <div class="flex p-2 border-t border-primary mt-2">
        <div class="flex-grow pr-1">
          <ui-dropdown v-model="newFolderMediaType" :items="mediaTypeItems" />
        </div>
        <ui-btn small class="w-28" @click="selectFolder">Add Folder</ui-btn>
      </div>
    </div>

    <!-- <div v-if="!isIos" class="list-content-body relative w-full overflow-x-hidden overflow-y-auto bg-primary">
      <template v-if="showingDownloads">
        <div v-if="!totalDownloads" class="flex items-center justify-center h-40">
          <p>No Downloads</p>
        </div>
        <ul v-else class="h-full w-full" role="listbox" aria-labelledby="listbox-label">
          <li v-for="download in downloadsDownloading" :key="download.id" class="text-gray-400 select-none relative px-4 py-5 border-b border-white border-opacity-10 bg-black bg-opacity-10">
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
          <li v-for="download in downloadsReady" :key="download.id" class="text-gray-50 select-none relative pr-4 pl-2 py-5 border-b border-white border-opacity-10" @click="jumpToAudiobook(download)">
            <modals-downloads-download-item :download="download" @play="playDownload" @delete="clickDeleteDownload" />
          </li>
        </ul>
      </template>
      <template v-else>
        <div class="w-full h-full">
          <div class="w-full flex justify-around py-4 px-2">
            <ui-btn small color="error" @click="resetFolder">Reset</ui-btn>
          </div>

          <p v-if="isScanning" class="text-center my-8">Scanning Folder..</p>
          <p v-else-if="!mediaScanResults" class="text-center my-8">No Files Found</p>
          <div v-else>
            <div v-for="mediaFolder in mediaScanResults.folders" :key="mediaFolder.uri" class="w-full px-2 py-2">
              <div class="flex items-center">
                <span class="material-icons text-base text-white text-opacity-50">folder</span>
                <p class="ml-1 py-0.5">{{ mediaFolder.name }}</p>
              </div>
              <div v-for="mediaFile in mediaFolder.files" :key="mediaFile.uri" class="ml-3 flex items-center">
                <span class="material-icons text-base text-white text-opacity-50">{{ mediaFile.isAudio ? 'music_note' : 'image' }}</span>
                <p class="ml-1 py-0.5">{{ mediaFile.name }}</p>
              </div>
            </div>
            <div v-for="mediaFile in mediaScanResults.files" :key="mediaFile.uri" class="w-full px-2 py-2">
              <div class="flex items-center">
                <span class="material-icons text-base text-white text-opacity-50">{{ mediaFile.isAudio ? 'music_note' : 'image' }}</span>
                <p class="ml-1 py-0.5">{{ mediaFile.name }}</p>
              </div>
            </div>
          </div>
        </div>
      </template>
    </div> -->
  </div>
</template>

<script>
import { Capacitor } from '@capacitor/core'
import { Dialog } from '@capacitor/dialog'
import AudioDownloader from '@/plugins/audio-downloader'
import StorageManager from '@/plugins/storage-manager'

export default {
  data() {
    return {
      downloadingProgress: {},
      totalSize: 0,
      showingDownloads: true,
      isScanning: false,
      localMediaItems: [],
      localFolders: [],
      newFolderMediaType: 'book',
      mediaTypeItems: [
        {
          value: 'book',
          text: 'Books'
        },
        {
          value: 'podcast',
          text: 'Podcasts'
        }
      ]
    }
  },
  computed: {
    isIos() {
      return this.$platform === 'ios'
    },
    isSocketConnected() {
      return this.$store.state.socketConnected
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
    async selectFolder() {
      var folderObj = await StorageManager.selectFolder({ mediaType: this.newFolderMediaType })
      if (folderObj.error) {
        return this.$toast.error(`Error: ${folderObj.error || 'Unknown Error'}`)
      }

      var indexOfExisting = this.localFolders.findIndex((lf) => lf.id == folderObj.id)
      if (indexOfExisting >= 0) {
        this.localFolders.splice(indexOfExisting, 1, folderObj)
      } else {
        this.localFolders.push(folderObj)
      }

      var permissionsGood = await StorageManager.checkFolderPermissions({ folderUrl: folderObj.contentUrl })

      if (!permissionsGood) {
        this.$toast.error('Folder permissions failed')
        return
      } else {
        this.$toast.success('Folder permission success')
      }

      // await this.searchFolder(folderObj.id)

      this.$router.push(`/localMedia/folders/${folderObj.id}?scan=1`)
    },
    async changeDownloadFolderClick() {
      if (!this.hasStoragePermission) {
        StorageManager.requestStoragePermission()
      } else {
        var folderObj = await StorageManager.selectFolder({ mediaType: 'book' })
        if (folderObj.error) {
          return this.$toast.error(`Error: ${folderObj.error || 'Unknown Error'}`)
        }

        var indexOfExisting = this.localFolders.findIndex((lf) => lf.id == folderObj.id)
        if (indexOfExisting >= 0) {
          this.localFolders.splice(indexOfExisting, 1, folderObj)
        } else {
          this.localFolders.push(folderObj)
        }

        var permissionsGood = await StorageManager.checkFolderPermissions({ folderUrl: folderObj.contentUrl })

        if (!permissionsGood) {
          this.$toast.error('Folder permissions failed')
          return
        } else {
          this.$toast.success('Folder permission success')
        }
        await this.searchFolder(folderObj.id)

        if (this.isSocketConnected) {
          this.$store.dispatch('downloads/linkOrphanDownloads')
        }
      }
    },
    async searchFolder(folderId) {
      this.isScanning = true
      var response = await StorageManager.searchFolder({ folderId })

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
    async resetFolder() {
      await this.$localStore.setDownloadFolder(null)
      this.$store.commit('downloads/setMediaScanResults', {})
      this.$toast.info('Unlinked Folder')
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
        this.deleteDownload(download)
      }
    },
    playDownload(download) {
      this.$store.commit('setPlayOnLoad', true)
      this.$store.commit('setPlayingDownload', download)
      this.show = false
    },
    async deleteDownload(download) {
      console.log('Delete download', download.filename)

      if (this.$store.state.playingDownload && this.$store.state.playingDownload.id === download.id) {
        console.warn('Deleting download when currently playing download - terminate play')
        if (this.$refs.streamContainer) {
          this.$refs.streamContainer.cancelStream()
        }
      }
      if (download.contentUrl) {
        await StorageManager.delete(download)
      }
      this.$store.commit('downloads/removeDownload', download)
    },
    onDownloadProgress(data) {
      var progress = data.progress
      var audiobookId = data.audiobookId

      var downloadObj = this.$store.getters['downloads/getDownload'](audiobookId)
      if (downloadObj) {
        this.$set(this.downloadingProgress, audiobookId, progress)
      }
    },
    async init() {
      this.localFolders = (await this.$db.loadFolders()) || []
      AudioDownloader.addListener('onDownloadProgress', this.onDownloadProgress)
    }
  },
  mounted() {
    this.init()
  },
  beforeDestroy() {
    AudioDownloader.removeListener('onDownloadProgress', this.onDownloadProgress)
  }
}
</script>