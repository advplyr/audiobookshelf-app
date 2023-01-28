<template>
  <div v-if="downloadItemPartsRemaining.length" @click="clickedIt">
    <widgets-circle-progress :value="progress" :count="downloadItemPartsRemaining.length" />
  </div>
</template>

<script>
import { AbsDownloader } from '@/plugins/capacitor'

export default {
  data() {
    return {
      downloadItemListener: null,
      completeListener: null,
      itemPartUpdateListener: null
    }
  },
  computed: {
    downloadItems() {
      return this.$store.state.globals.itemDownloads
    },
    downloadItemParts() {
      let parts = []
      this.downloadItems.forEach((di) => parts.push(...di.downloadItemParts))
      return parts
    },
    downloadItemPartsRemaining() {
      return this.downloadItemParts.filter((dip) => !dip.completed)
    },
    progress() {
      let totalBytes = 0
      let totalBytesDownloaded = 0
      this.downloadItemParts.forEach((dip) => {
        totalBytes += dip.fileSize
        totalBytesDownloaded += dip.bytesDownloaded
      })

      if (!totalBytes) return 0
      return Math.min(1, totalBytesDownloaded / totalBytes)
    }
  },
  methods: {
    clickedIt() {
      this.$router.push('/downloading')
    },
    onItemDownloadComplete(data) {
      console.log('DownloadProgressIndicator onItemDownloadComplete', JSON.stringify(data))
      if (!data || !data.libraryItemId) {
        console.error('Invalid item downlaod complete payload')
        return
      }

      if (!data.localLibraryItem) {
        this.$toast.error('Item download complete but failed to create library item')
      } else {
        this.$toast.success(`Item "${data.localLibraryItem.media.metadata.title}" download finished`)
        this.$eventBus.$emit('new-local-library-item', data.localLibraryItem)
      }

      if (data.localMediaProgress) {
        console.log('onItemDownloadComplete updating local media progress', data.localMediaProgress.id)
        this.$store.commit('globals/updateLocalMediaProgress', data.localMediaProgress)
      }

      this.$store.commit('globals/removeItemDownload', data.libraryItemId)
    },
    onDownloadItem(downloadItem) {
      console.log('DownloadProgressIndicator onDownloadItem', JSON.stringify(downloadItem))

      downloadItem.itemProgress = 0
      downloadItem.episodes = downloadItem.downloadItemParts.filter((dip) => dip.episode).map((dip) => dip.episode)

      this.$store.commit('globals/addUpdateItemDownload', downloadItem)
    },
    onDownloadItemPartUpdate(itemPart) {
      this.$store.commit('globals/updateDownloadItemPart', itemPart)
    }
  },
  mounted() {
    this.downloadItemListener = AbsDownloader.addListener('onDownloadItem', (data) => this.onDownloadItem(data))
    this.itemPartUpdateListener = AbsDownloader.addListener('onDownloadItemPartUpdate', (data) => this.onDownloadItemPartUpdate(data))
    this.completeListener = AbsDownloader.addListener('onItemDownloadComplete', (data) => this.onItemDownloadComplete(data))
  },
  beforeDestroy() {
    if (this.downloadItemListener) this.downloadItemListener.remove()
    if (this.completeListener) this.completeListener.remove()
    if (this.itemPartUpdateListener) this.itemPartUpdateListener.remove()
  }
}
</script>