<template>
  <div class="w-full py-2 text-sm">
    <div class="flex -mb-px">
      <div class="w-1/2 h-6 rounded-tl-md relative border border-white/10 flex items-center justify-center cursor-pointer" :class="!showAdvancedView ? 'text-white bg-bg border-b-bg' : 'text-gray-400 bg-primary bg-opacity-70'" @click="showAdvancedView = false">
        <p class="text-sm">{{ $strings.HeaderRSSFeedGeneral }}</p>
      </div>
      <div class="w-1/2 h-6 rounded-tr-md relative border border-white/10 flex items-center justify-center -ml-px cursor-pointer" :class="showAdvancedView ? 'text-white bg-bg border-b-bg' : 'text-gray-400 bg-primary bg-opacity-70'" @click="showAdvancedView = true">
        <p class="text-sm">{{ $strings.HeaderAdvanced }}</p>
      </div>
    </div>
    <div class="px-2 py-4 md:p-4 border border-white/10 rounded-b-md mr-px" style="min-height: 220px">
      <template v-if="!showAdvancedView">
        <div class="flex-grow pt-2 mb-2">
          <ui-checkbox v-model="preventIndexing" :label="$strings.LabelPreventIndexing" checkbox-bg="primary" border-color="gray-600" label-class="pl-2" />
        </div>
      </template>
      <template v-else>
        <div class="flex-grow pt-2 mb-2">
          <ui-checkbox v-model="preventIndexing" :label="$strings.LabelPreventIndexing" checkbox-bg="primary" border-color="gray-600" label-class="pl-2" />
        </div>
        <div class="w-full relative mb-1">
          <ui-text-input-with-label v-model="ownerName" :label="$strings.LabelRSSFeedCustomOwnerName" />
        </div>
        <div class="w-full relative mb-1">
          <ui-text-input-with-label v-model="ownerEmail" :label="$strings.LabelRSSFeedCustomOwnerEmail" />
        </div>
      </template>
    </div>
  </div>
</template>

<script>
export default {
  props: {
    value: {
      type: Object,
      default: () => {
        return {
          preventIndexing: true,
          ownerName: '',
          ownerEmail: ''
        }
      }
    }
  },
  data() {
    return {
      showAdvancedView: false
    }
  },
  watch: {},
  computed: {
    preventIndexing: {
      get() {
        return this.value.preventIndexing
      },
      set(value) {
        this.$emit('input', {
          ...this.value,
          preventIndexing: value
        })
      }
    },
    ownerName: {
      get() {
        return this.value.ownerName
      },
      set(value) {
        this.$emit('input', {
          ...this.value,
          ownerName: value
        })
      }
    },
    ownerEmail: {
      get() {
        return this.value.ownerEmail
      },
      set(value) {
        this.$emit('input', {
          ...this.value,
          ownerEmail: value
        })
      }
    }
  },
  methods: {},
  mounted() {}
}
</script>
