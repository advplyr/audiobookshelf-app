<template>
  <div class="w-full h-full">
    <div class="w-full h-full" v-show="!isListView">
      <template v-for="(shelf, index) in shelves">
        <bookshelf-library-shelf :key="shelf.id" :books="shelf.books" :style="{ zIndex: shelves.length - index }" />
      </template>
    </div>
    <div class="w-full h-full px-4 py-2" v-show="isListView">
      <template v-for="book in books">
        <app-bookshelf-list-row :key="book.id" :audiobook="book" :page-width="pageWidth" class="my-2" />
      </template>
    </div>
  </div>
</template>

<script>
export default {
  data() {
    return {
      booksPerRow: 3,
      pageWidth: 0
    }
  },
  computed: {
    bookshelfView() {
      return this.$store.state.bookshelfView
    },
    isListView() {
      return this.bookshelfView === 'list'
    },
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
        shelf.id++
        shelves.push(shelf)
      }
      return shelves
    }
  },
  methods: {},
  mounted() {
    this.pageWidth = window.innerWidth
  }
}
</script>
