<template>
  <div class="w-full h-full py-6 px-4 overflow-y-auto" :style="contentPaddingStyle">
    <p class="mb-4 text-base text-fg">{{ $strings.HeaderDownloads }} ({{ downloadItemParts.length }})</p>

    <div v-if="!downloadItemParts.length" class="py-6 text-center text-lg">No download item parts</div>
    <template v-for="(itemPart, num) in downloadItemParts">
      <div :key="itemPart.id" class="w-full">
        <div class="flex">
          <div class="w-14">
            <span v-if="itemPart.completed" class="material-symbols text-success">check_circle</span>
            <span v-else class="font-semibold text-fg">{{ Math.round(itemPart.progress) }}%</span>
          </div>
          <div class="flex-grow px-2">
            <p class="truncate">{{ itemPart.filename }}</p>
          </div>
        </div>

        <div v-if="num + 1 < downloadItemParts.length" class="flex border-t border-border my-3" />
      </div>
    </template>
  </div>
</template>

<script>
export default {
  data() {
    return {}
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
    contentPaddingStyle() {
      return this.$store.getters['getIsPlayerOpen'] ? { paddingBottom: '120px' } : {}
    }
  },
  mounted() {},
  beforeDestroy() {}
}
</script>

