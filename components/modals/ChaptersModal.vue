<template>
  <modals-modal v-model="show" :width="400" height="100%">
    <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" data-modal-backdrop >
      <div ref="container" class="w-full overflow-x-hidden overflow-y-auto bg-surface rounded-2xl border border-outline-variant shadow-elevation-4 backdrop-blur-md" style="max-height: 75%" >
        <!-- Material 3 Modal Header -->
        <div v-if="currentChapter" class="px-6 py-4 border-b border-outline-variant">
          <h2 class="text-headline-small text-on-surface font-medium">{{ chapters.length }} {{ $strings.LabelChapters }}</h2>
        </div>

        <ul class="h-full w-full" role="listbox" aria-labelledby="listbox-label">
          <template v-for="chapter in chapters">
            <li :key="chapter.id" :id="`chapter-row-${chapter.id}`" class="text-on-surface select-none relative py-4 cursor-pointer state-layer" :class="currentChapterId === chapter.id ? 'bg-primary-container text-on-primary-container' : ''" role="option" @click="clickedOption(chapter)">
              <div class="relative flex items-center pl-3 pr-20">
                <p class="font-normal block truncate text-sm">{{ chapter.title }}</p>
                <div class="absolute top-0 right-3 -mt-0.5">
                  <span class="font-mono text-on-surface-variant leading-3 text-sm" style="letter-spacing: -0.5px">{{ $secondsToTimestamp(chapter.start / _playbackRate) }}</span>
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
    },
    playbackRate: Number
  },
  data() {
    return {}
  },
  watch: {
    value(newVal) {
      if (newVal) {
        this.$nextTick(this.scrollToChapter)
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
    _playbackRate() {
      if (!this.playbackRate || isNaN(this.playbackRate)) return 1
      return this.playbackRate
    },
    currentChapterId() {
      return this.currentChapter?.id
    },
    currentChapterTitle() {
      return this.currentChapter?.title || null
    }
  },
  methods: {
    clickedOption(chapter) {
      this.$emit('select', chapter)
    },
    scrollToChapter() {
      if (!this.currentChapterId) return

      const container = this.$refs.container
      if (container) {
        const currChapterEl = document.getElementById(`chapter-row-${this.currentChapterId}`)
        if (currChapterEl) {
          const offsetTop = currChapterEl.offsetTop
          const containerHeight = container.clientHeight
          container.scrollTo({ top: offsetTop - containerHeight / 2 })
        }
      }
    }
  },
  mounted() {}
}
</script>
