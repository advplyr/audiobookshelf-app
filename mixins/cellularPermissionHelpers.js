import { Dialog } from '@capacitor/dialog';

import { Capacitor } from '@capacitor/core'
import { AbsDownloader } from '@/plugins/capacitor'

export default {
  data() {
    return {
      // Which networks the next download may use; passed to AbsDownloader.downloadLibraryItem
      downloadNetworkMode: null
    }
  },
  methods: {
    /**
     * Android decides natively: it knows whether the network is metered and can offer
     * "Download now", "Wait for Wi-Fi" and "Cancel", which the Dialog plugin cannot.
     */
    async resolveAndroidDownloadNetworkMode() {
      const result = await AbsDownloader.resolveDownloadNetworkMode({
        title: this.$strings.HeaderDownloadOnMobileData,
        message: this.$strings.MessageMeteredDownloadChoice,
        downloadNow: this.$strings.ButtonDownloadNow,
        waitForWifi: this.$strings.ButtonWaitForWifi,
        cancel: this.$strings.ButtonCancel
      })
      if (result.cancelled) return false
      this.downloadNetworkMode = result.networkMode
      if (result.metered && result.networkMode === 'wifiOnly') {
        this.$toast.info(this.$strings.ToastDownloadQueuedForWifi)
      }
      return true
    },
    async checkCellularPermission(actionType) {
      if (actionType === 'download' && Capacitor.getPlatform() === 'android') {
        return this.resolveAndroidDownloadNetworkMode()
      }
      if (this.$store.state.networkConnectionType !== 'cellular') return true

      let permission;
      if (actionType === 'download') {
        permission = this.$store.getters['getCanDownloadUsingCellular']
        if (permission === 'NEVER') {
          this.$toast.error(this.$strings.ToastDownloadNotAllowedOnCellular)
          return false
        }
      } else if (actionType === 'streaming') {
        permission = this.$store.getters['getCanStreamingUsingCellular']
        if (permission === 'NEVER') {
          this.$toast.error(this.$strings.ToastStreamingNotAllowedOnCellular)
          return false
        }
      }

      if (permission === 'ASK') {
        const confirmed = await this.confirmAction(actionType)
        return confirmed
      }

      return true
    },
    async confirmAction(actionType) {
      const message = actionType === 'download' ?
        this.$strings.MessageConfirmDownloadUsingCellular :
        this.$strings.MessageConfirmStreamingUsingCellular

      const { value } = await Dialog.confirm({
        title: this.$strings.HeaderConfirm,
        message
      })
      return value
    }
  }
}
