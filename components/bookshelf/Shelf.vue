<template>
  <div class="w-full relative">
    <div class="bookshelfRow flex items-end px-3 max-w-full overflow-x-auto" :style="{ height: shelfHeight + 'px' }">
      <template v-for="(entity, index) in entities">
        <cards-lazy-book-card v-if="type === 'book' || type === 'podcast'" :key="entity.id" :index="index" :book-mount="entity" :width="bookWidth" :height="entityHeight" :book-cover-aspect-ratio="bookCoverAspectRatio" is-categorized class="mx-2 relative" />
        <cards-lazy-series-card v-else-if="type === 'series'" :key="entity.id" :index="index" :series-mount="entity" :width="bookWidth * 2" :height="entityHeight" :book-cover-aspect-ratio="bookCoverAspectRatio" is-categorized class="mx-2 relative" />
      </template>
    </div>

    <div class="absolute text-center categoryPlacard font-book transform z-30 bottom-0.5 left-4 md:left-8 w-36 rounded-md" style="height: 18px">
      <div class="w-full h-full shinyBlack flex items-center justify-center rounded-sm border">
        <p class="transform text-xs">{{ label }}</p>
      </div>
    </div>
    <div class="w-full h-5 z-40 bookshelfDivider"></div>
  </div>
</template>

<script>
export default {
  props: {
    label: String,
    type: String,
    entities: {
      type: Array,
      default: () => []
    }
  },
  data() {
    return {}
  },
  computed: {
    shelfHeight() {
      return this.entityHeight + 40
    },
    bookWidth() {
      var coverSize = 100
      if (this.bookCoverAspectRatio === 1) return coverSize * 1.6
      return coverSize
    },
    bookHeight() {
      if (this.bookCoverAspectRatio === 1) return this.bookWidth
      return this.bookWidth * 1.6
    },
    entityHeight() {
      return this.bookHeight
    },
    bookCoverAspectRatio() {
      return this.$store.getters['getBookCoverAspectRatio']
    }
  },
  methods: {},
  mounted() {}
}
</script>
