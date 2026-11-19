<template>
  <div class="w-full h-full py-6 px-4">
    <div class="flex items-center mb-2">
      <p class="text-base font-semibold">{{ $strings.LabelFolder }}: {{ folderName }}</p>
      <div class="flex-grow" />

      <span v-if="dialogItems.length" class="material-symbols text-2xl" @click="showDialog = true">more_vert</span>
    </div>

    <p class="text-sm mb-4 text-fg-muted">{{ $strings.LabelMediaType }}: {{ mediaType }}</p>

    <p class="mb-2 text-base text-fg">{{ $strings.HeaderLocalLibraryItems }} ({{ localLibraryItems.length }})</p>

    <div class="w-full media-item-container overflow-y-auto">
      <template v-for="localLibraryItem in localLibraryItems">
        <nuxt-link :to="`/localMedia/item/${localLibraryItem.id}`" :key="localLibraryItem.id" class="flex my-1">
          <div class="w-12 h-12 min-w-12 min-h-12 bg-primary">
            <img v-if="localLibraryItem.coverPathSrc" :src="localLibraryItem.coverPathSrc" class="w-full h-full object-contain" />
          </div>
          <div class="flex-grow px-2">
            <p class="text-sm">{{ localLibraryItem.media.metadata.title }}</p>
            <p class="text-xs text-fg-muted">{{ getLocalLibraryItemSubText(localLibraryItem) }}</p>
          </div>
          <div class="w-12 h-12 flex items-center justify-center">
            <span class="material-symbols text-xl text-fg-muted">arrow_right</span>
          </div>
        </nuxt-link>
      </template>
    </div>

    <modals-dialog v-model="showDialog" :items="dialogItems" @action="dialogAction" />
  </div>
</template>

<script>
import { Capacitor } from '@capacitor/core'
import { Dialog } from '@capacitor/dialog'
import { AbsFileSystem } from '@/plugins/capacitor'
import localPodcastDownloads from '@/mixins/localPodcastDownloads'

export default {
  mixins: [localPodcastDownloads],
  asyncData({ params, query }) {
    return {
      folderId: params.id
    }
  },
  data() {
    return {
      localLibraryItems: [],
      folder: null,
      removingFolder: false,
      showDialog: false
    }
  },
  computed: {
    folderName() {
      return this.folder?.name || null
    },
    mediaType() {
      return this.folder?.mediaType
    },
    isInternalStorage() {
      return this.folder?.id.startsWith('internal-')
    },
    dialogItems() {
      if (this.isInternalStorage) return []
      const items = []
      items.push({
        text: this.$strings.ButtonRemove,
        value: 'remove'
      })
      return items
    }
  },
  methods: {
    getLocalLibraryItemSubText(localLibraryItem) {
      if (!localLibraryItem) return ''
      if (localLibraryItem.mediaType == 'book') {
        const txts = []
        if (localLibraryItem.media.ebookFile) {
          txts.push(`${localLibraryItem.media.ebookFile.ebookFormat} ${this.$strings.LabelEbook}`)
        }
        if (localLibraryItem.media.tracks?.length) {
          txts.push(`${localLibraryItem.media.tracks.length} ${this.$strings.LabelTracks}`)
        }
        return txts.join(' • ')
      } else {
        return `${localLibraryItem.media.episodes?.length || 0} ${this.$strings.HeaderEpisodes}`
      }
    },
    dialogAction(action) {
      console.log('Dialog action', action)
      if (action == 'remove') {
        this.removeFolder()
      }
      this.showDialog = false
    },
    async removeFolder() {
      var deleteMessage = 'Are you sure you want to remove this folder? (does not delete anything in your file system)'
      if (this.localLibraryItems.length) {
        deleteMessage = `Are you sure you want to remove this folder and ${this.localLibraryItems.length} items? (does not delete anything in your file system)`
      }
      const { value } = await Dialog.confirm({
        title: this.$strings.HeaderConfirm,
        message: deleteMessage
      })
      if (value) {
        this.removingFolder = true
        await AbsFileSystem.removeFolder({ folderId: this.folderId })
        this.removingFolder = false
        this.$router.replace('/localMedia/folders')
      }
    },
    async init() {
      var folder = await this.$db.getLocalFolder(this.folderId)
      this.folder = folder

      const before = new Map(this.localLibraryItems.map((item) => [item.id, item]))
      var items = (await this.$db.getLocalLibraryItemsInFolder(this.folderId)) || []
      console.log('Init folder', this.folderId, items)
      const snapshot = items.map((lmi) => {
        return {
          ...lmi,
          coverPathSrc: lmi.coverContentUrl ? Capacitor.convertFileSrc(lmi.coverContentUrl) : null
        }
      })
      this.localLibraryItems = this.mergeLocalDownloadSnapshot(snapshot, before)
    },
    acceptLocalPodcastDelta(delta) {
      return delta.folderId === this.folderId
    },
    findLocalPodcastRecord(id) {
      return this.localLibraryItems.find((item) => item.id === id)
    },
    newLocalLibraryItem(item) {
      if (item.folderId == this.folderId) {
        console.log('New local library item', item.id)
        const index = this.localLibraryItems.findIndex((li) => li.id === item.id)
        var _item = {
          ...item,
          coverPathSrc: item.coverContentUrl ? Capacitor.convertFileSrc(item.coverContentUrl) : null
        }
        if (index >= 0) this.localLibraryItems.splice(index, 1, _item)
        else this.localLibraryItems.push(_item)
      }
    }
  },
  mounted() {
    this.$eventBus.$on('new-local-library-item', this.newLocalLibraryItem)
    this.init()
  },
  beforeDestroy() {
    this.$eventBus.$off('new-local-library-item', this.newLocalLibraryItem)
  }
}
</script>

<style scoped>
.media-item-container {
  height: calc(100vh - 210px);
  max-height: calc(100vh - 210px);
}
.playerOpen .media-item-container {
  height: calc(100vh - 310px);
  max-height: calc(100vh - 310px);
}
</style>
