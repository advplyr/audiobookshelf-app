<template>
  <modals-modal v-model="show" :width="400" max-width="95%" height="100%">
    <template #outer>
      <div v-if="currentChapter" class="absolute top-10 left-4 z-40 pt-1" style="max-width: 80%">
        <p class="text-white text-lg truncate">{{ chapters.length }} {{ $strings.LabelChapters }}</p>
      </div>
    </template>

    <div class="w-full h-full overflow-hidden absolute top-0 left-0 flex items-center justify-center" @click="show = false">
      <div ref="container" class="w-full overflow-x-hidden overflow-y-auto bg-secondary rounded-lg border border-fg/20" style="max-height: 75%" @click.stop>
        <div class="sticky top-0 z-10 bg-secondary grid grid-cols-[1fr_auto_auto] gap-2 px-3 py-2 border-b border-fg/10 transition-shadow" :class="{ 'shadow-md': isScrolled }">
          <div>
            <p class="text-fg-muted text-sm">{{ $strings.LabelChapters }}</p>
          </div>
          <div class="text-right" style="min-width: 60px">
            <p class="text-fg-muted text-sm" style="letter-spacing: -0.5px">{{ $strings.LabelStart }}</p>
          </div>
          <div class="text-right" style="min-width: 60px">
            <p class="text-fg-muted text-sm" style="letter-spacing: -0.5px">{{ $strings.LabelDuration }}</p>
          </div>
        </div>
        <ul class="h-full w-full" role="listbox" aria-labelledby="listbox-label">
          <li v-for="chapter in chapters" :key="chapter.id" :id="`chapter-row-${chapter.id}`" class="text-fg select-none relative cursor-pointer" :class="currentChapterId === chapter.id ? 'bg-primary bg-opacity-80' : ''" role="option" @click="clickedOption(chapter)">
            <div class="grid grid-cols-[1fr_auto_auto] gap-2 px-3 py-3 items-start">
              <p class="font-normal line-clamp-2 text-sm text-fg/80">{{ chapter.title }}</p>
              <div class="text-right" style="min-width: 60px">
                <span class="font-mono text-fg-muted text-sm" style="letter-spacing: -0.5px">{{ $secondsToTimestamp(chapter.start / _playbackRate) }}</span>
              </div>
              <div class="text-right" style="min-width: 60px">
                <span class="font-mono text-fg-muted text-sm" style="letter-spacing: -0.5px">{{ $secondsToTimestamp(Math.max(0, chapter.end - chapter.start) / _playbackRate) }}</span>
              </div>
            </div>

            <div v-show="chapter.id === currentChapterId" class="w-0.5 h-full absolute top-0 left-0 bg-yellow-400" />
          </li>
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
    return {
      isScrolled: false
    }
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
    },
    handleScroll() {
      const container = this.$refs.container
      if (container) {
        this.isScrolled = container.scrollTop > 0
      }
    }
  },
  mounted() {
    const container = this.$refs.container
    if (container) {
      container.addEventListener('scroll', this.handleScroll)
    }
  },
  beforeDestroy() {
    const container = this.$refs.container
    if (container) {
      container.removeEventListener('scroll', this.handleScroll)
    }
  }
}
</script>
