<template>
  <div class="w-full h-full">
    <div class="relative flex items-center justify-center min-h-screen sm:pt-0">
      <nuxt-link to="/" class="absolute top-2 left-2 z-20">
        <span class="material-icons text-4xl">arrow_back</span>
      </nuxt-link>
      <div class="absolute top-0 left-0 w-full p-6 flex items-center flex-col justify-center z-0 short:hidden">
        <img src="/Logo.png" class="h-20 w-20 mb-2" />
        <h1 class="text-2xl">audiobookshelf</h1>
      </div>
      <p class="hidden absolute short:block top-1.5 left-12 p-2text-xl">audiobookshelf</p>

      <connection-server-connect-form v-if="deviceData" />
    </div>

    <div class="flex items-center justify-center pt-4 fixed bottom-4 left-0 right-0">
      <a href="https://github.com/advplyr/audiobookshelf-app" target="_blank" class="text-sm pr-2">Follow the project on Github</a>
      <a href="https://github.com/advplyr/audiobookshelf-app" target="_blank"
        ><svg class="w-8 h-8 text-gray-100" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" aria-hidden="true" role="img" width="32" height="32" preserveAspectRatio="xMidYMid meet" viewBox="0 0 24 24">
          <path
            d="M12 2.247a10 10 0 0 0-3.162 19.487c.5.088.687-.212.687-.475c0-.237-.012-1.025-.012-1.862c-2.513.462-3.163-.613-3.363-1.175a3.636 3.636 0 0 0-1.025-1.413c-.35-.187-.85-.65-.013-.662a2.001 2.001 0 0 1 1.538 1.025a2.137 2.137 0 0 0 2.912.825a2.104 2.104 0 0 1 .638-1.338c-2.225-.25-4.55-1.112-4.55-4.937a3.892 3.892 0 0 1 1.025-2.688a3.594 3.594 0 0 1 .1-2.65s.837-.262 2.75 1.025a9.427 9.427 0 0 1 5 0c1.912-1.3 2.75-1.025 2.75-1.025a3.593 3.593 0 0 1 .1 2.65a3.869 3.869 0 0 1 1.025 2.688c0 3.837-2.338 4.687-4.563 4.937a2.368 2.368 0 0 1 .675 1.85c0 1.338-.012 2.413-.012 2.75c0 .263.187.575.687.475A10.005 10.005 0 0 0 12 2.247z"
            fill="currentColor"
          /></svg
      ></a>
    </div>
  </div>
</template>

<script>
export default {
  layout: 'blank',
  data() {
    return {
      deviceData: null
    }
  },
  computed: {
    networkConnected() {
      return this.$store.state.networkConnected
    }
  },
  methods: {
    async init() {
      this.deviceData = await this.$db.getDeviceData()
      this.$store.commit('setDeviceData', this.deviceData)
      await this.$store.dispatch('setupNetworkListener')
    }
  },
  mounted() {
    // Reset data on logouts
    this.$store.commit('libraries/reset')
    this.$store.commit('setIsFirstLoad', true)
    this.init()
  }
}
</script>