<template>
  <div class="w-full h-full flex flex-col">
    <!-- Tab switcher -->
    <div class="flex items-center justify-center px-4 pt-4 pb-2">
      <div class="bg-surface-container rounded-full p-1 flex items-center shadow-elevation-1">
        <button class="px-4 py-2 rounded-full text-label-medium font-medium transition-all duration-200 ease-expressive min-w-24" :class="currentView === 'collections' ? 'bg-primary text-on-primary shadow-elevation-2' : 'text-on-surface-variant hover:bg-on-surface/8'" @click="currentView = 'collections'">
          {{ $strings.ButtonCollections }}
        </button>
        <button class="px-4 py-2 rounded-full text-label-medium font-medium transition-all duration-200 ease-expressive min-w-24" :class="currentView === 'playlists' ? 'bg-primary text-on-primary shadow-elevation-2' : 'text-on-surface-variant hover:bg-on-surface/8'" @click="currentView = 'playlists'">
          {{ $strings.ButtonPlaylists }}
        </button>
      </div>
    </div>

    <!-- Content area -->
    <div class="flex-grow" :style="contentPaddingStyle">
      <bookshelf-lazy-bookshelf :key="currentView" :page="currentView" />
    </div>
  </div>
</template>

<script>
export default {
  data() {
    return {
      currentView: 'collections'
    }
  },
  computed: {
    userHasPlaylists() {
      return this.$store.state.libraries.numUserPlaylists
    }
  },
  mounted() {
    // Check if we should default to playlists view based on route query or user preference
    if (this.$route.query.view === 'playlists' && this.userHasPlaylists) {
      this.currentView = 'playlists'
    } else if (!this.userHasPlaylists) {
      // If user has no playlists, stay on collections
      this.currentView = 'collections'
    }
    // Otherwise default to collections
  },
  watch: {
    // Update URL query when view changes
    currentView(newView) {
      if (this.$route.query.view !== newView) {
        this.$router.replace({
          path: this.$route.path,
          query: { ...this.$route.query, view: newView }
        })
      }
    }
  },
  computed: {
    contentPaddingStyle() {
      return this.$store.getters['getIsPlayerOpen'] ? { paddingBottom: '120px' } : {}
    }
  }
}
</script>
