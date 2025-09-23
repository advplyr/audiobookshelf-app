<template>
  <div class="w-full bg-surface-container border border-outline-variant rounded-2xl">
    <div class="w-full h-14 flex items-center px-4 bg-surface-container text-on-surface border-b border-outline-variant rounded-t-2xl">
      <p class="pr-4 font-medium">{{ $strings.HeaderCollectionItems }}</p>

      <div class="w-6 h-6 md:w-7 md:h-7 bg-secondary-container text-on-secondary-container rounded-full flex items-center justify-center">
        <span class="text-label-small md:text-body-medium font-mono leading-none">{{ books.length }}</span>
      </div>

      <div class="flex-grow" />
      <p v-if="totalDuration" class="text-body-medium text-on-surface-variant">{{ totalDurationPretty }}</p>
    </div>
    <template v-for="book in booksCopy">
      <tables-collection-book-table-row :key="book.id" :book="book" :collection-id="collectionId" class="item collection-book-item" @edit="editBook" />
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
        _total += book.media.duration
      })
      return _total
    },
    totalDurationPretty() {
      return this.$elapsedPrettyExtended(this.totalDuration)
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