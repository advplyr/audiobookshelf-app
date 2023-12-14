<template>
  <div class="w-full h-full">
    <home-bookshelf-nav-bar />
    <home-bookshelf-toolbar v-show="!hideToolbar" />
    <div id="bookshelf-wrapper" class="main-content overflow-y-auto overflow-x-hidden relative" :class="hideToolbar ? 'no-toolbar' : ''">
      <nuxt-child />
    </div>
  </div>
</template>

<script>
export default {
  data() {
    return {}
  },
  computed: {
    hideToolbar() {
      return this.isHome || this.isLatest || this.isPodcastSearch
    },
    isHome() {
      return this.$route.name === 'bookshelf'
    },
    isLatest() {
      return this.$route.name === 'bookshelf-latest'
    },
    isPodcastSearch() {
      return this.$route.name === 'bookshelf-add-podcast'
    }
  }
}
</script>

<style>
.main-content {
  height: calc(100% - 72px);
  max-height: calc(100% - 72px);
  min-height: calc(100% - 72px);
  max-width: 100vw;
}
.main-content.no-toolbar {
  height: calc(100% - 36px);
  max-height: calc(100% - 36px);
  min-height: calc(100% - 36px);
}
</style>