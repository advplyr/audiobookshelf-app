<template>
  <div :key="bookmark.time" :id="`bookmark-row-${bookmark.time}`" class="flex items-center px-1 py-4 justify-start relative cursor-pointer state-layer" :class="highlight ? 'bg-primary-container text-on-primary-container' : 'text-on-surface'" @click="click">
    <div class="flex-grow overflow-hidden px-2">
      <div class="flex items-center mb-0.5">
        <i class="material-symbols text-lg pr-1 -mb-1" :class="{ 'text-primary fill': highlight, 'text-on-surface-variant': !highlight }">bookmark</i>
        <p class="truncate text-sm">
          {{ bookmark.title }}
        </p>
      </div>
      <p class="text-sm font-mono flex items-center" :class="highlight ? 'text-on-primary-container' : 'text-on-surface-variant'"><span class="material-symbols text-base pl-px pr-1">schedule</span>{{ $secondsToTimestamp(bookmark.time / playbackRate) }}</p>
    </div>
    <div class="h-full flex items-center justify-end transform w-16 pr-2" @click.stop>
      <span class="material-symbols text-2xl mr-2 cursor-pointer state-layer" :class="highlight ? 'text-on-primary-container' : 'text-on-surface'" @click.stop="editClick">edit</span>
      <span class="material-symbols text-2xl cursor-pointer state-layer" :class="highlight ? 'text-on-primary-container' : 'text-on-surface'" @click.stop="deleteClick">delete</span>
    </div>
  </div>
</template>

<script>
export default {
  props: {
    bookmark: {
      type: Object,
      default: () => {}
    },
    highlight: Boolean,
    playbackRate: Number
  },
  data() {
    return {}
  },
  computed: {},
  methods: {
    click() {
      this.$emit('click', this.bookmark)
    },
    deleteClick() {
      this.$emit('delete', this.bookmark)
    },
    editClick() {
      this.$emit('edit', this.bookmark)
    }
  }
}
</script>
