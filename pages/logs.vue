<template>
  <div class="w-full h-full p-4">
    <div class="flex items-center justify-between mb-2">
      <p class="text-lg font-bold">{{ $strings.ButtonLogs }}</p>
      <ui-icon-btn outlined borderless icon="content_copy" @click="copyToClipboard" />
    </div>
    <div class="w-full h-[calc(100%-40px)] overflow-y-auto relative" ref="logContainer">
      <div v-if="hasScrolled" class="sticky top-0 left-0 w-full h-10 bg-gradient-to-t from-transparent to-bg z-10 pointer-events-none"></div>

      <div v-for="log in logs" :key="log.id" class="py-1">
        <div class="flex items-center space-x-4 mb-1">
          <div class="text-xs uppercase font-bold" :class="{ 'text-error': log.level === 'error', 'text-blue-600': log.level === 'info' }">{{ log.level }}</div>
          <div class="text-xs text-gray-400">{{ new Date(log.timestamp).toLocaleString() }}</div>
        </div>
        <div class="text-xs">{{ log.message }}</div>
      </div>
    </div>
  </div>
</template>

<script>
import { AbsLogger } from '@/plugins/capacitor'

export default {
  data() {
    return {
      logs: [],
      hasScrolled: false
    }
  },
  computed: {},
  methods: {
    copyToClipboard() {
      this.$copyToClipboard(
        this.logs
          .map((log) => {
            return `${log.timestamp} [${log.level}] ${log.message}`
          })
          .join('\n')
      )
    },
    scrollToBottom() {
      this.$refs.logContainer.scrollTop = this.$refs.logContainer.scrollHeight
      this.hasScrolled = this.$refs.logContainer.scrollTop > 0
    },
    loadLogs() {
      AbsLogger.getAllLogs().then((logData) => {
        const logs = logData.value || []
        this.logs = logs
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
