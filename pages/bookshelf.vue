<template>
  <div class="w-full h-full">
    <home-bookshelf-nav-bar />
    <home-bookshelf-toolbar v-show="!isHome && !isLatest" />
    <div id="bookshelf-wrapper" class="main-content overflow-y-auto overflow-x-hidden relative" :class="isHome ? 'home-page' : ''">
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
    isHome() {
      return this.$route.name === 'bookshelf'
    },
    isLatest() {
      return this.$route.name === 'bookshelf-latest'
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
.main-content.home-page {
  height: calc(100% - 36px);
  max-height: calc(100% - 36px);
  min-height: calc(100% - 36px);
}
</style>