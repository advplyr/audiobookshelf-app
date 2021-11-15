<template>
  <div class="w-full h-full">
    <template v-for="(shelf, index) in shelves">
      <bookshelf-group-shelf v-if="!selectedSeriesName" :key="shelf.id" group-type="series" :groups="shelf.groups" :style="{ zIndex: shelves.length - index }" />
      <bookshelf-library-shelf v-else :key="shelf.id" :books="shelf.books" :style="{ zIndex: shelves.length - index }" />
    </template>
  </div>
</template>

<script>
export default {
  data() {
    return {
      groupsPerRow: 2,
      booksPerRow: 3,
      selectedSeriesName: null
    }
  },
  watch: {
    routeQuery: {
      handler(newVal) {
        if (newVal && newVal.series) {
          console.log('Select series')
          this.selectedSeriesName = this.$decode(newVal.series)
        } else {
          this.selectedSeriesName = null
        }
      }
    }
  },
  computed: {
    routeQuery() {
      return this.$route.query
    },
    series() {
      return this.$store.getters['audiobooks/getSeriesGroups']()
    },
    seriesShelves() {
      var shelves = []
      var shelf = {
        id: 0,
        groups: []
      }
      for (let i = 0; i < this.series.length; i++) {
        var shelfNum = Math.floor((i + 1) / this.groupsPerRow)
        shelf.id = shelfNum
        shelf.groups.push(this.series[i])

        if ((i + 1) % this.groupsPerRow === 0) {
          shelves.push(shelf)
          shelf = {
            id: 0,
            groups: []
          }
        }
      }
      if (shelf.groups.length) {
        shelves.push(shelf)
      }
      return shelves
    },
    selectedSeries() {
      if (!this.selectedSeriesName) return null
      return this.series.find((s) => s.name === this.selectedSeriesName)
    },
    seriesBooksShelves() {
      if (!this.selectedSeries) return []
      var seriesBooks = this.selectedSeries.books || []

      var shelves = []
      var shelf = {
        id: 0,
        books: []
      }
      for (let i = 0; i < seriesBooks.length; i++) {
        var shelfNum = Math.floor((i + 1) / this.booksPerRow)
        shelf.id = shelfNum
        shelf.books.push(seriesBooks[i])

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
    },
    shelves() {
      if (this.selectedSeries) {
        return this.seriesBooksShelves
      } else {
        return this.seriesShelves
      }
    }
  },
  methods: {},
  mounted() {}
}
</script>