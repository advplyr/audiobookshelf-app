<template>
  <div class="w-full h-9 bg-bg relative">
    <div id="bookshelf-navbar" class="absolute z-10 top-0 left-0 w-full h-full flex bg-secondary text-gray-200">
      <nuxt-link v-for="item in items" :key="item.to" :to="item.to" class="h-full flex items-center justify-center" :style="{ width: isPodcast ? '50%' : '25%' }" :class="routeName === item.routeName ? 'bg-primary' : 'text-gray-400'">
        <p>{{ item.text }}</p>
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
    items() {
      if (this.isPodcast) {
        return [
          {
            to: '/bookshelf',
            routeName: 'bookshelf',
            text: 'Home'
          },
          {
            to: '/bookshelf/library',
            routeName: 'bookshelf-library',
            text: 'Library'
          }
        ]
      }
      return [
        {
          to: '/bookshelf',
          routeName: 'bookshelf',
          text: 'Home'
        },
        {
          to: '/bookshelf/library',
          routeName: 'bookshelf-library',
          text: 'Library'
        },
        {
          to: '/bookshelf/series',
          routeName: 'bookshelf-series',
          text: 'Series'
        },
        {
          to: '/bookshelf/collections',
          routeName: 'bookshelf-collections',
          text: 'Collections'
        }
      ]
    },
    routeName() {
      return this.$route.name
    },
    isPodcast() {
      return this.libraryMediaType == 'podcast'
    },
    libraryMediaType() {
      return this.$store.getters['libraries/getCurrentLibraryMediaType']
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