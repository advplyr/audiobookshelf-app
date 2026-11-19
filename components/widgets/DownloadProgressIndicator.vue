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
      itemPartUpdateListener: null,
      queueChangedListener: null,
      restoringDownloads: false
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
    },
    isIos() {
      return this.$platform === 'ios'
    }
  },
  methods: {
    clickedIt() {
      this.$router.push('/downloading')
    },
    onItemDownloadComplete(data) {
      console.log('DownloadProgressIndicator onItemDownloadComplete', JSON.stringify(data))
      if (!data || !data.libraryItemId) {
        console.error('Invalid item download complete payload')
        return
      }

      if (!data.localLibraryItem) {
        this.$toast.error(this.$strings.MessageItemDownloadCompleteFailedToCreate)
      } else {
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
      // Progress arrived for an item the store doesn't know about (e.g. app was
      // relaunched mid-download): pull the active downloads from native
      if (this.isIos && !this.downloadItems.some((i) => i.id === itemPart.downloadItemId)) {
        this.restoreActiveDownloads()
      }
    },
    async restoreActiveDownloads() {
      if (this.restoringDownloads) return
      this.restoringDownloads = true
      try {
        const result = await AbsDownloader.getActiveDownloadItems()
        const items = result && result.downloadItemsJson ? JSON.parse(result.downloadItemsJson) : []
        items.forEach((downloadItem) => this.onDownloadItem(downloadItem))
      } catch (e) {
        console.error('Failed to restore active downloads', e)
      }
      this.restoringDownloads = false
    },
    onQueueChanged(data) {
      if (!data.hasWork) this.$store.commit('globals/clearItemDownloads')
    }
  },
  async mounted() {
    this.downloadItemListener = await AbsDownloader.addListener('onDownloadItem', (data) => this.onDownloadItem(data))
    this.itemPartUpdateListener = await AbsDownloader.addListener('onDownloadItemPartUpdate', (data) => this.onDownloadItemPartUpdate(data))
    this.queueChangedListener = await AbsDownloader.addListener('onQueueChanged', (data) => this.onQueueChanged(data))
    this.completeListener = await AbsDownloader.addListener('onItemDownloadComplete', (data) => this.onItemDownloadComplete(data))

    // Restore downloads still running natively (they survive app relaunches).
    // Retried because a background URLSession reconstitutes its tasks
    // asynchronously after launch and early queries can come back empty.
    if (this.isIos) {
      this.restoreActiveDownloads()
      this.restoreTimers = [2000, 6000, 15000].map((delay) => setTimeout(() => {
        if (!this.downloadItems.length) this.restoreActiveDownloads()
      }, delay))
    }
  },
  beforeDestroy() {
    this.downloadItemListener?.remove()
    this.completeListener?.remove()
    this.itemPartUpdateListener?.remove()
    this.queueChangedListener?.remove()
    this.restoreTimers?.forEach((t) => clearTimeout(t))
  }
}
</script>
