<template>
  <div class="w-full h-9 bg-bg relative">
    <div id="bookshelf-navbar" class="absolute z-10 top-0 left-0 w-full h-full flex bg-secondary text-gray-200">
      <nuxt-link v-for="item in items" :key="item.to" :to="item.to" class="h-full flex-grow flex items-center justify-center" :class="routeName === item.routeName ? 'bg-primary' : 'text-gray-400'">
        <p v-if="routeName === item.routeName" class="text-sm font-semibold">{{ item.text }}</p>
        <span v-else-if="item.iconPack === 'abs-icons'" class="abs-icons" :class="`icon-${item.icon} ${item.iconClass || ''}`"></span>
        <span v-else :class="`${item.iconPack} ${item.iconClass || ''}`">{{ item.icon }}</span>
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
    currentLibrary() {
      return this.$store.getters['libraries/getCurrentLibrary']
    },
    currentLibraryIcon() {
      return this.currentLibrary ? this.currentLibrary.icon : 'database'
    },
    items() {
      if (this.isPodcast) {
        return [
          {
            to: '/bookshelf',
            routeName: 'bookshelf',
            iconPack: 'abs-icons',
            icon: 'home',
            iconClass: 'text-xl',
            text: 'Home'
          },
          {
            to: '/bookshelf/latest',
            routeName: 'bookshelf-latest',
            iconPack: 'abs-icons',
            icon: 'list',
            iconClass: 'text-xl',
            text: 'Latest'
          },
          {
            to: '/bookshelf/library',
            routeName: 'bookshelf-library',
            iconPack: 'abs-icons',
            icon: this.currentLibraryIcon,
            iconClass: 'text-lg',
            text: 'Library'
          }
        ]
      }
      return [
        {
          to: '/bookshelf',
          routeName: 'bookshelf',
          iconPack: 'abs-icons',
          icon: 'home',
          iconClass: 'text-xl',
          text: 'Home'
        },
        {
          to: '/bookshelf/library',
          routeName: 'bookshelf-library',
          iconPack: 'abs-icons',
          icon: this.currentLibraryIcon,
          iconClass: 'text-lg',
          text: 'Library'
        },
        {
          to: '/bookshelf/series',
          routeName: 'bookshelf-series',
          iconPack: 'abs-icons',
          icon: 'columns',
          iconClass: 'text-lg pt-px',
          text: 'Series'
        },
        {
          to: '/bookshelf/collections',
          routeName: 'bookshelf-collections',
          iconPack: 'material-icons-outlined',
          icon: 'collections_bookmark',
          iconClass: 'text-xl',
          text: 'Collections'
        },
        // {
        //   to: '/bookshelf/authors',
        //   routeName: 'bookshelf-authors',
        //   iconPack: 'abs-icons',
        //   icon: 'authors',
        //   iconClass: 'text-2xl pb-px',
        //   text: 'Authors'
        // },
        {
          to: '/bookshelf/playlists',
          routeName: 'bookshelf-playlists',
          iconPack: 'material-icons',
          icon: 'queue_music',
          text: 'Playlists'
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
  methods: {
    isSelected(item) {}
  },
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