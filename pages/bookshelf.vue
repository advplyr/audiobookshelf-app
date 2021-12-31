<template>
  <div class="w-full h-full">
    <home-bookshelf-nav-bar />
    <home-bookshelf-toolbar v-show="!isHome" />
    <div id="bookshelf-wrapper" class="main-content overflow-y-auto overflow-x-hidden relative" :class="isHome ? 'home-page' : ''">
      <nuxt-child />

      <!-- <div v-if="isLoading" class="absolute top-0 left-0 w-full h-full flex items-center justify-center">
        <ui-loading-indicator />
      </div>-->
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