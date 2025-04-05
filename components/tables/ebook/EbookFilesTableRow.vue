<template>
  <tr>
    <td class="px-4">{{ file.metadata.filename }} <span v-if="isPrimary" class="material-symbols text-success align-text-bottom text-base">check_circle</span></td>
    <td class="text-xs w-16">
      <ui-icon-btn icon="auto_stories" outlined borderless icon-font-size="1.125rem" :size="8" @click="readEbook" />
    </td>
    <td v-if="contextMenuItems.length" class="text-center">
      <ui-icon-btn icon="more_vert" borderless @click="clickMore" />
    </td>
  </tr>
</template>

<script>
export default {
  props: {
    libraryItemId: String,
    showFullPath: Boolean,
    file: {
      type: Object,
      default: () => {}
    }
  },
  data() {
    return {}
  },
  computed: {
    userCanUpdate() {
      return this.$store.getters['user/getUserCanUpdate']
    },
    isPrimary() {
      return !this.file.isSupplementary
    },
    libraryIsAudiobooksOnly() {
      return this.$store.getters['libraries/getLibraryIsAudiobooksOnly']
    },
    contextMenuItems() {
      const items = []
      if (this.userCanUpdate && !this.libraryIsAudiobooksOnly) {
        items.push({
          text: this.isPrimary ? this.$strings.LabelSetEbookAsSupplementary : this.$strings.LabelSetEbookAsPrimary,
          value: 'updateStatus'
        })
      }
      return items
    }
  },
  methods: {
    clickMore() {
      this.$emit('more', {
        file: this.file,
        items: this.contextMenuItems
      })
    },
    readEbook() {
      this.$emit('read', this.file.ino)
    }
  },
  mounted() {}
}
</script>
