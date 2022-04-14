<template>
  <bookshelf-lazy-bookshelf page="series-books" :series-id="seriesId" />
</template>

<script>
export default {
  async asyncData({ params, app, store, redirect }) {
    var series = await app.$axios.$get(`/api/series/${params.id}`).catch((error) => {
      console.error('Failed', error)
      return false
    })
    if (!series) {
      return redirect('/oops?message=Series not found')
    }
    store.commit('globals/setSeries', series)
    return {
      series,
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