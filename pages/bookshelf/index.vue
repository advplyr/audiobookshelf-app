<template>
  <div class="w-full h-full">
    <template v-for="(shelf, index) in shelves">
      <bookshelf-shelf :key="shelf.id" :label="shelf.label" :books="shelf.books" :style="{ zIndex: shelves.length - index }" />
    </template>
  </div>
</template>

<script>
export default {
  data() {
    return {
      settings: {}
    }
  },
  computed: {
    books() {
      // return this.$store.getters['audiobooks/getFilteredAndSorted']()
      return this.$store.state.audiobooks.audiobooks
    },
    booksWithUserAbData() {
      var books = this.books.map((b) => {
        var userAbData = this.$store.getters['user/getUserAudiobookData'](b.id)
        return { ...b, userAbData }
      })
      return books
    },
    booksCurrentlyReading() {
      var books = this.booksWithUserAbData
        .map((b) => ({ ...b }))
        .filter((b) => b.userAbData && !b.userAbData.isRead && b.userAbData.progress > 0)
        .sort((a, b) => {
          return b.userAbData.lastUpdate - a.userAbData.lastUpdate
        })
      return books
    },
    booksRecentlyAdded() {
      var books = this.books
        .map((b) => {
          return { ...b }
        })
        .sort((a, b) => b.addedAt - a.addedAt)
      return books.slice(0, 10)
    },
    booksRead() {
      var books = this.booksWithUserAbData
        .filter((b) => b.userAbData && b.userAbData.isRead)
        .sort((a, b) => {
          return b.userAbData.lastUpdate - a.userAbData.lastUpdate
        })
      return books.slice(0, 10)
    },
    shelves() {
      var shelves = []

      if (this.booksCurrentlyReading.length) {
        shelves.push({
          id: 'recent',
          label: 'Continue Reading',
          books: this.booksCurrentlyReading
        })
      }

      if (this.booksRecentlyAdded.length) {
        shelves.push({
          id: 'added',
          label: 'Recently Added',
          books: this.booksRecentlyAdded
        })
      }

      if (this.booksRead.length) {
        shelves.push({
          id: 'read',
          label: 'Read Again',
          books: this.booksRead
        })
      }
      return shelves
    }
  },
  methods: {
    async init() {
      this.settings = { ...this.$store.state.user.settings }

      // var bookshelfView = await this.$localStore.getBookshelfView()
      // this.isListView = bookshelfView === 'list'
      // this.bookshelfReady = true
      // console.log('Bookshelf view', bookshelfView)
    }
  },
  mounted() {}
}
</script>