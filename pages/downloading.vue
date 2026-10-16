<template>
  <div class="w-full h-full py-6 px-4 overflow-y-auto">
    <div class="flex items-center mb-4">
      <p class="text-base text-fg">{{ $strings.HeaderDownloads }} ({{ downloadItemParts.length }})</p>
      <div class="flex-grow" />
      <ui-btn v-if="isAndroid && downloadItems.length > 1" small color="error" @click="cancelAll">{{ $strings.ButtonCancel }} ({{ downloadItems.length }})</ui-btn>
    </div>
    <div v-if="!downloadItemParts.length" class="py-6 text-center text-lg">No download item parts</div>
    <template v-for="(downloadItem, itemIndex) in downloadItems">
      <div :key="downloadItem.id" class="w-full">
        <div class="flex items-center mb-2">
          <p class="flex-grow font-semibold text-fg truncate pr-2">{{ downloadItem.itemTitle }}</p>
          <template v-if="isAndroid">
            <ui-btn v-if="hasStatus(downloadItem, 'retrying')" small class="mr-2" :disabled="!!retryingNow[downloadItem.id]" @click="retryNow(downloadItem)">{{ $strings.ButtonRetryNow }}</ui-btn>
            <ui-btn v-if="canAllowMobileData && hasStatus(downloadItem, 'waitingForWifi')" small class="mr-2" @click="allowMobileData(downloadItem)">{{ $strings.ButtonAllowMobileData }}</ui-btn>
            <ui-btn v-if="hasStatus(downloadItem, 'failed')" small class="mr-2" @click="retry(downloadItem)">{{ $strings.ButtonRetry }}</ui-btn>
            <ui-btn small color="error" :disabled="!!cancelling[downloadItem.id]" @click="cancel(downloadItem)">{{ $strings.ButtonCancel }}</ui-btn>
          </template>
        </div>
        <template v-for="itemPart in downloadItem.downloadItemParts">
          <div :key="itemPart.id" class="flex py-1">
            <div class="w-14">
              <span v-if="itemPart.completed" class="material-symbols text-success">check_circle</span>
              <span v-else-if="itemPart.status === 'failed'" class="material-symbols text-error">error</span>
              <span v-else-if="partStatus(itemPart) === 'retrying'" class="material-symbols text-warning">schedule</span>
              <span v-else class="font-semibold text-fg">{{ Math.round(itemPart.progress) }}%</span>
            </div>
            <div class="flex-grow px-2 min-w-0">
              <p class="break-all">{{ itemPart.filename }}</p>
              <p v-if="statusLabel(itemPart)" class="text-xs text-fg-muted">{{ statusLabel(itemPart) }}</p>
            </div>
          </div>
        </template>
        <div v-if="itemIndex + 1 < downloadItems.length" class="flex border-t border-border my-3" />
      </div>
    </template>
  </div>
</template>

<script>
import { AbsDownloader } from '@/plugins/capacitor'
import cellularPermissionHelpers from '@/mixins/cellularPermissionHelpers'

export default {
  mixins: [cellularPermissionHelpers],
  data() {
    return {
      cancelling: {},
      retryingNow: {},
      // Wall clock for retry countdowns; ticks only while something is backing off
      now: Date.now(),
      clockTimer: null
    }
  },
  computed: {
    isAndroid() {
      return this.$platform === 'android'
    },
    downloadItems() {
      return this.$store.state.globals.itemDownloads
    },
    downloadItemParts() {
      let parts = []
      this.downloadItems.forEach((di) => parts.push(...di.downloadItemParts))
      return parts
    },
    // Under "Wi-Fi only" the setting overrides a download's own mode, so the button would do nothing
    canAllowMobileData() {
      return this.$store.getters['getCanDownloadUsingCellular'] !== 'NEVER'
    },
    hasBackoff() {
      return this.downloadItemParts.some((part) => part.status === 'retrying')
    }
  },
  watch: {
    hasBackoff: {
      immediate: true,
      handler(hasBackoff) {
        clearInterval(this.clockTimer)
        this.clockTimer = null
        if (!hasBackoff) return
        this.now = Date.now()
        this.clockTimer = setInterval(() => (this.now = Date.now()), 1000)
      }
    }
  },
  beforeDestroy() {
    clearInterval(this.clockTimer)
  },
  methods: {
    hasStatus(downloadItem, status) {
      return downloadItem.downloadItemParts.some((part) => this.partStatus(part) === status)
    },
    /** The native status is a snapshot, so treat an expired backoff as about to start. */
    partStatus(itemPart) {
      if (itemPart.status === 'retrying' && itemPart.nextAttemptAt <= this.now) return 'retryStarting'
      return itemPart.status
    },
    formatCountdown(ms) {
      const totalSeconds = Math.max(1, Math.ceil(ms / 1000))
      const minutes = Math.floor(totalSeconds / 60)
      const seconds = totalSeconds % 60
      return minutes ? `${minutes}:${String(seconds).padStart(2, '0')}` : `${seconds}s`
    },
    statusLabel(itemPart) {
      switch (this.partStatus(itemPart)) {
        case 'queued':
          return this.$strings.LabelDownloadStatusQueued
        case 'retrying':
          return this.$getString('LabelDownloadStatusRetryingIn', [this.formatCountdown(itemPart.nextAttemptAt - this.now)])
        case 'retryStarting':
          return this.$strings.LabelDownloadStatusRetrying
        case 'waitingForNetwork':
          return this.$strings.MessageWaitingForNetwork
        case 'waitingForWifi':
          return this.$strings.MessageWaitingForWifi
        case 'waitingForStorage':
          return this.$strings.MessageWaitingForAvailableStorage
        case 'finalizing':
          return this.$strings.LabelDownloadStatusFinalizing
        case 'failed':
          return this.$strings.LabelDownloadStatusFailed
        default:
          return null
      }
    },
    async cancel(downloadItem) {
      this.$set(this.cancelling, downloadItem.id, true)
      try {
        const { cancelled } = await AbsDownloader.cancelDownloadItem({ downloadItemId: downloadItem.id })
        if (cancelled) this.$store.commit('globals/removeItemDownload', downloadItem.id)
      } catch (error) {
        console.error('Failed to cancel download', error)
      } finally {
        this.$delete(this.cancelling, downloadItem.id)
      }
    },
    async cancelAll() {
      await AbsDownloader.cancelAllDownloads()
      this.$store.commit('globals/clearItemDownloads')
    },
    async retryNow(downloadItem) {
      this.$set(this.retryingNow, downloadItem.id, true)
      try {
        await AbsDownloader.retryDownloadNow({ downloadItemId: downloadItem.id })
      } catch (error) {
        console.error('Failed to retry download', error)
      } finally {
        this.$delete(this.retryingNow, downloadItem.id)
      }
    },
    allowMobileData(downloadItem) {
      AbsDownloader.allowCellularDownload({ downloadItemId: downloadItem.id })
    },
    async retry(downloadItem) {
      if (!(await this.checkCellularPermission('download'))) return
      const payload = {
        networkMode: this.downloadNetworkMode,
        libraryItemId: downloadItem.libraryItemId,
        episodeId: downloadItem.episodeId
      }
      const res = await AbsDownloader.downloadLibraryItem(payload)
      if (res && res.error) this.$toast.error(res.error)
    }
  }
}
</script>
