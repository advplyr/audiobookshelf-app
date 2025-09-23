<template>
  <modals-modal v-model="show" :width="width" height="100%">
    <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" data-modal-backdrop>
      <div ref="container" class="w-full overflow-x-hidden overflow-y-auto bg-surface rounded-lg border border-outline-variant shadow-elevation-4 p-2 backdrop-blur-md" style="max-height: 75%">
        <!-- Material 3 Modal Header -->
        <div v-if="title" class="px-4 py-4 border-b border-outline-variant">
          <h2 class="text-headline-small text-on-surface font-medium">{{ title }}</h2>
        </div>

        <ul class="h-full w-full" role="listbox" aria-labelledby="listbox-label">
          <template v-for="item in itemsToShow">
            <slot :name="item.value" :item="item" :selected="item.value === selected">
              <li :key="item.value" :ref="`item-${item.value}`" class="text-on-surface select-none relative cursor-pointer state-layer" :class="selected === item.value ? 'bg-primary-container text-on-primary-container' : ''" :style="{ paddingTop: itemPaddingY, paddingBottom: itemPaddingY }" role="option" @click="clickedOption(item.value)">
                <div class="relative flex items-center px-3">
                  <span v-if="item.icon" class="material-symbols text-xl mr-2 text-on-surface-variant">{{ item.icon }}</span>
                  <p class="font-normal block truncate text-base">{{ item.text }}</p>
                </div>
              </li>
            </slot>
          </template>
        </ul>
      </div>
    </div>
  </modals-modal>
</template>

<script>
export default {
  props: {
    value: Boolean,
    title: String,
    items: {
      type: Array,
      default: () => []
    },
    selected: [String, Number], // optional
    itemPaddingY: {
      type: String,
      default: '16px'
    },
    width: {
      type: [String, Number],
      default: 300
    }
  },
  data() {
    return {}
  },
  watch: {
    show: {
      immediate: true,
      handler(newVal) {
        if (newVal) this.$nextTick(this.init)
      }
    }
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
    itemsToShow() {
      return this.items.map((i) => {
        if (typeof i === 'string') {
          return {
            text: i,
            value: i
          }
        }
        return i
      })
    }
  },
  methods: {
    clickedOption(action) {
      this.$emit('action', action)
    },
    init() {
      if (this.selected && this.$refs[`item-${this.selected}`]?.[0]) {
        // Set scroll position so that selected item is in the center
        const containerOffset = this.$refs.container.offsetTop + this.$refs.container.clientHeight / 2
        const scrollAmount = this.$refs[`item-${this.selected}`][0].offsetTop - containerOffset
        this.$refs.container.scrollTo({
          top: scrollAmount
        })
      }
    }
  },
  mounted() {}
}
</script>
