<template>
  <div class="flex h-full px-1 overflow-hidden">
    <covers-group-cover :name="name" :book-items="bookItems" :width="80" :height="60" :book-cover-aspect-ratio="bookCoverAspectRatio" />
    <div class="flex-grow px-2 seriesSearchCardContent h-full">
      <p class="truncate text-sm">{{ name }}</p>
    </div>
  </div>
</template>

<script>
export default {
  props: {
    series: {
      type: Object,
      default: () => {}
    },
    bookItems: {
      type: Array,
      default: () => []
    }
  },
  data() {
    return {}
  },
  computed: {
    bookCoverAspectRatio() {
      return this.$store.getters['getBookCoverAspectRatio']
    },
    name() {
      return this.series.name
    }
  },
  methods: {},
  mounted() {}
}
</script>

<style>
.seriesSearchCardContent {
  width: calc(100% - 80px);
  display: flex;
  flex-direction: column;
  justify-content: center;
}
</style>