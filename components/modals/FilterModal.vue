<template>
  <modals-modal v-model="show" width="90%" height="100%">
    <template #outer>
      <div v-show="selected !== 'all'" class="absolute top-4 left-4 z-40">
        <ui-btn class="text-xl border-yellow-400 border-opacity-40" @click="clearSelected">Clear</ui-btn>
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
              <span class="font-normal block truncate py-5 text-lg">No {{ sublist }} items</span>
            </div>
          </li>
          <template v-for="item in sublistItems">
            <li :key="item.value" class="text-gray-50 select-none relative px-4 cursor-pointer hover:bg-black-400" :class="`${sublist}.${item.value}` === selected ? 'bg-bg bg-opacity-50' : ''" role="option" @click="clickedSublistOption(item.value)">
              <div class="flex items-center">
                <span class="font-normal truncate py-3 text-base">{{ item.text }}</span>
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
      bookItems: [
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
        },
        {
          text: 'Authors',
          value: 'authors',
          sublist: true
        },
        {
          text: 'Narrator',
          value: 'narrators',
          sublist: true
        },
        {
          text: 'Language',
          value: 'languages',
          sublist: true
        },
        {
          text: 'Progress',
          value: 'progress',
          sublist: true
        },
        {
          text: 'Issues',
          value: 'issues',
          sublist: false
        }
      ],
      podcastItems: [
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
    isPodcast() {
      return this.$store.getters['libraries/getCurrentLibraryMediaType'] === 'podcast'
    },
    items() {
      if (this.isPodcast) return this.podcastItems
      return this.bookItems
    },
    selectedItemSublist() {
      return this.selected && this.selected.includes('.') ? this.selected.split('.')[0] : false
    },
    genres() {
      return this.filterData.genres || []
    },
    tags() {
      return this.filterData.tags || []
    },
    series() {
      return this.filterData.series || []
    },
    authors() {
      return this.filterData.authors || []
    },
    narrators() {
      return this.filterData.narrators || []
    },
    languages() {
      return this.filterData.languages || []
    },
    progress() {
      return [
        {
          id: 'finished',
          name: 'Finished'
        },
        {
          id: 'in-progress',
          name: 'In Progress'
        },
        {
          id: 'not-started',
          name: 'Not Started'
        },
        {
          id: 'not-finished',
          name: 'Not Finished'
        }
      ]
    },
    sublistItems() {
      return (this[this.sublist] || []).map((item) => {
        if (typeof item === 'string') {
          return {
            text: item,
            value: this.$encode(item)
          }
        } else {
          return {
            text: item.name,
            value: this.$encode(item.id)
          }
        }
      })
    },
    filterData() {
      return this.$store.state.libraries.filterData || {}
    }
  },
  methods: {
    async clearSelected() {
      await this.$hapticsImpact()
      this.selected = 'all'
      this.show = false
      this.$nextTick(() => this.$emit('change', 'all'))
    },
    clickedSublistOption(item) {
      this.clickedOption({ value: `${this.sublist}.${item}` })
    },
    async clickedOption(option) {
      if (option.sublist) {
        this.sublist = option.value
        return
      }

      var val = option.value
      if (this.selected === val) {
        this.show = false
        return
      }
      await this.$hapticsImpact()
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
