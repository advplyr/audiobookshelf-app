<template>
  <div class="w-full h-full py-6 px-4 overflow-y-auto">
    <p class="mb-2 text-base text-fg">{{ $strings.HeaderDownloads }} ({{ downloadItemParts.length }})</p>

    <!-- Download Management Buttons -->
    <div v-if="downloadItemParts.length" class="flex space-x-2 mb-4">
      <ui-btn size="sm" color="error" @click="cancelAllDownloads">Cancel All</ui-btn>
      <ui-btn size="sm" @click="retryDownloadQueue">Retry Queue</ui-btn>
    </div>

    <div v-if="!downloadItemParts.length" class="py-6 text-center text-lg">No download item parts</div>
    <template v-for="(itemPart, num) in downloadItemParts">
      <div :key="itemPart.id" class="w-full">
        <div class="flex">
          <div class="w-14">
            <span v-if="itemPart.completed" class="material-symbols text-success">check_circle</span>
            <span v-else class="font-semibold text-fg">{{ Math.round(itemPart.progress) }}%</span>
          </div>
          <div class="flex-grow px-2">
            <p class="truncate">{{ itemPart.filename }}</p>
          </div>
        </div>

        <div v-if="num + 1 < downloadItemParts.length" class="flex border-t border-border my-3" />
      </div>
    </template>
  </div>
</template>

<script>
import { Capacitor } from '@capacitor/core'
import { AbsDownloader } from '@/plugins/capacitor'
import { Dialog } from '@capacitor/dialog'

export default {
  data() {
    return {}
  },
  computed: {
    downloadItems() {
      return this.$store.state.globals.itemDownloads
    },
    downloadItemParts() {
      let parts = []
      this.downloadItems.forEach((di) => parts.push(...di.downloadItemParts))
      return parts
    }
  },
  methods: {
    async cancelAllDownloads() {
      const { value } = await Dialog.confirm({
        title: 'Cancel All Downloads',
        message: 'Are you sure you want to cancel all pending downloads? This action cannot be undone.',
        okButtonTitle: 'Cancel All',
        cancelButtonTitle: 'Keep Downloads'
      })

      if (value) {
        try {
          await AbsDownloader.cancelAllDownloads()
          // Immediately clear downloads from the store for instant UI feedback
          this.$store.commit('globals/clearAllDownloads')
          this.$toast.success('All downloads cancelled')
        } catch (error) {
          console.error('Failed to cancel downloads:', error)
          this.$toast.error('Failed to cancel downloads')
        }
      }
    },
    async retryDownloadQueue() {
      try {
        await AbsDownloader.retryDownloadQueue()
        this.$toast.success('Download queue restarted')
      } catch (error) {
        console.error('Failed to retry download queue:', error)
        this.$toast.error('Failed to retry download queue')
      }
    }
  },
  mounted() {},
  beforeDestroy() {}
}
</script>

