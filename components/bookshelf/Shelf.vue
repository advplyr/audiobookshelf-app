<template>
  <div class="w-full relative">
    <div class="bookshelfRow h-44 flex items-end px-3 max-w-full overflow-x-auto">
      <template v-for="book in books">
        <cards-book-card :key="book.id" :audiobook="book" :width="100" class="mx-2" />
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
    books: {
      type: Array,
      default: () => []
    }
  },
  data() {
    return {}
  },
  computed: {},
  methods: {},
  mounted() {}
}
</script>
