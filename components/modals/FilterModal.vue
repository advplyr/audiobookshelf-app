<template>
  <modals-modal v-model="show" width="90%" height="100%">
    <template #outer>
      <div v-show="selected !== 'all'" class="absolute top-4 left-4 z-40">
        <ui-btn class="text-xl border-yellow-400 border-opacity-40" @click="clearSelected">Clear</ui-btn>
        <!-- <span class="font-semibold uppercase text-white text-2xl">Clear</span> -->
      </div>
    </template>
    <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" @click="show = false">
      <div class="w-full overflow-x-hidden overflow-y-auto bg-primary rounded-lg border border-white border-opacity-20" style="max-height: 75%" @click.stop>
        <ul v-show="!sublist" class="h-full w-full" role="listbox" aria-labelledby="listbox-label">
          <template v-for="item in items">
            <li :key="item.value" class="text-gray-50 select-none relative py-4 pr-9 cursor-pointer hover:bg-black-400" :class="item.value === selected ? 'bg-bg bg-opacity-50' : ''" role="option" @click="clickedOption(item)">
              <div class="flex items-center justify-between">
                <span class="font-normal ml-3 block truncate text-lg">{{ item.text }}</span>
              </div>
              <div v-if="item.sublist" class="absolute right-1 top-0 bottom-0 h-full flex items-center">
                <span class="material-icons text-2xl">arrow_right</span>
              </div>
            </li>
          </template>
        </ul>
        <ul v-show="sublist" class="h-full w-full rounded-lg" role="listbox" aria-labelledby="listbox-label">
          <li class="text-gray-50 select-none relative py-3 pl-9 cursor-pointer hover:bg-black-400" role="option" @click="sublist = null">
            <div class="absolute left-1 top-0 bottom-0 h-full flex items-center">
              <span class="material-icons text-2xl">arrow_left</span>
            </div>
            <div class="flex items-center justify-between">
              <span class="font-normal ml-3 block truncate text-lg">Back</span>
            </div>
          </li>
          <li v-if="!sublistItems.length" class="text-gray-400 select-none relative px-2" role="option">
            <div class="flex items-center justify-center">
              <span class="font-normal block truncate py-3">No {{ sublist }}</span>
            </div>
          </li>
          <template v-for="item in sublistItems">
            <li :key="item" class="text-gray-50 select-none relative px-2 cursor-pointer hover:bg-black-400" :class="`${sublist}.${item}` === selected ? 'bg-bg bg-opacity-50' : ''" role="option" @click="clickedSublistOption(item)">
              <div class="flex items-center">
                <span class="font-normal truncate py-3 text-base">{{ snakeToNormal(item) }}</span>
              </div>
            </li>
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
    filterBy: String
  },
  data() {
    return {
      sublist: null,
      items: [
        {
          text: 'All',
          value: 'all'
        },
        {
          text: 'Genre',
          value: 'genres',
          sublist: true
        },
        {
          text: 'Tag',
          value: 'tags',
          sublist: true
        },
        {
          text: 'Series',
          value: 'series',
          sublist: true
        }
      ]
    }
  },
  watch: {
    show(newVal) {
      if (!newVal) {
        if (this.sublist && !this.selectedItemSublist) this.sublist = null
        if (!this.sublist && this.selectedItemSublist) this.sublist = this.selectedItemSublist
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
    selected: {
      get() {
        return this.filterBy
      },
      set(val) {
        this.$emit('update:filterBy', val)
      }
    },
    selectedItemSublist() {
      return this.selected && this.selected.includes('.') ? this.selected.split('.')[0] : false
    },
    genres() {
      return this.$store.state.audiobooks.genres
    },
    tags() {
      return this.$store.state.audiobooks.tags
    },
    series() {
      return this.$store.state.audiobooks.series
    },
    sublistItems() {
      return this[this.sublist] || []
    }
  },
  methods: {
    clearSelected() {
      this.selected = 'all'
      this.show = false
      this.$nextTick(() => this.$emit('change', 'all'))
    },
    snakeToNormal(kebab) {
      if (!kebab) {
        return 'err'
      }
      return String(kebab)
        .split('_')
        .map((t) => t.slice(0, 1).toUpperCase() + t.slice(1))
        .join(' ')
    },
    clickedSublistOption(item) {
      this.clickedOption({ value: `${this.sublist}.${item}` })
    },
    clickedOption(option) {
      if (option.sublist) {
        this.sublist = option.value
        return
      }

      var val = option.value
      if (this.selected === val) {
        this.show = false
        return
      }
      this.selected = val
      this.show = false
      this.$nextTick(() => this.$emit('change', val))
    }
  },
  mounted() {}
}
</script>

<style>
.filter-modal-wrapper {
  max-height: calc(100% - 320px);
}
</style>