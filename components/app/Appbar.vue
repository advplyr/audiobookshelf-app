<template>
  <div class="w-full h-16 bg-primary relative">
    <div id="appbar" class="absolute top-0 left-0 w-full h-full z-30 flex items-center px-2">
      <nuxt-link v-show="!showBack" to="/" class="mr-3">
        <img src="/Logo.png" class="h-10 w-10" />
      </nuxt-link>
      <a v-if="showBack" @click="back" class="rounded-full h-10 w-10 flex items-center justify-center hover:bg-white hover:bg-opacity-10 mr-2 cursor-pointer">
        <span class="material-icons text-3xl text-white">arrow_back</span>
      </a>
      <div>
        <p class="text-lg font-book leading-4">AudioBookshelf</p>
      </div>
      <div class="flex-grow" />
      <!-- <ui-menu :label="username" :items="menuItems" @action="menuAction" class="ml-5" /> -->

      <span class="material-icons cursor-pointer mx-4" @click="$store.commit('downloads/setShowModal', true)">source</span>

      <widgets-connection-icon />

      <!-- <nuxt-link to="/account" class="relative w-28 bg-fg border border-gray-500 rounded shadow-sm ml-5 pl-3 pr-10 py-2 text-left focus:outline-none sm:text-sm cursor-pointer hover:bg-bg hover:bg-opacity-40" aria-haspopup="listbox" aria-expanded="true">
        <span class="flex items-center">
          <span class="block truncate">{{ username }}</span>
        </span>
        <span class="ml-3 absolute inset-y-0 right-0 flex items-center pr-2 pointer-events-none">
          <span class="material-icons text-gray-100">person</span>
        </span>
      </nuxt-link> -->
    </div>
  </div>
</template>

<script>
export default {
  data() {
    return {
      menuItems: [
        {
          value: 'account',
          text: 'Account',
          to: '/account'
        },
        {
          value: 'logout',
          text: 'Logout'
        }
      ]
    }
  },
  computed: {
    showBack() {
      return this.$route.name !== 'index'
    },
    user() {
      return this.$store.state.user.user
    },
    username() {
      return this.user ? this.user.username : 'err'
    },
    appListingUrl() {
      if (this.$platform === 'android') {
        return process.env.ANDROID_APP_URL
      } else {
        return process.env.IOS_APP_URL
      }
    }
  },
  methods: {
    back() {
      if (this.$route.name === 'audiobook-id-edit') {
        this.$router.push(`/audiobook/${this.$route.params.id}`)
      } else {
        this.$router.push('/')
      }
    },
    logout() {
      this.$axios.$post('/logout').catch((error) => {
        console.error(error)
      })
      this.$server.logout()
      this.$router.push('/connect')
    },
    menuAction(action) {
      if (action === 'logout') {
        this.logout()
      }
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