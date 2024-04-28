<template>
  <modals-modal v-model="show" width="90%" height="100%">
    <template #outer>
      <div v-show="selected !== 'all'" class="absolute top-12 left-4 z-40">
        <ui-btn class="text-lg border-yellow-400 border-opacity-40 h-10" :padding-y="0" @click="clearSelected">{{ $strings.ButtonClearFilter }}</ui-btn>
      </div>
    </template>
    <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" @click="show = false">
      <div class="w-full overflow-x-hidden overflow-y-auto bg-primary rounded-lg border border-fg/20 mt-8" style="max-height: 75%" @click.stop>
        <ul v-show="!sublist" class="h-full w-full" role="listbox" aria-labelledby="listbox-label">
          <template v-for="item in items">
            <li :key="item.value" class="text-fg select-none relative py-4 pr-9 cursor-pointer" :class="item.value === selected ? 'bg-bg bg-opacity-50' : ''" role="option" @click="clickedOption(item)">
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
          <li class="text-fg select-none relative py-3 pl-9 cursor-pointer" role="option" @click="sublist = null">
            <div class="absolute left-1 top-0 bottom-0 h-full flex items-center">
              <span class="material-icons text-2xl">arrow_left</span>
            </div>
            <div class="flex items-center justify-between">
              <span class="font-normal ml-3 block truncate text-lg">{{ $strings.ButtonBack }}</span>
            </div>
          </li>
          <li v-if="!sublistItems.length" class="text-gray-400 select-none relative px-2" role="option">
            <div class="flex items-center justify-center">
              <span class="font-normal block truncate py-5 text-lg">No {{ sublist }} items</span>
            </div>
          </li>
          <template v-for="item in sublistItems">
            <li :key="item.value" class="text-fg select-none relative px-4 cursor-pointer" :class="`${sublist}.${item.value}` === selected ? 'bg-bg bg-opacity-50' : ''" role="option" @click="clickedSublistOption(item.value)">
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
          text: this.$strings.LabelAll,
          value: 'all'
        },
        {
          text: this.$strings.LabelGenre,
          value: 'genres',
          sublist: true
        },
        {
          text: this.$strings.LabelTag,
          value: 'tags',
          sublist: true
        },
        {
          text: this.$strings.LabelSeries,
          value: 'series',
          sublist: true
        },
        {
          text: this.$strings.LabelAuthor,
          value: 'authors',
          sublist: true
        },
        {
          text: this.$strings.LabelNarrator,
          value: 'narrators',
          sublist: true
        },
        {
          text: this.$strings.LabelLanguage,
          value: 'languages',
          sublist: true
        },
        {
          text: this.$strings.LabelProgress,
          value: 'progress',
          sublist: true
        },
        {
          text: this.$strings.LabelEbooks,
          value: 'ebooks',
          sublist: true
        },
        {
          text: this.$strings.ButtonIssues,
          value: 'issues',
          sublist: false
        }
      ],
      podcastItems: [
        {
          text: this.$strings.LabelAll,
          value: 'all'
        },
        {
          text: this.$strings.LabelGenre,
          value: 'genres',
          sublist: true
        },
        {
          text: this.$strings.LabelTag,
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
          name: this.$strings.LabelFinished
        },
        {
          id: 'in-progress',
          name: this.$strings.LabelInProgress
        },
        {
          id: 'not-started',
          name: this.$strings.LabelNotStarted
        },
        {
          id: 'not-finished',
          name: this.$strings.LabelNotFinished
        }
      ]
    },
    ebooks() {
      return [
        {
          id: 'ebook',
          name: this.$strings.LabelHasEbook
        },
        {
          id: 'supplementary',
          name: this.$strings.LabelHasSupplementaryEbook
        }
      ]
    },
    sublistItems() {
      const sublistItems = (this[this.sublist] || []).map((item) => {
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
      if (this.sublist === 'series') {
        sublistItems.unshift({
          text: this.$strings.MessageNoSeries,
          value: this.$encode('no-series')
        })
      }
      return sublistItems
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
