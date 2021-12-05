<template>
  <bookshelf-lazy-bookshelf page="series-books" :series-id="seriesId" />
</template>

<script>
export default {
  asyncData({ params }) {
    return {
      seriesId: params.id
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