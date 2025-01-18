<template>
  <div class="w-full my-2">
    <div class="w-full bg-primary px-4 py-2 flex items-center" :class="showFiles ? 'rounded-t-md' : 'rounded-md'" @click.stop="clickBar">
      <p class="pr-2">{{ $strings.HeaderEbookFiles }}</p>
      <div class="h-6 w-6 rounded-full bg-fg/10 flex items-center justify-center">
        <span class="text-xs font-mono">{{ ebookFiles.length }}</span>
      </div>
      <div class="flex-grow" />
      <div class="h-10 w-10 rounded-full flex justify-center items-center duration-500" :class="showFiles ? 'transform rotate-180' : ''">
        <span class="material-icons text-3xl">expand_more</span>
      </div>
    </div>
    <transition name="slide">
      <div class="w-full" v-show="showFiles">
        <table class="text-sm tracksTable">
          <tr>
            <th class="text-left px-4">{{ $strings.LabelFilename }}</th>
            <th class="text-left px-4 w-16">{{ $strings.LabelRead }}</th>
            <th v-if="userCanUpdate && !libraryIsAudiobooksOnly" class="text-center w-16"></th>
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