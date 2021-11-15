<template>
  <div class="w-full h-full">
    <template v-for="(shelf, index) in shelves">
      <bookshelf-group-shelf :key="shelf.id" group-type="collection" :groups="shelf.groups" :style="{ zIndex: shelves.length - index }" />
    </template>
  </div>
</template>

<script>
export default {
  data() {
    return {
      groupsPerRow: 2
    }
  },
  watch: {},
  computed: {
    collections() {
      return this.$store.state.user.collections || []
    },
    shelves() {
      var shelves = []
      var shelf = {
        id: 0,
        groups: []
      }
      for (let i = 0; i < this.collections.length; i++) {
        var shelfNum = Math.floor((i + 1) / this.groupsPerRow)
        shelf.id = shelfNum
        shelf.groups.push(this.collections[i])

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
    }
  },
  methods: {},
  mounted() {
    this.$store.dispatch('user/loadUserCollections')
  }
}
</script>