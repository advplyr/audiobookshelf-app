<template>
  <div class="w-full h-full p-4">
    <div class="flex items-center mb-2 space-x-2">
      <p class="text-lg font-bold">{{ $strings.ButtonLogs }}</p>
      <ui-icon-btn outlined borderless :icon="isCopied ? 'check' : 'content_copy'" @click="copyToClipboard" />
      <ui-icon-btn outlined borderless icon="share" @click="shareLogs" />
      <div class="flex-grow"></div>
      <ui-btn class="h-9" :padding-y="1" :padding-x="4" @click="toggleMaskServerAddress">
        {{ maskServerAddress ? $strings.ButtonUnmaskServerAddress : $strings.ButtonMaskServerAddress }}
      </ui-btn>
    </div>
    <div class="w-full h-[calc(100%-40px)] overflow-y-auto relative" ref="logContainer">
      <div v-if="hasScrolled" class="sticky top-0 left-0 w-full h-10 bg-gradient-to-t from-transparent to-bg z-10 pointer-events-none"></div>

      <div v-for="log in logs" :key="log.id" class="py-1">
        <div class="flex items-center space-x-4 mb-1">
          <div class="text-xs uppercase font-bold" :class="{ 'text-error': log.level === 'error', 'text-blue-500': log.level === 'info' }">{{ log.level }}</div>
          <div class="text-xs text-gray-400">{{ formatEpochToDatetimeString(log.timestamp) }}</div>
        </div>
        <div class="text-xs">{{ maskServerAddress ? log.maskedMessage : log.message }}</div>
      </div>
    </div>
  </div>
</template>
<script>
import { AbsLogger } from '@/plugins/capacitor'
import { FileSharer } from '@webnativellc/capacitor-filesharer'

export default {
  data() {
    return {
      logs: [],
      isCopied: false,
      hasScrolled: false,
      maskServerAddress: true
    }
  },
  computed: {},
  methods: {
    toggleMaskServerAddress() {
      this.maskServerAddress = !this.maskServerAddress
    },
    copyToClipboard() {
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
    shareLogs() {
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
      AbsLogger.getAllLogs().then((logData) => {
        const logs = logData.value || []
        this.logs = logs.map((log) => {
          log.maskedMessage = this.maskLogMessage(log.message)
          return log
        })
        this.$nextTick(() => {
          this.scrollToBottom()
        })
      })
    }
  },
  mounted() {
    this.loadLogs()
  }
}
</script>
