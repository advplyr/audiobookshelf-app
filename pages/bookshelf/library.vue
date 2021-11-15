<template>
  <div class="w-full h-full">
    <template v-for="(shelf, index) in shelves">
      <bookshelf-library-shelf :key="shelf.id" :books="shelf.books" :style="{ zIndex: shelves.length - index }" />
    </template>
  </div>
</template>

<script>
export default {
  data() {
    return {
      booksPerRow: 3
    }
  },
  computed: {
    books() {
      return this.$store.getters['audiobooks/getFilteredAndSorted']()
    },
    shelves() {
      var shelves = []
      var shelf = {
        id: 0,
        books: []
      }
      for (let i = 0; i < this.books.length; i++) {
        var shelfNum = Math.floor((i + 1) / this.booksPerRow)
        shelf.id = shelfNum
        shelf.books.push(this.books[i])

        if ((i + 1) % this.booksPerRow === 0) {
          shelves.push(shelf)
          shelf = {
            id: 0,
            books: []
          }
        }
      }
      if (shelf.books.length) {
        shelves.push(shelf)
      }
      return shelves
    }
  },
  methods: {},
  mounted() {}
}
</script>