<template>
  <div v-if="numPartsRemaining > 0">
    <widgets-circle-progress :value="progress" :count="numPartsRemaining" />
  </div>
</template>

<script>
import { AbsDownloader } from '@/plugins/capacitor'

export default {
  data() {
    return {
      updateListener: null,
      completeListener: null,
      itemDownloadingMap: {}
    }
  },
  computed: {
    numItemPartsComplete() {
      var total = 0
      Object.values(this.itemDownloadingMap).map((item) => (total += item.partsCompleted))
      return total
    },
    numPartsRemaining() {
      return this.numTotalParts - this.numItemPartsComplete
    },
    numTotalParts() {
      var total = 0
      Object.values(this.itemDownloadingMap).map((item) => (total += item.totalParts))
      return total
    },
    progress() {
      var numItems = Object.keys(this.itemDownloadingMap).length
      if (!numItems) return 0
      var totalProg = 0
      Object.values(this.itemDownloadingMap).map((item) => (totalProg += item.itemProgress))
      return totalProg / numItems
    }
  },
  methods: {
    onItemDownloadUpdate(data) {
      console.log('DownloadProgressIndicator onItemDownloadUpdate', JSON.stringify(data))
      if (!data || !data.downloadItemParts) {
        console.error('Invalid item update payload')
        return
      }
      var downloadItemParts = data.downloadItemParts
      var partsCompleted = 0
      var totalPartsProgress = 0
      var partsRemaining = 0
      downloadItemParts.forEach((dip) => {
        if (dip.completed) {
          totalPartsProgress += 1
          partsCompleted++
        } else {
          var progPercent = dip.progress / 100
          totalPartsProgress += progPercent
          partsRemaining++
        }
      })
      var itemProgress = totalPartsProgress / downloadItemParts.length

      var update = {
        id: data.id,
        partsRemaining,
        partsCompleted,
        totalParts: downloadItemParts.length,
        itemProgress
      }
      data.itemProgress = itemProgress

      console.log('Saving item update download payload', JSON.stringify(update))
      this.$set(this.itemDownloadingMap, update.id, update)

      this.$store.commit('globals/addUpdateItemDownload', data)
    },
    onItemDownloadComplete(data) {
      console.log('DownloadProgressIndicator onItemDownloadComplete', JSON.stringify(data))
      if (!data || !data.libraryItemId) {
        console.error('Invalid item downlaod complete payload')
        return
      }

      if (this.itemDownloadingMap[data.libraryItemId]) {
        delete this.itemDownloadingMap[data.libraryItemId]
      } else {
        console.warn('Item download complete but not found in item downloading map', data.libraryItemId)
      }
      if (!data.localLibraryItem) {
        this.$toast.error('Item download complete but failed to create library item')
      } else {
        this.$toast.success(`Item "${data.localLibraryItem.media.metadata.title}" download finished`)
        this.$eventBus.$emit('new-local-library-item', data.localLibraryItem)
      }

      this.$store.commit('globals/removeItemDownload', data.libraryItemId)
    }
  },
  mounted() {
    this.updateListener = AbsDownloader.addListener('onItemDownloadUpdate', (data) => this.onItemDownloadUpdate(data))
    this.completeListener = AbsDownloader.addListener('onItemDownloadComplete', (data) => this.onItemDownloadComplete(data))
  },
  beforeDestroy() {
    if (this.updateListener) this.updateListener.remove()
    if (this.completeListener) this.completeListener.remove()
  }
}
</script>