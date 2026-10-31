<template>
  <div class="w-full h-full py-6 px-4 overflow-y-auto">
    <p class="mb-4 text-base text-fg">{{ $strings.HeaderDownloads }} ({{ downloadItemParts.length }})</p>

    <div v-if="!downloadItemParts.length" class="py-6 text-center text-lg">No download item parts</div>
    <template v-for="(itemPart, num) in downloadItemParts">
      <div :key="`${itemPart.downloadItemId}-${itemPart.id}`" class="w-full">
        <div class="flex" @click="clickedItemPart(itemPart)">
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

    <modals-dialog v-if="isIos" v-model="showItemDialog" :items="itemDialogItems" :title="selectedItemPartTitle" @action="itemDialogAction" />
  </div>
</template>

<script>
import { AbsDownloader } from '@/plugins/capacitor'

export default {
  data() {
    return {
      showItemDialog: false,
      selectedItemPart: null
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
    },
    isIos() {
      return this.$platform === 'ios'
    },
    selectedItemPartTitle() {
      return this.selectedItemPart ? this.selectedItemPart.filename : ''
    },
    itemDialogItems() {
      return [
        { text: this.$strings.ButtonCancel, value: 'cancel' },
        { text: 'Keep downloading', value: 'keep' }
      ]
    }
  },
  methods: {
    clickedItemPart(itemPart) {
      if (!this.isIos) return
      this.selectedItemPart = itemPart
      this.showItemDialog = true
    },
    async itemDialogAction(action) {
      this.showItemDialog = false
      if (action !== 'cancel' || !this.selectedItemPart) return

      const downloadItemId = this.selectedItemPart.downloadItemId
      try {
        const result = await AbsDownloader.cancelDownloadItem({ downloadItemId })
        if (result && result.error) {
          this.$toast.error(result.error)
        } else {
          this.$store.commit('globals/removeItemDownload', downloadItemId)
          this.$toast.success('Download cancelled')
        }
      } catch (e) {
        console.error('Failed to cancel download', e)
        this.$toast.error('Failed to cancel download')
      }
    }
  },
  mounted() {},
  beforeDestroy() {}
}
</script>
