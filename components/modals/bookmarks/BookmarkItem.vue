<template>
  <div :key="bookmark.id" :id="`bookmark-row-${bookmark.id}`" class="flex items-center px-1 py-4 justify-start cursor-pointer hover:bg-bg relative" :class="highlight ? 'bg-bg bg-opacity-60' : ' bg-opacity-20'" @click="click">
    <div class="flex-grow overflow-hidden px-2">
      <div class="flex items-center mb-0.5">
        <i class="material-icons text-lg pr-1 -mb-1" :class="highlight ? 'text-success' : 'text-white/60'">{{ highlight ? 'bookmark' : 'bookmark_border' }}</i>
        <p class="truncate text-sm">
          {{ bookmark.title }}
        </p>
      </div>
      <p class="text-sm font-mono text-gray-300 flex items-center"><span class="material-icons text-base text-white/60 pl-px pr-1">schedule</span>{{ $secondsToTimestamp(bookmark.time) }}</p>
    </div>
    <div class="h-full flex items-center justify-end transform w-16 pr-2" @click.stop>
      <span class="material-icons text-2xl mr-2 text-gray-200 hover:text-yellow-400" @click.stop="editClick">edit</span>
      <span class="material-icons text-2xl text-gray-200 hover:text-error cursor-pointer" @click.stop="deleteClick">delete</span>
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
    highlight: Boolean
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
  },
  mounted() {}
}
</script>