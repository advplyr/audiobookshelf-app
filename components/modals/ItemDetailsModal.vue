<template>
  <modals-modal v-model="show" :width="400" height="100%">
    <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" data-modal-backdrop >
      <div class="w-full overflow-x-hidden overflow-y-auto bg-surface rounded-lg border border-outline-variant shadow-elevation-4 backdrop-blur-md p-2" style="max-height: 75%" >
        <!-- Material 3 Modal Header -->
        <div class="px-4 py-4 border-b border-outline-variant">
          <h2 class="text-headline-small text-on-surface font-medium">{{ $strings.HeaderDetails }}</h2>
        </div>

        <div class="p-4">
          <p class="mb-2 text-on-surface">{{ mediaMetadata.title }}</p>

          <div v-if="size" class="text-sm mb-2 text-on-surface">{{ $strings.LabelSize }}: {{ $bytesPretty(size) }}</div>

          <p class="mb-1 text-xs text-on-surface-variant">ID: {{ _libraryItem.id }}</p>
        </div>
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
