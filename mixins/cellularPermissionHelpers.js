import { Dialog } from '@capacitor/dialog';

export default {
  methods: {
    async checkCellularPermission(actionType) {
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
        title: 'Confirm',
        message
      })
      return value
    }
  }
}
