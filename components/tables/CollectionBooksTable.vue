<template>
  <div class="w-full bg-primary bg-opacity-40">
    <div class="w-full h-14 flex items-center px-4 bg-primary">
      <p>Collection List</p>
      <div class="w-6 h-6 bg-white bg-opacity-10 flex items-center justify-center rounded-full ml-2">
        <p class="font-mono text-sm">{{ books.length }}</p>
      </div>
      <div class="flex-grow" />
      <p v-if="totalDuration">{{ totalDurationPretty }}</p>
    </div>
    <template v-for="book in booksCopy">
      <tables-collection-book-table-row :key="book.id" :book="book" :collection-id="collectionId" class="item" :class="drag ? '' : 'collection-book-item'" @edit="editBook" />
    </template>
  </div>
</template>

<script>
export default {
  props: {
    collectionId: String,
    books: {
      type: Array,
      default: () => []
    }
  },
  data() {
    return {
      booksCopy: []
    }
  },
  watch: {
    books: {
      handler(newVal) {
        this.init()
      }
    }
  },
  computed: {
    totalDuration() {
      var _total = 0
      this.books.forEach((book) => {
        _total += book.duration
      })
      return _total
    },
    totalDurationPretty() {
      return this.$elapsedPretty(this.totalDuration)
    }
  },
  methods: {
    editBook(book) {
      var bookIds = this.books.map((b) => b.id)
      this.$store.commit('setBookshelfBookIds', bookIds)
      this.$store.commit('showEditModal', book)
    },
    init() {
      this.booksCopy = this.books.map((b) => ({ ...b }))
    }
  },
  mounted() {
    this.init()
  }
}
</script>

<style>
.collection-book-item {
  transition: all 0.4s ease;
}

.collection-book-enter-from,
.collection-book-leave-to {
  opacity: 0;
  transform: translateX(30px);
}

.collection-book-leave-active {
  position: absolute;
}
</style>