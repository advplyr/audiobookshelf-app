<template>
  <div class="flex items-center w-full px-4 py-2 border-b border-white border-opacity-10 active:bg-white active:bg-opacity-5" @click="navigate">
    <div class="rounded overflow-hidden flex-shrink-0" style="width: 48px; height: 60px">
      <covers-author-image :author="author" />
    </div>
    <div class="flex-grow px-3 overflow-hidden">
      <p class="text-base font-semibold truncate text-fg">{{ name }}</p>
      <p class="text-sm text-gray-400">{{ numBooks }} {{ $strings.LabelBooks }}</p>
    </div>
    <span class="material-symbols text-fg text-opacity-40 text-xl">chevron_right</span>
  </div>
</template>

<script>
export default {
  props: {
    author: {
      type: Object,
      default: () => {}
    }
  },
  computed: {
    name() {
      return this.author.name || ''
    },
    numBooks() {
      return this.author.numBooks || 0
    }
  },
  methods: {
    navigate() {
      this.$router.push(`/bookshelf/library?filter=authors.${this.$encode(this.author.id)}`)
    }
  }
}
</script>
