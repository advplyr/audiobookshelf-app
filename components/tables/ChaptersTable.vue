<template>
  <div class="w-full my-4">
    <div class="w-full bg-surface-container text-on-surface px-4 py-3 flex items-center border border-outline-variant" :class="expanded ? 'rounded-t-2xl' : 'rounded-2xl'" @click.stop="clickBar">
      <p class="pr-2 font-medium">{{ $strings.HeaderChapters }}</p>
      <div class="h-6 w-6 rounded-full bg-secondary-container text-on-secondary-container flex items-center justify-center">
        <span class="text-label-small font-mono">{{ chapters.length }}</span>
      </div>
      <div class="flex-grow" />
      <div class="h-10 w-10 rounded-full bg-secondary-container text-on-secondary-container flex justify-center items-center duration-500 hover:bg-secondary-container/80" :class="expanded ? 'transform rotate-180' : ''">
        <span class="material-symbols text-display-small text-on-surface">arrow_drop_down</span>
      </div>
    </div>
    <transition name="slide">
      <div class="bg-surface-container border-x border-b border-outline-variant rounded-b-2xl px-4 py-2" v-show="expanded">
        <table class="text-body-medium tracksTable w-full">
          <tr class="border-b border-outline-variant">
            <th class="text-left py-2 text-on-surface font-medium">{{ $strings.LabelTitle }}</th>
            <th class="text-center w-20 py-2 text-on-surface font-medium">{{ $strings.LabelStart }}</th>
          </tr>
          <tr v-for="chapter in chapters" :key="chapter.id" class="hover:bg-surface-container-high">
            <td class="py-2 text-on-surface-variant">
              {{ chapter.title }}
            </td>
            <td class="font-mono text-center underline w-20 py-2 text-primary hover:text-primary/80" @click.stop="goToTimestamp(chapter.start)">
              {{ $secondsToTimestamp(chapter.start) }}
            </td>
          </tr>
        </table>
      </div>
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
