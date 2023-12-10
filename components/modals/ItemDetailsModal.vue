<template>
  <modals-modal v-model="show" :width="400" height="100%">
    <template #outer>
      <div class="absolute top-8 left-4 z-40">
        <p class="text-white text-2xl truncate">{{ $strings.HeaderDetails }}</p>
      </div>
    </template>

    <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" @click="show = false">
      <div class="w-full overflow-x-hidden overflow-y-auto bg-primary rounded-lg border border-border p-2" style="max-height: 75%" @click.stop>
        <p class="mb-2">{{ mediaMetadata.title }}</p>

        <div v-if="size" class="text-sm mb-2">{{ $strings.LabelSize }}: {{ $bytesPretty(size) }}</div>

        <p class="mb-1 text-xs text-fg">ID: {{ _libraryItem.id }}</p>
      </div>
    </div>
  </modals-modal>
</template>

<script>
export default {
  props: {
    value: Boolean,
    libraryItem: {
      type: Object,
      default: () => {}
    }
  },
  data() {
    return {}
  },
  computed: {
    show: {
      get() {
        return this.value
      },
      set(val) {
        this.$emit('input', val)
      }
    },
    _libraryItem() {
      return this.libraryItem || {}
    },
    media() {
      return this._libraryItem.media || {}
    },
    mediaMetadata() {
      return this.media.metadata || {}
    },
    size() {
      return this.media.size
    }
  },
  methods: {},
  mounted() {}
}
</script>
