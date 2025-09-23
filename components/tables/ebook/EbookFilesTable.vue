<template>
  <div class="w-full my-2">
    <div class="w-full bg-surface-container text-on-surface px-4 py-3 flex items-center border border-outline-variant" :class="showFiles ? 'rounded-t-2xl' : 'rounded-2xl'" @click.stop="clickBar">
      <p class="pr-2 font-medium">{{ $strings.HeaderEbookFiles }}</p>
      <div class="h-6 w-6 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center">
        <span class="text-label-small font-mono">{{ ebookFiles.length }}</span>
      </div>
      <div class="flex-grow" />
      <div class="h-10 w-10 rounded-full bg-secondary-container text-on-secondary-container flex justify-center items-center duration-500 hover:bg-secondary-container/80" :class="showFiles ? 'transform rotate-180' : ''">
        <span class="material-symbols text-display-small text-on-surface">arrow_drop_down</span>
      </div>
    </div>
    <transition name="slide">
      <div class="bg-surface-container border-x border-b border-outline-variant rounded-b-2xl px-4 py-2" v-show="showFiles">
        <table class="text-body-medium tracksTable w-full">
          <tr class="border-b border-outline-variant">
            <th class="text-left px-4 py-2 text-on-surface font-medium">{{ $strings.LabelFilename }}</th>
            <th class="text-left px-4 w-20 py-2 text-on-surface font-medium">{{ $strings.LabelRead }}</th>
            <th v-if="userCanUpdate && !libraryIsAudiobooksOnly" class="text-center w-16 py-2"></th>
          </tr>
          <template v-for="file in ebookFiles">
            <tables-ebook-files-table-row :key="file.path" :libraryItemId="libraryItemId" :file="file" @read="readEbook" @more="showMore" />
          </template>
        </table>
      </div>
    </transition>

    <modals-dialog v-model="showMoreMenu" :items="moreMenuItems" @action="moreMenuAction" />
  </div>
</template>

<script>
export default {
  props: {
    libraryItem: {
      type: Object,
      default: () => {}
    }
  },
  data() {
    return {
      processing: false,
      showFiles: false,
      showMoreMenu: false,
      moreMenuItems: [],
      selectedFile: null
    }
  },
  computed: {
    libraryItemId() {
      return this.libraryItem.id
    },
    ebookFiles() {
      return (this.libraryItem.libraryFiles || []).filter((lf) => lf.fileType === 'ebook')
    },
    userCanUpdate() {
      return this.$store.getters['user/getUserCanUpdate']
    },
    libraryIsAudiobooksOnly() {
      return this.$store.getters['libraries/getLibraryIsAudiobooksOnly']
    }
  },
  methods: {
    moreMenuAction(action) {
      this.showMoreMenu = false
      if (action === 'updateStatus') {
        this.updateEbookStatus()
      }
    },
    showMore({ file, items }) {
      this.showMoreMenu = true
      this.selectedFile = file
      this.moreMenuItems = items
    },
    readEbook(fileIno) {
      this.$store.commit('showReader', { libraryItem: this.libraryItem, keepProgress: false, fileId: fileIno })
    },
    clickBar() {
      this.showFiles = !this.showFiles
    },
    updateEbookStatus() {
      this.processing = true
      this.$nativeHttp
        .patch(`/api/items/${this.libraryItemId}/ebook/${this.selectedFile.ino}/status`)
        .then(() => {
          this.$toast.success('Ebook updated')
        })
        .catch((error) => {
          console.error('Failed to update ebook', error)
          this.$toast.error('Failed to update ebook')
        })
        .finally(() => {
          this.processing = false
        })
    }
  },
  mounted() {}
}
</script>
