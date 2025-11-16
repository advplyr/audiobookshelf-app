<template>
  <div class="w-full my-4">
    <div class="w-full bg-primary px-4 py-2 flex items-center" :class="expanded ? 'rounded-t-md' : 'rounded-md'" @click.stop="clickBar">
      <p class="pr-2">{{ $strings.HeaderChapters }}</p>
      <div class="h-6 w-6 rounded-full bg-fg/10 flex items-center justify-center">
        <span class="text-xs font-mono">{{ chapters.length }}</span>
      </div>
      <div class="flex-grow" />
      <div class="h-10 w-10 rounded-full flex justify-center items-center duration-500" :class="expanded ? 'transform rotate-180' : ''">
        <span class="material-symbols text-3xl">arrow_drop_down</span>
      </div>
    </div>
    <transition name="slide">
      <table class="text-xs tracksTable" v-show="expanded">
        <tr>
          <th class="text-left">{{ $strings.LabelTitle }}</th>
          <th class="text-center w-16">{{ $strings.LabelStart }}</th>
          <th class="text-center w-16">{{ $strings.LabelDuration }}</th>
        </tr>
        <tr v-for="chapter in chapters" :key="chapter.id">
          <td>
            {{ chapter.title }}
          </td>
          <td class="font-mono text-center underline w-16" @click.stop="goToTimestamp(chapter.start)">
            {{ $secondsToTimestamp(chapter.start) }}
          </td>
          <td class="font-mono text-center">
            {{ $secondsToTimestamp(Math.max(0, chapter.end - chapter.start)) }}
          </td>
        </tr>
      </table>
    </transition>
  </div>
</template>

<script>
export default {
  props: {
    libraryItem: {
      type: Object,
      default: () => {}
    }
  },
  data() {
    return {
      expanded: false
    }
  },
  computed: {
    libraryItemId() {
      return this.libraryItem.id
    },
    media() {
      return this.libraryItem ? this.libraryItem.media || {} : {}
    },
    metadata() {
      return this.media.metadata || {}
    },
    chapters() {
      return this.media.chapters || []
    },
    userCanUpdate() {
      return this.$store.getters['user/getUserCanUpdate']
    }
  },
  methods: {
    clickBar() {
      this.expanded = !this.expanded
    },
    goToTimestamp(time) {
      this.$emit('playAtTimestamp', time)
    }
  },
  mounted() {}
}
</script>
