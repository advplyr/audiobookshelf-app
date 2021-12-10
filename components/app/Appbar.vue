<template>
  <div class="w-full h-16 bg-primary relative">
    <div id="appbar" class="absolute top-0 left-0 w-full h-full z-10 flex items-center px-2">
      <nuxt-link v-show="!showBack" to="/" class="mr-3">
        <img src="/Logo.png" class="h-10 w-10" />
      </nuxt-link>
      <a v-if="showBack" @click="back" class="rounded-full h-10 w-10 flex items-center justify-center hover:bg-white hover:bg-opacity-10 mr-2 cursor-pointer">
        <span class="material-icons text-3xl text-white">arrow_back</span>
      </a>
      <div v-if="socketConnected">
        <div class="px-4 py-2 bg-bg bg-opacity-30 rounded-md flex items-center" @click="clickShowLibraryModal">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 7v10c0 2.21 3.582 4 8 4s8-1.79 8-4V7M4 7c0 2.21 3.582 4 8 4s8-1.79 8-4M4 7c0-2.21 3.582-4 8-4s8 1.79 8 4m0 5c0 2.21-3.582 4-8 4s-8-1.79-8-4" />
          </svg>
          <p class="text-lg font-book leading-4 ml-2">{{ currentLibraryName }}</p>
        </div>
      </div>
      <div class="flex-grow" />

      <nuxt-link class="h-7 mx-2" to="/search">
        <span class="material-icons" style="font-size: 1.75rem">search</span>
      </nuxt-link>

      <div class="h-7 mx-2">
        <span class="material-icons" style="font-size: 1.75rem" @click="clickShowSideDrawer">menu</span>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  data() {
    return {}
  },
  computed: {
    socketConnected() {
      return this.$store.state.socketConnected
    },
    currentLibrary() {
      return this.$store.getters['libraries/getCurrentLibrary']
    },
    currentLibraryName() {
      return this.currentLibrary ? this.currentLibrary.name : 'Main'
    },
    showBack() {
      return this.$route.name !== 'index' && !this.$route.name.startsWith('bookshelf')
    },
    user() {
      return this.$store.state.user.user
    },
    username() {
      return this.user ? this.user.username : 'err'
    }
  },
  methods: {
    clickShowSideDrawer() {
      this.$store.commit('setShowSideDrawer', true)
    },
    clickShowLibraryModal() {
      this.$store.commit('libraries/setShowModal', true)
    },
    back() {
      window.history.back()
    }
  },
  mounted() {}
}
</script>

<style>
#appbar {
  box-shadow: 0px 5px 5px #11111155;
}
.loader-dots div {
  animation-timing-function: cubic-bezier(0, 1, 1, 0);
}
.loader-dots div:nth-child(1) {
  left: 0px;
  animation: loader-dots1 0.6s infinite;
}
.loader-dots div:nth-child(2) {
  left: 0px;
  animation: loader-dots2 0.6s infinite;
}
.loader-dots div:nth-child(3) {
  left: 10px;
  animation: loader-dots2 0.6s infinite;
}
.loader-dots div:nth-child(4) {
  left: 20px;
  animation: loader-dots3 0.6s infinite;
}
@keyframes loader-dots1 {
  0% {
    transform: scale(0);
  }
  100% {
    transform: scale(1);
  }
}
@keyframes loader-dots3 {
  0% {
    transform: scale(1);
  }
  100% {
    transform: scale(0);
  }
}
@keyframes loader-dots2 {
  0% {
    transform: translate(0, 0);
  }
  100% {
    transform: translate(10px, 0);
  }
}
</style>