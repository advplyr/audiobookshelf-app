<template>
  <div v-if="remainingParts" @click="clickedIt">
    <widgets-circle-progress :value="progress" :count="remainingParts" />
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
      remainingParts: 0,
      progress: 0,
      progressTimer: null
    }
  },
  methods: {
    // Combine frequent native events into one queue-total calculation per 250 ms; store updates stay immediate.
    scheduleProgressUpdate() {
      if (this.progressTimer !== null) return
      this.progressTimer = setTimeout(() => {
        this.progressTimer = null
        let totalBytes = 0
        let downloadedBytes = 0
        let remainingParts = 0
        for (const item of this.$store.state.globals.itemDownloads) {
          for (const part of item.downloadItemParts) {
            totalBytes += Number(part.fileSize)
            downloadedBytes += Number(part.bytesDownloaded)
            if (!part.completed) remainingParts++
          }
        }
        this.remainingParts = remainingParts
        this.progress = totalBytes ? Math.min(1, downloadedBytes / totalBytes) : 0
      }, 250)
    },
    clickedIt() {
      this.$router.push('/downloading')
    },
    // Accept full-item completions from books/iOS and compact Android podcast updates.
    onItemDownloadComplete(data) {
      if (!data || !data.libraryItemId) {
        console.error('Invalid item download complete payload')
        return
      }

      if (data.localLibraryItem) {
        this.$eventBus.$emit('new-local-library-item', data.localLibraryItem)
      } else if (data.localPodcastDelta) {
        this.$eventBus.$emit('local-podcast-download-delta', data.localPodcastDelta)
      } else if (!data.localLibraryItemId) {
        this.$toast.error(this.$strings.MessageItemDownloadCompleteFailedToCreate)
      }

      if (data.localMediaProgress) {
        console.log('onItemDownloadComplete updating local media progress', data.localMediaProgress.id)
        this.$store.commit('globals/updateLocalMediaProgress', data.localMediaProgress)
      }

      this.$store.commit('globals/removeItemDownload', data.libraryItemId)
      this.scheduleProgressUpdate()
    },
    onDownloadItem(downloadItem) {
      downloadItem.itemProgress = 0
      downloadItem.episodes = downloadItem.downloadItemParts.filter((dip) => dip.episode).map((dip) => dip.episode)

      this.$store.commit('globals/addUpdateItemDownload', downloadItem)
      this.scheduleProgressUpdate()
    },
    onDownloadItemPartUpdate(itemPart) {
      this.$store.commit('globals/updateDownloadItemPart', itemPart)
      this.scheduleProgressUpdate()
    },
    onQueueChanged(data) {
      if (!data.hasWork) this.$store.commit('globals/clearItemDownloads')
      this.scheduleProgressUpdate()
    }
  },
  async mounted() {
    this.downloadItemListener = await AbsDownloader.addListener('onDownloadItem', (data) => this.onDownloadItem(data))
    this.itemPartUpdateListener = await AbsDownloader.addListener('onDownloadItemPartUpdate', (data) => this.onDownloadItemPartUpdate(data))
    this.queueChangedListener = await AbsDownloader.addListener('onQueueChanged', (data) => this.onQueueChanged(data))
    this.completeListener = await AbsDownloader.addListener('onItemDownloadComplete', (data) => this.onItemDownloadComplete(data))
  },
  beforeDestroy() {
    clearTimeout(this.progressTimer)
    this.downloadItemListener?.remove()
    this.completeListener?.remove()
    this.itemPartUpdateListener?.remove()
    this.queueChangedListener?.remove()
  }
}
</script>
