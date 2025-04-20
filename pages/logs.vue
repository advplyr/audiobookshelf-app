<template>
  <div class="w-full h-full py-4">
    <div class="flex items-center mb-2 space-x-2 px-4">
      <p class="text-lg font-bold">{{ $strings.ButtonLogs }}</p>
      <ui-icon-btn outlined borderless :icon="isCopied ? 'check' : 'content_copy'" @click="copyToClipboard" />
      <ui-icon-btn outlined borderless icon="share" @click="shareLogs" />
      <div class="flex-grow"></div>
      <ui-icon-btn outlined borderless icon="more_vert" @click="showDialog = true" />
    </div>

    <div class="w-full h-[calc(100%-40px)] overflow-y-auto relative" ref="logContainer">
      <div v-if="!logs.length && !isLoading" class="flex items-center justify-center h-32 p-4">
        <p class="text-gray-400">{{ $strings.MessageNoLogs }}</p>
      </div>
      <div v-if="hasScrolled" class="sticky top-0 left-0 w-full h-10 bg-gradient-to-t from-transparent to-bg z-10 pointer-events-none"></div>

      <div v-for="(log, index) in logs" :key="log.id" class="py-2 px-4" :class="{ 'bg-white/5': index % 2 === 0 }">
        <div class="flex items-center space-x-4 mb-1">
          <div class="text-xs uppercase font-bold" :class="{ 'text-error': log.level === 'error', 'text-blue-500': log.level === 'info' }">{{ log.level }}</div>
          <div class="text-xs text-gray-400">{{ formatEpochToDatetimeString(log.timestamp) }}</div>
          <div class="flex-grow"></div>
          <div class="text-xs text-gray-400">{{ log.tag }}</div>
        </div>
        <div class="text-xs">{{ maskServerAddress ? log.maskedMessage : log.message }}</div>
      </div>
    </div>

    <modals-dialog v-model="showDialog" :items="dialogItems" @action="dialogAction" />
  </div>
</template>
<script>
import { AbsLogger } from '@/plugins/capacitor'
import { FileSharer } from '@webnativellc/capacitor-filesharer'

export default {
  data() {
    return {
      logs: [],
      isLoading: true,
      isCopied: false,
      hasScrolled: false,
      maskServerAddress: true,
      showDialog: false
    }
  },
  computed: {
    dialogItems() {
      return [
        {
          text: this.maskServerAddress ? this.$strings.ButtonUnmaskServerAddress : this.$strings.ButtonMaskServerAddress,
          value: 'toggle-mask-server-address',
          icon: this.maskServerAddress ? 'remove_moderator' : 'shield'
        },
        {
          text: this.$strings.ButtonClearLogs,
          value: 'clear-logs',
          icon: 'delete'
        }
      ]
    }
  },
  methods: {
    async dialogAction(action) {
      await this.$hapticsImpact()

      if (action === 'clear-logs') {
        await AbsLogger.clearLogs()
        this.logs = []
      } else if (action === 'toggle-mask-server-address') {
        this.maskServerAddress = !this.maskServerAddress
      }
      this.showDialog = false
    },
    toggleMaskServerAddress() {
      this.maskServerAddress = !this.maskServerAddress
    },
    async copyToClipboard() {
      await this.$hapticsImpact()
      this.$copyToClipboard(this.getLogsString()).then(() => {
        this.isCopied = true
        setTimeout(() => {
          this.isCopied = false
        }, 2000)
      })
    },
    /**
     * Formats an epoch timestamp to YYYY-MM-DD HH:mm:ss.SSS
     * Use 24 hour time format
     * @param {number} epoch
     * @returns {string}
     */
    formatEpochToDatetimeString(epoch) {
      return new Date(epoch)
        .toLocaleString('en-US', {
          year: 'numeric',
          month: '2-digit',
          day: '2-digit',
          hour: '2-digit',
          minute: '2-digit',
          second: '2-digit',
          fractionalSecondDigits: 3,
          hour12: false
        })
        .replace(',', '')
    },
    getLogsString() {
      return this.logs
        .map((log) => {
          const logMessage = this.maskServerAddress ? log.maskedMessage : log.message
          return `${this.formatEpochToDatetimeString(log.timestamp)} [${log.level.toUpperCase()}] ${logMessage}`
        })
        .join('\n')
    },
    async shareLogs() {
      await this.$hapticsImpact()
      // Share .txt file with logs
      const base64Data = Buffer.from(this.getLogsString()).toString('base64')

      FileSharer.share({
        filename: `audiobookshelf_logs.txt`,
        contentType: 'text/plain',
        base64Data
      }).catch((error) => {
        if (error.message !== 'USER_CANCELLED') {
          console.error('Failed to share', error.message)
          this.$toast.error('Failed to share: ' + error.message)
        }
      })
    },
    scrollToBottom() {
      this.$refs.logContainer.scrollTop = this.$refs.logContainer.scrollHeight
      this.hasScrolled = this.$refs.logContainer.scrollTop > 0
    },
    maskLogMessage(message) {
      return message.replace(/(https?:\/\/)\S+/g, '$1[SERVER_ADDRESS]')
    },
    loadLogs() {
      this.isLoading = true
      AbsLogger.getAllLogs()
        .then((logData) => {
          const logs = logData.value || []
          this.logs = logs.map((log) => {
            log.maskedMessage = this.maskLogMessage(log.message)
            return log
          })
          this.$nextTick(() => {
            this.scrollToBottom()
          })
          this.isLoading = false
        })
        .catch((error) => {
          this.isLoading = false
          console.error('Failed to load logs', error)
          this.$toast.error('Failed to load logs: ' + error.message)
        })
    }
  },
  mounted() {
    this.loadLogs()
  }
}
</script>

