<template>
  <modals-modal v-model="show" :width="width" height="100%">
    <template #outer>
      <div v-if="title" class="absolute top-10 left-4 z-40 pt-1 pb-1.5" style="max-width: 80%">
        <p class="text-white text-xl truncate">{{ title }}</p>
      </div>
    </template>

    <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" @click="show = false">
      <div ref="container" class="w-full overflow-x-hidden overflow-y-auto bg-primary rounded-lg border border-white/20 p-2" style="max-height: 75%" @click.stop>
        <ul class="h-full w-full" role="listbox" aria-labelledby="listbox-label">
          <template v-for="item in itemsToShow">
            <slot :name="item.value" :item="item" :selected="item.value === selected">
              <li :key="item.value" :ref="`item-${item.value}`" class="text-gray-50 select-none relative cursor-pointer hover:bg-black-400" :class="selected === item.value ? 'bg-success bg-opacity-10' : ''" :style="{ paddingTop: itemPaddingY, paddingBottom: itemPaddingY }" role="option" @click="clickedOption(item.value)">
                <div class="relative flex items-center px-3">
                  <span v-if="item.icon" class="material-icons-outlined text-xl mr-2 text-white text-opacity-80">{{ item.icon }}</span>
                  <p class="font-normal block truncate text-base text-white text-opacity-80">{{ item.text }}</p>
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
      type: Number,
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
