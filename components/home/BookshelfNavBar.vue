<template>
  <div class="w-full h-9 bg-bg relative">
    <div id="bookshelf-navbar" class="absolute z-10 top-0 left-0 w-full h-full flex bg-secondary text-gray-200">
      <nuxt-link to="/bookshelf" class="w-1/4 h-full flex items-center justify-center" :class="routeName === 'bookshelf' ? 'bg-primary' : 'text-gray-400'">
        <p>Home</p>
      </nuxt-link>
      <nuxt-link to="/bookshelf/library" class="w-1/4 h-full flex items-center justify-center" :class="routeName === 'bookshelf-library' ? 'bg-primary' : 'text-gray-400'">
        <p>Library</p>
      </nuxt-link>
      <nuxt-link to="/bookshelf/series" class="w-1/4 h-full flex items-center justify-center" :class="routeName === 'bookshelf-series' ? 'bg-primary' : 'text-gray-400'">
        <p>Series</p>
      </nuxt-link>
      <nuxt-link to="/bookshelf/collections" class="w-1/4 h-full flex items-center justify-center" :class="routeName === 'bookshelf-collections' ? 'bg-primary' : 'text-gray-400'">
        <p>Collections</p>
      </nuxt-link>
    </div>
  </div>
</template>

<script>
export default {
  data() {
    return {}
  },
  computed: {
    routeName() {
      return this.$route.name
    }
  },
  methods: {},
  mounted() {}
}
</script>

<style>
#bookshelf-navbar {
  box-shadow: 0px 5px 5px #11111155;
}
#bookshelf-navbar a {
  font-size: 0.9rem;
}
</style>