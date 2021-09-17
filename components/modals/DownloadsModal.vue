<template>
  <modals-modal v-model="show" width="100%" height="100%">
    <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" @click="show = false">
      <p class="absolute top-6 left-2 text-2xl">Downloads</p>

      <div class="w-full overflow-x-hidden overflow-y-auto bg-primary rounded-lg border border-white border-opacity-20" style="max-height: 75%" @click.stop>
        <div v-if="!totalDownloads" class="flex items-center justify-center h-40">
          <p>No Downloads</p>
        </div>
        <ul class="h-full w-full" role="listbox" aria-labelledby="listbox-label">
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
              <div class="flex items-center justify-center">
                <img v-if="download.cover" :src="download.cover" class="w-10 h-16 object-contain" />
                <img v-else src="/book_placeholder.jpg" class="w-10 h-16 object-contain" />
                <div class="pl-2 w-1/2">
                  <p class="font-normal truncate text-sm">{{ download.audiobook.book.title }}</p>
                  <p class="font-normal truncate text-xs text-gray-400">{{ download.audiobook.book.author }}</p>
                </div>
                <div class="flex-grow" />
                <div v-if="download.isIncomplete" class="shadow-sm text-warning flex items-center justify-center rounded-full mr-4">
                  <span class="material-icons">error_outline</span>
                </div>
                <button class="shadow-sm text-accent flex items-center justify-center rounded-full" @click.stop="clickedOption(download)">
                  <span class="material-icons" style="font-size: 2rem">play_arrow</span>
                </button>
                <div class="shadow-sm text-error flex items-center justify-center rounded-ful ml-4" @click.stop="clickDelete(download)">
                  <span class="material-icons" style="font-size: 1.2rem">delete</span>
                </div>
              </div>
            </li>
          </template>
          <template v-for="download in orphanDownloads">
            <li :key="download.id" class="text-gray-50 select-none relative cursor-pointer px-4 py-5 border-b border-white border-opacity-10">
              <div class="flex items-center justify-center">
                <div class="w-3/4">
                  <span class="text-xs text-gray-400">Unknown Audio File</span>
                  <p class="font-normal truncate text-sm">{{ download.filename }}</p>
                </div>
                <!-- <span class="font-normal block truncate text-sm pr-2">{{ download.filename }}</span> -->
                <div class="flex-grow" />
                <div class="shadow-sm text-warning flex items-center justify-center rounded-full">
                  <span class="material-icons">error_outline</span>
                </div>
                <div class="shadow-sm text-error flex items-center justify-center rounded-ful ml-4" @click="clickDelete(download)">
                  <span class="material-icons" style="font-size: 1.2rem">delete</span>
                </div>
              </div>
            </li>
          </template>
        </ul>
      </div>
    </div>
  </modals-modal>
</template>

<script>
import { Dialog } from '@capacitor/dialog'

export default {
  data() {
    return {
      downloadingProgress: {}
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
    totalDownloads() {
      return this.downloadsReady.length + this.orphanDownloads.length + this.downloadsDownloading.length
    },
    downloadsDownloading() {
      return this.downloads.filter((d) => d.isDownloading || d.isPreparing)
    },
    downloadsReady() {
      return this.downloads.filter((d) => !d.isDownloading && !d.isPreparing)
    },
    orphanDownloads() {
      return this.$store.state.downloads.orphanDownloads
      // return [
      //   {
      //     id: 'asdf',
      //     filename: 'Test Title 1 another long title on the downloading widget.jpg'
      //   }
      // ]
    },
    downloads() {
      return this.$store.state.downloads.downloads
      // return [
      //   {
      //     id: 'asdf1',
      //     audiobook: {
      //       book: {
      //         title: 'Test Title 1 another long title on the downloading widget',
      //         author: 'Test Author 1'
      //       }
      //     },
      //     isDownloading: true
      //   },
      //   {
      //     id: 'asdf2',
      //     audiobook: {
      //       book: {
      //         title: 'Test Title 2',
      //         author: 'Test Author 2 long test author to test the overflow capabilities'
      //       }
      //     },
      //     isReady: true
      //   }
      // ]
    }
  },
  methods: {
    updateDownloadProgress({ audiobookId, progress }) {
      this.$set(this.downloadingProgress, audiobookId, progress)
    },
    jumpToAudiobook(download) {
      this.show = false
      this.$router.push(`/audiobook/${download.id}`)
    },
    async clickDelete(download) {
      const { value } = await Dialog.confirm({
        title: 'Confirm',
        message: 'Delete this download?'
      })
      if (value) {
        this.$emit('deleteDownload', download)
      }
    },
    clickedOption(download) {
      console.log('Clicked download', download)
      this.$emit('selectDownload', download)
      this.show = false
    }
  },
  mounted() {}
}
</script>