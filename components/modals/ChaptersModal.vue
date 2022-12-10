<template>
  <modals-modal v-model="show" :width="350" height="100%">
    <template #outer>
      <div v-if="currentChapter" class="absolute top-7 left-4 z-40" style="max-width: 80%">
        <p class="text-white text-lg truncate">Current: {{ currentChapterTitle }}</p>
      </div>
    </template>

    <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" @click="show = false">
      <div ref="container" class="w-full overflow-x-hidden overflow-y-auto bg-primary rounded-lg border border-white border-opacity-20" style="max-height: 75%" @click.stop>
        <ul class="h-full w-full" role="listbox" aria-labelledby="listbox-label">
          <template v-for="chapter in chapters">
            <li :key="chapter.id" :id="`chapter-row-${chapter.id}`" class="text-gray-50 select-none relative py-4 cursor-pointer hover:bg-black-400" :class="currentChapterId === chapter.id ? 'bg-bg bg-opacity-80' : ''" role="option" @click="clickedOption(chapter)">
              <div class="relative flex items-center pl-3 pr-20">
                <p class="font-normal block truncate text-sm text-white text-opacity-80">{{ chapter.title }}</p>
                <div class="absolute top-0 right-3 -mt-0.5">
                  <span class="font-mono text-white text-opacity-90 leading-3 text-sm" style="letter-spacing: -0.5px">{{ $secondsToTimestamp(chapter.start) }}</span>
                </div>
              </div>

              <div v-show="chapter.id === currentChapterId" class="w-0.5 h-full absolute top-0 left-0 bg-yellow-400" />
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
    chapters: {
      type: Array,
      default: () => []
    },
    currentChapter: {
      type: Object,
      default: () => null
    }
  },
  data() {
    return {}
  },
  watch: {
    value(newVal) {
      this.$nextTick(this.scrollToChapter)
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
    currentChapterId() {
      return this.currentChapter ? this.currentChapter.id : null
    },
    currentChapterTitle() {
      return this.currentChapter ? this.currentChapter.title : null
    }
  },
  methods: {
    clickedOption(chapter) {
      this.$emit('select', chapter)
    },
    scrollToChapter() {
      if (!this.currentChapterId) return

      var container = this.$refs.container
      if (container) {
        var currChapterEl = document.getElementById(`chapter-row-${this.currentChapterId}`)
        if (currChapterEl) {
          var offsetTop = currChapterEl.offsetTop
          var containerHeight = container.clientHeight
          container.scrollTo({ top: offsetTop - containerHeight / 2 })
        }
      }
    }
  },
  mounted() {}
}
</script>
