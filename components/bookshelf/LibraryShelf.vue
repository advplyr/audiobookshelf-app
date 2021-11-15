<template>
  <div class="w-full relative">
    <div class="bookshelfRow h-48 flex items-end justify-around px-3 max-w-full">
      <template v-for="book in books">
        <cards-book-card :key="book.id" :audiobook="book" :width="108" class="mx-2" />
      </template>
    </div>

    <div class="w-full h-4 z-40 bookshelfDivider"></div>
  </div>
</template>

<script>
export default {
  props: {
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
