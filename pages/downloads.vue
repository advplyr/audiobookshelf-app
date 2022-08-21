<template>
  <div class="w-full h-full py-6 px-4 overflow-y-auto">
    <p class="mb-2 text-base text-white">Downloads ({{ localLibraryItems.length }})</p>

    <div class="w-full media-item-container overflow-y-auto">
      <template v-for="(mediaItem, num) in localLibraryItems">
        <div :key="mediaItem.id">
          <div class="flex w-full items-center">
            <nuxt-link :to="`/item/${mediaItem.id}`" class="flex flex-grow">
              <div class="w-16 h-16 min-w-16 min-h-16 flex-none bg-primary relative self-center">
                <img v-if="mediaItem.coverPathSrc" :src="mediaItem.coverPathSrc" class="w-full h-full object-contain" />
              </div>
              <div class="px-2 self-center">
                <p class="text-sm">{{ mediaItem.media.metadata.title }}</p>
                <p v-if="mediaItem.mediaType == 'book'" class="text-xs text-gray-300">{{ mediaItem.media.tracks.length }} Track{{ mediaItem.media.tracks.length == 1 ? '' : 's' }}</p>
                <p v-else-if="mediaItem.mediaType == 'podcast'" class="text-xs text-gray-300">{{ mediaItem.media.episodes.length }} Episode{{ mediaItem.media.episodes.length == 1 ? '' : 's' }}</p>
                <p v-if="size(mediaItem)" class="text-xs text-gray-300">{{ $bytesPretty(size(mediaItem)) }}</p>
              </div>
            </nuxt-link>
            <div class="w-12 h-12 flex items-center">
              <span class="material-icons text-2xl text-red-400" @click="deleteItem(mediaItem)">delete</span>
            </div>
          </div>
          <div v-if="num+1 < localLibraryItems.length" class="flex border-t border-white border-opacity-10 my-3"/>
        </div>
      </template>
    </div>
  </div>
</template>

<script>
import { Capacitor } from '@capacitor/core'
import { Dialog } from '@capacitor/dialog'
import { AbsFileSystem } from '@/plugins/capacitor'

export default {
  data() {
    return {
      localLibraryItems: [],
    }
  },
  methods: {
    async init() {
      var items = (await this.$db.getLocalLibraryItems()) || []
      this.localLibraryItems = items.map((lmi) => {
        console.log('Local library item', JSON.stringify(lmi))
        return {
          ...lmi,
          coverPathSrc: lmi.coverContentUrl ? Capacitor.convertFileSrc(lmi.coverContentUrl) : null
        }
      })
    },
    size(item) {
      console.log('sizing' + item)
      let size = null
      if (item.localFiles) {
        for (let i = 0; i < item.localFiles.length; i++) {
          size = size + item.localFiles[i].size
        }
      return size
      }
    },
    async deleteItem(item) {
      const { value } = await Dialog.confirm({
        title: 'Confirm',
        message: `Warning! This will delete "${item.media.metadata.title}" and all associated local files. Are you sure?`
      })
      if (value) {
        var res = await AbsFileSystem.deleteItem(item)
        if (res && res.success) {
          this.$toast.success('Deleted Successfully')
          this.init()
        } else this.$toast.error('Failed to delete')
      }
    },
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

