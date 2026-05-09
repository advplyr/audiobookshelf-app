<template>
  <div class="w-full h-full py-6 px-4 overflow-y-auto">
    <p class="mb-2 text-base text-fg">{{ $strings.HeaderDownloads }} ({{ downloadItemParts.length }})</p>

    <!-- Download Management Buttons -->
    <div v-if="downloadItemParts.length || cancelling" class="flex items-center space-x-2 mb-4">
      <ui-btn size="sm" color="error" :disabled="cancelling" @click="cancelAllDownloads">
        <span v-if="cancelling" class="flex items-center space-x-1">
          <svg class="animate-spin h-3 w-3" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
            <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" />
            <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8H4z" />
          </svg>
          <span>Cancelling...</span>
        </span>
        <span v-else>Cancel All</span>
      </ui-btn>
      <ui-btn size="sm" :disabled="cancelling" @click="retryDownloadQueue">Retry Queue</ui-btn>
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
            <p class="break-all">{{ itemPart.filename }}</p>
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
    return {
      cancelling: false
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
        this.cancelling = true
        try {
          await AbsDownloader.cancelAllDownloads()
          // Clear the store only after native confirms cancellation succeeded
          this.$store.commit('globals/clearAllDownloads')
          this.$toast.success('All downloads cancelled')
        } catch (error) {
          console.error('Failed to cancel downloads:', error)
          this.$toast.error('Failed to cancel downloads')
        } finally {
          this.cancelling = false
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

